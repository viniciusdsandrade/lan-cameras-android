package com.lancameras.nucleo.apoio

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Servidor RTSP mínimo no endereço de loopback que imita as câmeras XiongMai, inclusive o defeito
 * que mais atrapalha: manda quadros de áudio ANTES da resposta ao PLAY.
 */
class ServidorRtspFalso(
    private val sdp: String,
    private val pacotesDeVideo: List<ByteArray>,
    private val audioAntesDoPlay: List<ByteArray> = emptyList(),
    /** Na PRIMEIRA conexão, corta a conexão depois de mandar tantos pacotes (a rede caiu). */
    private val cortarPrimeiraConexaoApos: Int? = null,
    /** Na PRIMEIRA conexão, para de mandar vídeo (sem fechar) depois de tantos pacotes (a câmera travou). */
    private val calarPrimeiraConexaoApos: Int? = null,
) : AutoCloseable {
    private val escuta = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
    val porta: Int = escuta.localPort
    val url: String get() = "rtsp://${escuta.inetAddress.hostAddress}:$porta/fluxo"

    private val trava = Any()
    private val _metodos = mutableListOf<String>()
    private var _conexoes = 0

    val metodos: List<String> get() = synchronized(trava) { _metodos.toList() }
    val conexoes: Int get() = synchronized(trava) { _conexoes }

    fun iniciar(): ServidorRtspFalso {
        thread(name = "rtsp-falso", isDaemon = true) { servir() }
        return this
    }

    override fun close() = escuta.close()

    private fun servir() {
        while (true) {
            val c = try { escuta.accept() } catch (_: IOException) { return }
            val numero = synchronized(trava) { ++_conexoes }
            try {
                c.use { atender(it, primeira = numero == 1) }
            } catch (_: IOException) {
                // o cliente fechou no meio: normal quando o teste termina
            }
        }
    }

    private fun atender(c: Socket, primeira: Boolean) {
        val entrada = c.getInputStream()
        val saida = c.getOutputStream()
        pendente.reset()
        var setups = 0
        while (true) {
            val req = lerRequisicao(entrada) ?: return
            val linhas = req.split("\r\n")
            val metodo = linhas[0].split(" ").first()
            synchronized(trava) { _metodos += metodo }
            var cseq = "0"
            var transporte = ""
            for (l in linhas.drop(1)) {
                val baixo = l.lowercase()
                if (baixo.startsWith("cseq:")) cseq = l.substring(5).trim()
                if (baixo.startsWith("transport:")) transporte = l.substring(10).trim()
            }
            when (metodo) {
                "OPTIONS" -> enviar(saida, resposta(cseq, listOf("Public: OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN")))
                "DESCRIBE" -> enviar(saida, resposta(cseq, listOf(
                    "Content-Type: application/sdp",
                    "Content-Base: $url/",
                    "Content-Length: ${sdp.toByteArray().size}",
                ), sdp))
                "SETUP" -> {
                    setups++                                        // cada SETUP devolve outra sessão, como a câmera
                    enviar(saida, resposta(cseq, listOf("Session: ${1000 + setups};timeout=60", "Transport: $transporte;mode=PLAY")))
                }
                "PLAY" -> {
                    for (p in audioAntesDoPlay) enviarQuadro(saida, 2, p)
                    enviar(saida, resposta(cseq, listOf("Session: ${1000 + setups}", "Range: npt=now-")))
                    for ((enviados, p) in pacotesDeVideo.withIndex()) {
                        if (primeira && cortarPrimeiraConexaoApos != null && enviados >= cortarPrimeiraConexaoApos) return
                        if (primeira && calarPrimeiraConexaoApos != null && enviados >= calarPrimeiraConexaoApos) break
                        enviarQuadro(saida, 0, p)
                        Thread.sleep(1, 500_000)
                    }
                }
                "TEARDOWN" -> return
                else -> enviar(saida, resposta(cseq, emptyList()))
            }
        }
    }

    private fun resposta(cseq: String, cabecalhos: List<String>, corpo: String = ""): ByteArray {
        val t = StringBuilder("RTSP/1.0 200 OK\r\nCseq: $cseq\r\nServer: falso\r\n")
        for (h in cabecalhos) t.append(h).append("\r\n")
        t.append("\r\n").append(corpo)
        return t.toString().toByteArray()
    }

    /** O que já chegou e ainda não formou uma requisição (as conexões são atendidas uma por vez). */
    private val pendente = ByteArrayOutputStream()

    private fun lerRequisicao(entrada: InputStream): String? {
        val tmp = ByteArray(4096)
        while (true) {
            val buf = pendente.toByteArray()
            val i = (0..buf.size - 4).firstOrNull {
                buf[it].toInt() == 13 && buf[it + 1].toInt() == 10 && buf[it + 2].toInt() == 13 && buf[it + 3].toInt() == 10
            }
            if (i != null) {
                pendente.reset()
                pendente.write(buf, i + 4, buf.size - i - 4)
                return String(buf, 0, i)
            }
            val n = entrada.read(tmp)
            if (n <= 0) return null
            pendente.write(tmp, 0, n)
        }
    }

    private fun enviarQuadro(saida: OutputStream, canal: Int, pacote: ByteArray) =
        enviar(saida, bytes(0x24, canal, pacote.size shr 8, pacote.size and 0xFF) + pacote)

    private fun enviar(saida: OutputStream, dados: ByteArray) {
        saida.write(dados)
        saida.flush()
    }
}
