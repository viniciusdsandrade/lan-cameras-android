package com.lancameras.nucleo.apoio

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** O endereço de loopback em texto, para montar URLs nos testes. */
val loopback: String = InetAddress.getLoopbackAddress().hostAddress

/** Abre um soquete de escuta no loopback numa porta livre (fila grande: alguns testes só conectam, sem aceitar). */
fun abrirEscutaLocal(): ServerSocket = ServerSocket(0, 64, InetAddress.getLoopbackAddress())

/** Lê exatamente [n] bytes, ou devolve null se a conexão acabar antes. */
fun InputStream.lerExato(n: Int): ByteArray? {
    val saida = ByteArray(n)
    var lidos = 0
    while (lidos < n) {
        val r = read(saida, lidos, n - lidos)
        if (r <= 0) return null
        lidos += r
    }
    return saida
}

/** Servidor HTTP no loopback que imita o SOAP das câmeras: uma requisição por conexão. */
class ServidorHttpFalso(private val manipulador: (Requisicao) -> Resposta) : AutoCloseable {
    enum class Modo { CONTENT_LENGTH, CHUNKED, ATE_FECHAR }
    data class Requisicao(val caminho: String, val corpo: String)
    data class Resposta(val corpo: String, val status: Int = 200, val modo: Modo = Modo.CONTENT_LENGTH)

    private val escuta = abrirEscutaLocal()
    val porta: Int = escuta.localPort
    private val trava = Any()
    private val _requisicoes = mutableListOf<Requisicao>()

    val requisicoes: List<Requisicao> get() = synchronized(trava) { _requisicoes.toList() }

    init {
        thread(name = "http-falso", isDaemon = true) {
            while (true) {
                val c = try { escuta.accept() } catch (_: IOException) { return@thread }
                try { c.use { atender(it) } } catch (_: IOException) {}
            }
        }
    }

    override fun close() = escuta.close()

    private fun atender(c: Socket) {
        val entrada = c.getInputStream()
        val buf = ByteArrayOutputStream()
        val tmp = ByteArray(8192)
        var posicaoDoCorpo = -1
        var tamanho = 0
        while (true) {
            val b = buf.toByteArray()
            if (posicaoDoCorpo < 0) {
                val i = (0..b.size - 4).firstOrNull { b[it].toInt() == 13 && b[it + 1].toInt() == 10 && b[it + 2].toInt() == 13 && b[it + 3].toInt() == 10 }
                if (i != null) {
                    posicaoDoCorpo = i + 4
                    val cab = String(b, 0, i).lowercase()
                    cab.split("\r\n").firstOrNull { it.startsWith("content-length:") }?.let { tamanho = it.substring(15).trim().toIntOrNull() ?: 0 }
                }
            }
            if (posicaoDoCorpo >= 0 && b.size >= posicaoDoCorpo + tamanho) break
            val n = entrada.read(tmp)
            if (n <= 0) return
            buf.write(tmp, 0, n)
        }
        val b = buf.toByteArray()
        val linhaInicial = String(b, 0, b.indexOf(13).coerceAtLeast(0))
        val caminho = linhaInicial.split(" ").getOrNull(1) ?: ""
        val req = Requisicao(caminho, String(b, posicaoDoCorpo, tamanho, Charsets.UTF_8))
        synchronized(trava) { _requisicoes += req }
        val r = manipulador(req)
        val corpo = r.corpo.toByteArray(Charsets.UTF_8)
        val cab = StringBuilder("HTTP/1.1 ${r.status} X\r\nContent-Type: application/soap+xml\r\nConnection: close\r\n")
        val carga = ByteArrayOutputStream()
        when (r.modo) {
            Modo.CONTENT_LENGTH -> { cab.append("Content-Length: ${corpo.size}\r\n"); carga.write(corpo) }
            Modo.CHUNKED -> {
                cab.append("Transfer-Encoding: chunked\r\n")
                var i = 0
                while (i < corpo.size) {                                // pedaços pequenos para exercitar a remontagem
                    val f = minOf(i + 37, corpo.size)
                    carga.write("${(f - i).toString(16)}\r\n".toByteArray()); carga.write(corpo, i, f - i); carga.write("\r\n".toByteArray())
                    i = f
                }
                carga.write("0\r\n\r\n".toByteArray())
            }
            Modo.ATE_FECHAR -> carga.write(corpo)
        }
        val saida: OutputStream = c.getOutputStream()
        saida.write(cab.append("\r\n").toString().toByteArray())
        saida.write(carga.toByteArray())
        saida.flush()
    }
}

/**
 * Outro aparelho no endereço que era da câmera (o roteador o deu a ele): tem RTSP, mas responde 405
 * ao OPTIONS e fecha a conexão no pedido seguinte, sem nunca mandar vídeo.
 */
class AparelhoQueNaoEhACamera : AutoCloseable {
    private val escuta = abrirEscutaLocal()
    val url: String get() = "rtsp://$loopback:${escuta.localPort}/fluxo"
    private val _conexoes = AtomicInteger()
    val conexoes: Int get() = _conexoes.get()

    init {
        thread(name = "aparelho-falso", isDaemon = true) {
            while (true) {
                val c = try { escuta.accept() } catch (_: IOException) { return@thread }
                _conexoes.incrementAndGet()
                try { c.use { atender(it) } } catch (_: IOException) {}
            }
        }
    }

    override fun close() = escuta.close()

    private fun atender(c: Socket) {
        val entrada = c.getInputStream()
        val saida = c.getOutputStream()
        val buf = ByteArrayOutputStream()
        val tmp = ByteArray(4096)
        while (true) {
            val b = buf.toByteArray()
            val i = (0..b.size - 4).firstOrNull { b[it].toInt() == 13 && b[it + 1].toInt() == 10 && b[it + 2].toInt() == 13 && b[it + 3].toInt() == 10 }
            if (i != null) {
                val pedido = String(b, 0, i)
                buf.reset()
                buf.write(b, i + 4, b.size - i - 4)
                if (!pedido.startsWith("OPTIONS")) return                 // DESCRIBE e o resto: fecha
                val cseq = pedido.split("\r\n").firstOrNull { it.lowercase().startsWith("cseq:") }?.substring(5)?.trim() ?: "0"
                saida.write("RTSP/1.0 405 Method Not Allowed\r\nCSeq: $cseq\r\n\r\n".toByteArray())
                saida.flush()
                continue
            }
            val n = entrada.read(tmp)
            if (n <= 0) return
            buf.write(tmp, 0, n)
        }
    }
}
