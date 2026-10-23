package com.lancameras.nucleo.modelo

import com.lancameras.nucleo.apoio.ServidorHttpFalso
import com.lancameras.nucleo.apoio.ServidorHttpFalso.Resposta
import com.lancameras.nucleo.apoio.abrirEscutaLocal
import com.lancameras.nucleo.apoio.loopback
import com.lancameras.nucleo.onvif.ErroOnvif
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Test

private fun envelopeSoap(corpo: String) =
    "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\" " +
        "xmlns:tds=\"d\" xmlns:trt=\"m\" xmlns:tt=\"t\"><s:Body>$corpo</s:Body></s:Envelope>"

/**
 * Uma câmera já guardada que passou a recusar a senha ou a não responder: os dados atuais vêm da
 * própria câmera, no endereço guardado ou, se lá estiver outro aparelho (ou nenhum), procurando na
 * rede pelo número de série.
 */
class CadastroTest {
    /**
     * Câmera falsa que diz ser [serie] e entrega [senha] no endereço do vídeo (null: não entrega).
     * Valores inventados; a URL é montada por interpolação para não parecer credencial real.
     */
    private fun cameraFalsa(serie: String, senha: String?) = ServidorHttpFalso { req ->
        if (req.corpo.contains("GetDeviceInformation")) {
            Resposta(envelopeSoap(
                "<tds:GetDeviceInformationResponse><tds:Model>Modelo-X</tds:Model>" +
                    "<tds:SerialNumber>$serie</tds:SerialNumber></tds:GetDeviceInformationResponse>",
            ))
        } else {
            val usuario = "fulano"
            val uri = if (senha != null) "rtsp://192.0.2.10:554/user=${usuario}_password=${senha}_channel=0_stream=0&amp;onvif=0.sdp?real_stream"
            else "rtsp://192.0.2.10:554/stream=0"
            Resposta(envelopeSoap("<trt:GetStreamUriResponse><trt:MediaUri><tt:Uri>$uri</tt:Uri></trt:MediaUri></trt:GetStreamUriResponse>"))
        }
    }

    /** A câmera como estava guardada: senha velha, nome, ordem e lentes como o usuário deixou. */
    private val guardada = CameraConfig(
        id = "SERIE-FALSA-0003", nome = "Porta de entrada", host = loopback, usuario = "fulano",
        hash = "senha-velha", modelo = "Modelo-X", duasLentes = false, ordem = 2,
    )

    @Test
    fun senhaTrocadaVemDaPropriaCameraNoMesmoEndereco() {
        cameraFalsa("SERIE-FALSA-0003", "senha-nova").use { s ->
            val nova = Cadastro.reidentificar(guardada, s.porta) {
                fail("no endereço guardado está a mesma câmera: não precisa procurar")
                Cadastro.Resultado()
            }
            assertEquals("senha-nova", nova.hash)
            assertEquals("fulano", nova.usuario)
            assertEquals(loopback, nova.host)
            assertEquals("nome, ordem e lentes ficam como estavam", "Porta de entrada", nova.nome)
            assertEquals(2, nova.ordem)
            assertFalse(nova.duasLentes)
        }
    }

    @Test
    fun outroAparelhoNoEnderecoGuardadoFazProcurarPeloNumeroDeSerie() {
        // o roteador deu o endereço desta câmera a outra: lá responde outro número de série
        cameraFalsa("SERIE-FALSA-0001", "senha-da-outra").use { s ->
            val nova = Cadastro.reidentificar(guardada, s.porta) { serie ->
                assertEquals("procura pelo número de série da câmera guardada", "SERIE-FALSA-0003", serie)
                Cadastro.Resultado().apply {
                    cameras += CameraConfig(id = "SERIE-FALSA-0001", nome = "Câmera 1", host = loopback, usuario = "fulano", hash = "senha-da-outra")
                    cameras += CameraConfig(id = "SERIE-FALSA-0003", nome = "Câmera 2", host = "192.0.2.30", usuario = "fulano", hash = "senha-nova")
                }
            }
            assertEquals("192.0.2.30", nova.host)
            assertEquals("senha-nova", nova.hash)
            assertEquals("Porta de entrada", nova.nome)
        }
    }

    @Test
    fun enderecoGuardadoSemCameraFazProcurarPeloNumeroDeSerie() {
        // o roteador deu outro endereço à câmera, e no dela ninguém atende (ou um aparelho recusa a conexão)
        val livre = abrirEscutaLocal().use { it.localPort }
        val nova = Cadastro.reidentificar(guardada, livre) { serie ->
            Cadastro.Resultado().apply {
                cameras += CameraConfig(id = serie, nome = "Câmera 1", host = "192.0.2.31", usuario = "fulano", hash = "senha-velha")
            }
        }
        assertEquals("192.0.2.31", nova.host)
        assertEquals("só o endereço mudou", "senha-velha", nova.hash)
        assertEquals("nome, ordem e lentes ficam como estavam", "Porta de entrada", nova.nome)
        assertEquals(2, nova.ordem)
    }

    @Test
    fun cameraQueNaoEstaNoEnderecoNemNaRedeViraErro() {
        val livre = abrirEscutaLocal().use { it.localPort }          // ninguém atende no endereço guardado
        assertThrows(Cadastro.Falha.CameraSumiu::class.java) {
            Cadastro.reidentificar(guardada, livre) { Cadastro.Resultado() }
        }
    }

    @Test
    fun mesmaCameraQueNaoEntregaASenhaNaoFazProcurar() {
        cameraFalsa("SERIE-FALSA-0003", senha = null).use { s ->
            assertThrows(ErroOnvif::class.java) {
                Cadastro.reidentificar(guardada, s.porta) {
                    fail("é a mesma câmera: procurar na rede não adiantaria")
                    Cadastro.Resultado()
                }
            }
        }
    }
}
