package com.lancameras.nucleo.rede

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Soquete TCP bloqueante com tempos limite, para os protocolos das câmeras.
 *
 * Uma thread lê enquanto outra pode chamar [interromper] para acordá-la; o soquete só é fechado
 * por quem o abriu, em [fechar].
 */
class SoqueteTcp {
    @Volatile private var soquete: Socket? = null
    @Volatile private var entrada: InputStream? = null
    @Volatile private var saida: OutputStream? = null

    fun conectar(host: String, porta: Int, tempo: Double) {
        val s = Socket()
        try {
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, porta), (tempo * 1000).toInt().coerceAtLeast(1))
        } catch (e: UnknownHostException) {
            s.close()
            throw ErroDeRede.Resolucao(e.message ?: "endereço desconhecido")
        } catch (e: SocketTimeoutException) {
            s.close()
            throw ErroDeRede.TempoEsgotado("conexão com a câmera")
        } catch (e: IOException) {
            s.close()
            throw ErroDeRede.Conexao(e.message ?: e.javaClass.simpleName)
        }
        s.soTimeout = 5000
        entrada = s.getInputStream()
        saida = s.getOutputStream()
        soquete = s
    }

    /** Lê até [maximo] bytes. Devolve null se o tempo esgotar sem chegar nada; lança se a conexão cair ou for interrompida. */
    fun ler(maximo: Int, tempo: Double): ByteArray? {
        val s = soquete ?: throw ErroDeRede.Fechado()
        val e = entrada ?: throw ErroDeRede.Fechado()
        val buf = ByteArray(maximo)
        val n = try {
            s.soTimeout = (tempo * 1000).toInt().coerceAtLeast(1)
            e.read(buf)
        } catch (_: SocketTimeoutException) {
            return null
        } catch (x: SocketException) {
            if (s.isClosed || s.isInputShutdown) throw ErroDeRede.Fechado()
            throw ErroDeRede.Sistema(x.message ?: "falha na leitura")
        } catch (x: IOException) {
            throw ErroDeRede.Sistema(x.message ?: "falha na leitura")
        }
        if (n < 0) throw ErroDeRede.Fechado()
        return buf.copyOf(n)
    }

    fun escrever(dados: ByteArray) {
        val o = saida ?: throw ErroDeRede.Fechado()
        try {
            o.write(dados)
            o.flush()
        } catch (x: IOException) {
            throw ErroDeRede.Sistema(x.message ?: "conexão fechada ao enviar")
        }
    }

    /** Acorda quem estiver bloqueado em [ler]. Seguro de qualquer thread. */
    fun interromper() {
        val s = soquete ?: return
        try { s.shutdownInput() } catch (_: IOException) {}
        try { s.shutdownOutput() } catch (_: IOException) {}
    }

    fun fechar() {
        val s = soquete
        soquete = null
        entrada = null
        saida = null
        try { s?.close() } catch (_: IOException) {}
    }
}
