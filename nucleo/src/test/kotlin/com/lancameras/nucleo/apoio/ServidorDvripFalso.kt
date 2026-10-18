package com.lancameras.nucleo.apoio

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.net.Socket
import kotlin.concurrent.thread

/** Servidor DVRIP no loopback (uma conexão por vez): responde o JSON de cada pedido e guarda tudo. */
class ServidorDvripFalso(
    /** Recebe (id, JSON do pedido); devolve o JSON da resposta, ou null para não responder. */
    private val responder: (Int, JsonObject) -> JsonObject?,
) : AutoCloseable {
    class Quadro(val id: Int, val sessao: Long, val corpo: ByteArray, /** System.nanoTime() da chegada */ val instante: Long)

    private val escuta = abrirEscutaLocal()
    val porta: Int = escuta.localPort
    private val trava = Any()
    private val _quadros = mutableListOf<Quadro>()

    val quadros: List<Quadro> get() = synchronized(trava) { _quadros.toList() }

    init {
        thread(name = "dvrip-falso", isDaemon = true) {
            while (true) {
                val c = try { escuta.accept() } catch (_: IOException) { return@thread }
                try { c.use { atender(it) } } catch (_: IOException) {}
            }
        }
    }

    override fun close() = escuta.close()

    private fun atender(c: Socket) {
        val entrada = c.getInputStream()
        val saida = c.getOutputStream()
        while (true) {
            val cab = entrada.lerExato(20) ?: return
            fun u(i: Int) = cab[i].toInt() and 0xFF
            val tam = u(16) or (u(17) shl 8) or (u(18) shl 16) or (u(19) shl 24)
            val corpo = if (tam > 0) entrada.lerExato(tam) ?: return else ByteArray(0)
            val id = u(14) or (u(15) shl 8)
            val sessao = u(4).toLong() or (u(5).toLong() shl 8) or (u(6).toLong() shl 16) or (u(7).toLong() shl 24)
            synchronized(trava) { _quadros += Quadro(id, sessao, corpo, System.nanoTime()) }
            var fim = corpo.size
            while (fim > 0 && (corpo[fim - 1].toInt() == 0 || corpo[fim - 1].toInt() == 0x0A)) fim--
            val json = try { Json.parseToJsonElement(String(corpo, 0, fim, Charsets.UTF_8)) as? JsonObject } catch (_: Exception) { null } ?: continue
            val resposta = responder(id, json) ?: continue
            val dados = resposta.toString().toByteArray(Charsets.UTF_8) + bytes(0x0A, 0x00)
            val h = ByteArray(20)
            h[0] = 0xFF.toByte()
            h[4] = 0x42                                            // a sessão que o login devolve
            h[14] = ((id + 1) and 0xFF).toByte(); h[15] = ((id + 1) shr 8).toByte()
            h[16] = (dados.size and 0xFF).toByte(); h[17] = ((dados.size shr 8) and 0xFF).toByte()
            saida.write(h + dados)
            saida.flush()
        }
    }
}
