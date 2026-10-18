package com.lancameras.nucleo.modelo

import com.lancameras.nucleo.Redact
import com.lancameras.nucleo.apoio.ServidorHttpFalso
import com.lancameras.nucleo.apoio.ServidorHttpFalso.Resposta
import com.lancameras.nucleo.apoio.abrirEscutaLocal
import com.lancameras.nucleo.apoio.loopback
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.UUID

class ArmazenamentoETest {
    private val pastas = mutableListOf<File>()

    @After
    fun limpar() = pastas.forEach { it.deleteRecursively() }

    private fun pastaTemporaria(): File =
        File(System.getProperty("java.io.tmpdir"), "cameras-teste-${UUID.randomUUID()}").also { pastas += it }

    private fun camera(id: String, nome: String, host: String = "192.0.2.10") =
        CameraConfig(id = id, nome = nome, host = host, usuario = "fulano", hash = "AbC123xY", modelo = "Modelo-X")

    private fun permissoes(f: File): String =
        Files.getPosixFilePermissions(f.toPath()).map {
            when (it) {
                PosixFilePermission.OWNER_READ -> "ur"; PosixFilePermission.OWNER_WRITE -> "uw"; PosixFilePermission.OWNER_EXECUTE -> "ux"
                else -> "outros"
            }
        }.sorted().joinToString(",")

    @Test
    fun gravaEmPastaPrivadaEArquivoPrivado() {
        val pasta = pastaTemporaria()
        ArmazenamentoDeCameras(pasta).salvar(listOf(camera("A", "Uma")))
        assertEquals("ur,uw,ux", permissoes(pasta))
        assertEquals("ur,uw", permissoes(File(pasta, "cameras.json")))
        assertTrue("não deve sobrar arquivo temporário", pasta.list().orEmpty().none { it.endsWith(".tmp") })
    }

    @Test
    fun idaEVolta() {
        val a = ArmazenamentoDeCameras(pastaTemporaria())
        val c = camera("A", "Primeira")
        a.salvar(listOf(c))
        assertEquals(listOf(c), a.carregar())
    }

    @Test
    fun semArquivoDevolveListaVazia() {
        assertEquals(emptyList<CameraConfig>(), ArmazenamentoDeCameras(pastaTemporaria()).carregar())
    }

    @Test
    fun arquivoEstragadoDevolveListaVaziaEPermiteSalvarDeNovo() {
        val pasta = pastaTemporaria()
        val a = ArmazenamentoDeCameras(pasta)
        a.salvar(listOf(camera("A", "Uma")))
        File(pasta, "cameras.json").writeText("{ isto não é json")
        assertEquals(emptyList<CameraConfig>(), a.carregar())
        a.mesclar(listOf(camera("B", "Duas")))
        assertEquals(listOf("B"), a.carregar().map { it.id })
    }

    @Test
    fun mesclarPreservaNomeEOrdemEAtualizaOEndereco() {
        val a = ArmazenamentoDeCameras(pastaTemporaria())
        a.mesclar(listOf(camera("A", "Câmera 1"), camera("B", "Câmera 2")))
        a.atualizar("A") { it.copy(nome = "Renomeada") }
        a.atualizar("B") { it.copy(ordem = 0) }
        a.atualizar("A") { it.copy(ordem = 1) }
        // a descoberta roda de novo: a câmera A mudou de IP, apareceu a C
        a.mesclar(listOf(camera("A", "Câmera 1", host = "192.0.2.99"), camera("C", "Câmera 3")))
        val todas = a.carregar()
        assertEquals(listOf("B", "A", "C"), todas.map { it.id })
        assertEquals("o nome dado pelo usuário não se perde", "Renomeada", todas.first { it.id == "A" }.nome)
        assertEquals("o endereço novo vale", "192.0.2.99", todas.first { it.id == "A" }.host)
    }

    @Test
    fun atualizarCameraQueNaoExisteNaoFazNada() {
        val a = ArmazenamentoDeCameras(pastaTemporaria())
        a.mesclar(listOf(camera("A", "Uma")))
        a.atualizar("Z") { it.copy(nome = "Fantasma") }
        assertEquals(listOf("Uma"), a.carregar().map { it.nome })
    }

    @Test
    fun posicoesDoAppSaoGuardadasPorCamera() {
        val a = ArmazenamentoDeCameras(pastaTemporaria())
        a.registrarPreset("3", "A")
        a.registrarPreset("3", "A")
        a.registrarPreset("4", "A")
        a.registrarPreset("9", "B")
        assertEquals(setOf("3", "4"), a.presetsDoApp("A"))
        a.esquecerPreset("3", "A")
        assertEquals(setOf("4"), a.presetsDoApp("A"))
        assertEquals(setOf("9"), a.presetsDoApp("B"))
        assertEquals(emptySet<String>(), a.presetsDoApp("C"))
    }

    @Test
    fun descricaoDaCameraNaoMostraAsCredenciais() {
        val t = camera("A", "Primeira").toString()
        assertFalse(t.contains("AbC123xY"))
        assertFalse(t.contains("fulano"))
        assertFalse(t.contains("192.0.2.10"))
    }

    @Test
    fun urlDeVideoSegueOPadraoDaCamera() {
        val c = camera("A", "Primeira")
        val (u, h) = c.usuario to c.hash                      // montado por interpolação: ver a regra de privacidade
        assertEquals("rtsp://192.0.2.10:554/user=${u}_password=${h}_channel=0_stream=0&onvif=0.sdp?real_stream", c.urlDeVideo(StreamDeVideo.PRINCIPAL))
        assertTrue(c.urlDeVideo(StreamDeVideo.SECUNDARIO).contains("_stream=1&"))
        assertFalse(Redact.texto(c.urlDeVideo(StreamDeVideo.PRINCIPAL)).contains("AbC123xY"))
    }

    // Cofre

    /** Cofre de teste: troca os bytes por outros e confere um carimbo no fim, como o AES-GCM faria. */
    private class CofreDeTeste : Cofre {
        override fun cifrar(claro: ByteArray) = claro.map { (it.toInt() xor 0x5A).toByte() }.toByteArray() + byteArrayOf(0x7E)
        override fun decifrar(cifrado: ByteArray): ByteArray {
            require(cifrado.isNotEmpty() && cifrado.last() == 0x7E.toByte()) { "adulterado" }
            return cifrado.copyOf(cifrado.size - 1).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        }
    }

    @Test
    fun oArquivoDasCamerasPassaPeloCofreEOHashNaoFicaLegivel() {
        val pasta = pastaTemporaria()
        val a = ArmazenamentoDeCameras(pasta, CofreDeTeste())
        val c = camera("A", "Primeira")
        a.salvar(listOf(c))
        val bruto = File(pasta, "cameras.json").readBytes()
        assertFalse("o hash não pode aparecer no disco", String(bruto, Charsets.ISO_8859_1).contains("AbC123xY"))
        assertEquals(listOf(c), a.carregar())
    }

    @Test
    fun arquivoAdulteradoOuComOutraChaveViraListaVazia() {
        val pasta = pastaTemporaria()
        ArmazenamentoDeCameras(pasta, CofreDeTeste()).salvar(listOf(camera("A", "Uma")))
        val f = File(pasta, "cameras.json")
        f.writeBytes(f.readBytes().also { it[it.size - 1] = 0 })
        assertEquals(emptyList<CameraConfig>(), ArmazenamentoDeCameras(pasta, CofreDeTeste()).carregar())
    }

    // Cadastro

    private fun envelope(corpo: String) =
        "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\" " +
            "xmlns:tds=\"d\" xmlns:trt=\"m\" xmlns:tt=\"t\"><s:Body>$corpo</s:Body></s:Envelope>"

    @Test
    fun identificaQuemAtendeComoCameraEDescartaOResto() {
        ServidorHttpFalso { req ->
            if (req.corpo.contains("GetDeviceInformation")) {
                Resposta(envelope(
                    "<tds:GetDeviceInformationResponse><tds:Manufacturer>F</tds:Manufacturer>" +
                        "<tds:Model>Modelo-X</tds:Model><tds:FirmwareVersion>1</tds:FirmwareVersion>" +
                        "<tds:SerialNumber>SERIE-FALSA-0002</tds:SerialNumber></tds:GetDeviceInformationResponse>",
                ))
            } else {
                val usuario = "fulano"
                val senha = "AbC123xY"
                val uri = "rtsp://192.0.2.10:554/user=${usuario}_password=${senha}_channel=0_stream=0&amp;onvif=0.sdp?real_stream"
                Resposta(envelope("<trt:GetStreamUriResponse><trt:MediaUri><tt:Uri>$uri</tt:Uri></trt:MediaUri></trt:GetStreamUriResponse>"))
            }
        }.use { servidor ->
            val r = Cadastro.identificarTodos(listOf(loopback), servidor.porta)
            assertEquals(1, r.cameras.size)
            val c = r.cameras.first()
            assertEquals("SERIE-FALSA-0002", c.id)
            assertEquals("Câmera 1", c.nome)
            assertEquals(loopback, c.host)
            assertEquals("fulano", c.usuario)
            assertEquals("Modelo-X", c.modelo)
            assertTrue(r.falhas.isEmpty())
        }
        val livre = abrirEscutaLocal().use { it.localPort }
        val ninguem = Cadastro.identificarTodos(listOf(loopback), livre)
        assertTrue(ninguem.cameras.isEmpty())
        assertEquals(1, ninguem.falhas.size)
        assertFalse(ninguem.falhas[0].contains("AbC123xY"))
    }

    @Test
    fun enderecoValidoAceitaIpENomeSimplesERecusaOResto() {
        assertEquals("192.0.2.10", Cadastro.enderecoValido("  192.0.2.10 \n"))
        assertEquals("camera-a.example", Cadastro.enderecoValido("camera-a.example"))
        assertEquals("camera", Cadastro.enderecoValido("camera"))
        for (ruim in listOf("", "   ", "-x", ".x", "a..b", "a b", "a/b", "http://x", "x:554", "a@b", "ç.local", "a".repeat(254))) {
            assertNull("[$ruim]", Cadastro.enderecoValido(ruim))
        }
        assertNotNull(Cadastro.enderecoValido("a".repeat(63)))
    }
}
