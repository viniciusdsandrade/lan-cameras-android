package com.lancameras.nucleo.video

import com.lancameras.nucleo.apoio.Amostras
import com.lancameras.nucleo.apoio.Empacotador
import com.lancameras.nucleo.apoio.ServidorRtspFalso
import com.lancameras.nucleo.apoio.esperar
import com.lancameras.nucleo.rtsp.ClienteRtsp
import com.lancameras.nucleo.rtsp.ErroRtsp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.util.Base64
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * O caminho inteiro (soquete, RTSP, RTP, depacotador, preparador) contra um servidor RTSP falso
 * que imita os defeitos das câmeras. O decodificador é falso: na JVM não há MediaCodec.
 */
class FluxoDeVideoTest {
    /** Confere cada quadro e "exibe" no tamanho que o SPS diz. */
    private class DecodificadorFalso(private val aoExibir: (TamanhoDoVideo) -> Unit) : Decodificador {
        var parametros: ParametrosDeVideo? = null
        val configuracoes = AtomicInteger()

        override fun configurar(parametros: ParametrosDeVideo) {
            this.parametros = parametros
            configuracoes.incrementAndGet()
        }

        override fun decodificar(quadro: QuadroDeVideo): Boolean {
            val tamanho = parametros?.tamanho ?: return false
            if (quadro.dados.take(4) != listOf<Byte>(0, 0, 0, 1)) return false
            aoExibir(tamanho)
            return true
        }

        override fun encerrar() {}
    }

    private val decodificadores = Collections.synchronizedList(mutableListOf<DecodificadorFalso>())

    private fun fluxo(url: String, incluirAudio: Boolean = false, limiteSemVideo: Double = 8.0) =
        FluxoDeVideo(url, incluirAudio, limiteSemVideo) { _, aoExibir -> DecodificadorFalso(aoExibir).also { decodificadores += it } }

    private fun sdp(h265: Boolean): String {
        val nals = Amostras.nals(h265)
        fun b64(tipo: Int) = Base64.getEncoder().encodeToString(nals.first { Amostras.tipo(it, h265) == tipo })
        val video = if (h265) {
            "m=video 0 RTP/AVP 98\r\na=rtpmap:98 H265/90000 \r\na=fmtp:98 sprop-vps=${b64(32)};sprop-sps=${b64(33)};sprop-pps=${b64(34)};\r\na=control:trackID=3\r\n"
        } else {
            "m=video 0 RTP/AVP 96\r\na=rtpmap:96 H264/90000 \r\na=fmtp:96 packetization-mode=1; sprop-parameter-sets=${b64(7)},${b64(8)}\r\na=control:trackID=3\r\n"
        }
        return "v=0\r\ns=Teste\r\nt=0 0\r\na=control:*\r\n" + video +
            "m=audio 0 RTP/AVP 8\r\na=rtpmap:8 PCMA/8000\r\na=control:trackID=4\r\n"
    }

    private fun rodar(h265: Boolean, incluirAudio: Boolean) {
        val quadros = Amostras.quadros(h265)
        val pacotes = if (h265) Empacotador.h265(quadros) else Empacotador.h264(quadros)
        val audioAntes = (0 until 3).map { Empacotador.audio(it) }
        ServidorRtspFalso(sdp(h265), pacotes, audioAntesDoPlay = audioAntes).iniciar().use { servidor ->
            val f = fluxo(servidor.url, incluirAudio)
            val audio = AtomicInteger()
            f.aoReceberAudio = { audio.incrementAndGet() }
            f.iniciar()
            try {
                assertTrue("só chegaram ${f.quadrosRecebidos} quadros", esperar { f.quadrosRecebidos >= 20 })
                assertEquals(20, f.quadrosRecebidos)
                val e = f.estado as? FluxoDeVideo.Estado.AoVivo ?: return fail("estado inesperado: ${f.estado}")
                assertEquals(if (h265) "H265" else "H264", e.codec)
                assertEquals(160, e.largura)
                assertEquals(96, e.altura)
                assertEquals(listOf("OPTIONS", "DESCRIBE", "SETUP"), servidor.metodos.take(3))
                assertTrue(servidor.metodos.contains("PLAY"))
                assertEquals("os parâmetros se repetem no vídeo, mas são os mesmos do SDP", 1, decodificadores.single().configuracoes.get())
                if (incluirAudio) {
                    assertTrue("o áudio que chegou antes do PLAY deveria ter sido entregue", audio.get() > 0)
                    assertEquals(2, servidor.metodos.count { it == "SETUP" })
                } else {
                    assertEquals(1, servidor.metodos.count { it == "SETUP" })
                    assertEquals(0, audio.get())
                }
            } finally {
                f.parar()
            }
        }
    }

    @Test fun fluxoCompletoH264() = rodar(h265 = false, incluirAudio = false)
    @Test fun fluxoCompletoH265() = rodar(h265 = true, incluirAudio = false)
    @Test fun fluxoComAudioChegandoAntesDoPlay() = rodar(h265 = false, incluirAudio = true)

    @Test
    fun pararLiberaASessaoNaCamera() {
        val quadros = Amostras.quadros(false)
        ServidorRtspFalso(sdp(false), Empacotador.h264(quadros)).iniciar().use { servidor ->
            val f = fluxo(servidor.url)
            f.iniciar()
            assertTrue(esperar { f.quadrosRecebidos >= 20 })
            f.parar()
            assertTrue("parar() deveria mandar TEARDOWN", esperar(3.0) { servidor.metodos.contains("TEARDOWN") })
            assertEquals(FluxoDeVideo.Estado.Parado, f.estado)
        }
    }

    @Test
    fun cameraInacessivelViraEstadoDeErroSemVazarOEndereco() {
        val loopback = InetAddress.getLoopbackAddress().hostAddress
        val f = fluxo("rtsp://$loopback:1/fluxo")                     // porta 1: ninguém escuta
        f.iniciar()
        try {
            assertTrue(esperar(5.0) { f.estado is FluxoDeVideo.Estado.Erro })
            val mensagem = (f.estado as FluxoDeVideo.Estado.Erro).mensagem
            assertFalse(mensagem.isEmpty())
            assertFalse(mensagem, mensagem.contains(loopback))
        } finally {
            f.parar()
        }
    }

    @Test
    fun enderecoInvalidoViraErro() {
        val f = fluxo("http://camera/fluxo")
        f.iniciar()
        try {
            assertTrue(esperar(3.0) { f.estado == FluxoDeVideo.Estado.Erro("o endereço da câmera é inválido") })
        } finally {
            f.parar()
        }
    }

    // Recuperação

    @Test
    fun conexaoQueCaiVoltaSozinhaEMostraImagemDeNovo() {
        val pacotes = Empacotador.h264(Amostras.quadros(false))
        // a primeira conexão morre no meio do vídeo (depois do primeiro quadro-chave); a segunda é completa
        ServidorRtspFalso(sdp(false), pacotes, cortarPrimeiraConexaoApos = pacotes.size / 3).iniciar().use { servidor ->
            val f = fluxo(servidor.url)
            val estados = Collections.synchronizedList(mutableListOf<FluxoDeVideo.Estado>())
            f.aoMudarEstado = { estados += it }
            f.iniciar()
            try {
                assertTrue(
                    "conexões: ${servidor.conexoes}, quadros: ${f.quadrosRecebidos}",
                    esperar(15.0) { servidor.conexoes >= 2 && f.quadrosRecebidos >= 20 },
                )
                val nomes = estados.toList().map { it::class.simpleName }
                val primeiroErro = nomes.indexOf("Erro")
                assertTrue("a queda deveria aparecer como erro: $nomes", primeiroErro >= 0)
                val depois = nomes.drop(primeiroErro)
                assertTrue("depois do erro deveria tentar de novo: $nomes", depois.contains("Conectando"))
                assertTrue("e voltar a mostrar imagem: $nomes", depois.contains("AoVivo"))
            } finally {
                f.parar()
            }
        }
    }

    @Test
    fun cameraQueFicaMudaSemFecharAConexaoEhDetectadaEReconecta() {
        val pacotes = Empacotador.h264(Amostras.quadros(false))
        ServidorRtspFalso(sdp(false), pacotes, calarPrimeiraConexaoApos = pacotes.size / 3).iniciar().use { servidor ->
            // o limite padrão é 8 s: reduzido para o teste, o mecanismo é o mesmo
            val f = fluxo(servidor.url, limiteSemVideo = 1.5)
            f.iniciar()
            try {
                assertTrue(
                    "conexões: ${servidor.conexoes}, quadros: ${f.quadrosRecebidos}",
                    esperar(20.0) { servidor.conexoes >= 2 && f.quadrosRecebidos >= 20 },
                )
            } finally {
                f.parar()
            }
        }
    }

    @Test
    fun pararEIniciarDeNovoNaoDeixaDuasConexoes() {
        val pacotes = Empacotador.h264(Amostras.quadros(false))
        ServidorRtspFalso(sdp(false), pacotes).iniciar().use { servidor ->
            val f = fluxo(servidor.url)
            f.iniciar()
            assertTrue(esperar { f.quadrosRecebidos >= 20 })
            f.parar()
            f.iniciar()
            try {
                assertTrue(esperar { servidor.conexoes >= 2 && f.estado is FluxoDeVideo.Estado.AoVivo })
                assertEquals(2, servidor.conexoes)
            } finally {
                f.parar()
            }
        }
    }

    // Senha recusada

    @Test
    fun cameraQueRecusaASenhaAvisaUmaVezENaoInsiste() {
        ServidorRtspFalso(sdp(false), emptyList(), statusDoDescribe = 401).iniciar().use { servidor ->
            val f = fluxo(servidor.url)
            val recusas = AtomicInteger()
            f.aoRecusarCredenciais = { recusas.incrementAndGet() }
            f.iniciar()
            try {
                assertTrue("deveria avisar que a câmera recusou a senha", esperar(5.0) { recusas.get() == 1 })
                val e = f.estado as? FluxoDeVideo.Estado.Erro ?: return fail("estado inesperado: ${f.estado}")
                assertTrue(e.mensagem, e.mensagem.contains("recusou o usuário ou a senha (401)"))
                Thread.sleep(3000)                                  // sem a recusa, tentaria de novo em 2 s
                assertEquals("com a mesma senha não deveria tentar de novo", 1, servidor.conexoes)
                assertEquals(1, recusas.get())
            } finally {
                f.parar()
            }
            assertEquals("parar() solta o fluxo parado no erro", FluxoDeVideo.Estado.Parado, f.estado)
        }
    }

    @Test
    fun so401E403ContamComoSenhaRecusada() {
        for ((status, recusada) in listOf(401 to true, 403 to true, 404 to false, 503 to false)) {
            ServidorRtspFalso(sdp(false), emptyList(), statusDoDescribe = status).iniciar().use { servidor ->
                val cliente = ClienteRtsp.criar(servidor.url) ?: return fail("URL do servidor falso inválida")
                val erro = assertThrows(ErroRtsp::class.java) {
                    cliente.executar(incluirAudio = false, aoFicarPronto = {}, aoReceberVideo = {})
                }
                assertEquals("$status: $erro", recusada, erro.credenciaisRecusadas)
            }
        }
    }
}
