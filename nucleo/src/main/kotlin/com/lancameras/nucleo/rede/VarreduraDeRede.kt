package com.lancameras.nucleo.rede

import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.NoRouteToHostException
import java.net.SocketException
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel

/**
 * Acha câmeras sem multicast: bate na porta do ONVIF de cada endereço da sub-rede do Wi-Fi, poucos
 * de cada vez, e devolve quem atendeu. É o plano B quando o WS-Discovery não acha ninguém (roteador
 * que filtra multicast, Wi-Fi que perde os pacotes).
 */
object VarreduraDeRede {
    /** Uma rede IPv4 de uma interface do aparelho (endereço e máscara como números sem sinal). */
    data class Rede(val interface_: String, val endereco: Long, val mascara: Long)

    class Resultado {
        /** Endereços que aceitaram a conexão, em ordem numérica. */
        val abertos = mutableListOf<String>()
        /** Quantos voltaram na hora com "sem rota". */
        var semRota = 0
        /** Quantos o sistema barrou (no Android 17, a permissão de rede local negada ao app). */
        var negados = 0
        var tentados = 0
    }

    sealed class Falha(mensagem: String) : Exception(mensagem) {
        class SemRedeLocal : Falha("o aparelho não está numa rede Wi-Fi")
        class SemPermissao : Falha("o app não tem acesso à rede local: libere a permissão de dispositivos próximos nas configurações")
    }

    // Redes do aparelho

    /**
     * Redes privadas (10/8, 172.16/12, 192.168/16) das interfaces de Wi-Fi e cabo ativas; ficam de
     * fora loopback, VPN, rede celular e pontes de máquinas virtuais.
     */
    fun redesLocais(): List<Rede> {
        val redes = mutableListOf<Rede>()
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (_: SocketException) {
            emptyList()
        }
        for (ni in interfaces) {
            val nome = ni.name ?: continue
            val ativa = try { ni.isUp && !ni.isLoopback && !ni.isVirtual } catch (_: SocketException) { false }
            if (!ativa || !nomeDeRedeLocal(nome)) continue
            for (ia in ni.interfaceAddresses) {
                val a = ia.address as? Inet4Address ?: continue
                val prefixo = ia.networkPrefixLength.toInt()
                if (prefixo !in 0..32) continue
                val rede = Rede(nome, numero(a), mascara(prefixo))
                if (privada(rede.endereco) && redes.none { it.mascara == rede.mascara && (it.endereco and it.mascara) == (rede.endereco and rede.mascara) }) {
                    redes += rede
                }
            }
        }
        return redes
    }

    /** Wi-Fi e cabo: `wlan0`/`eth0` no Android e no Linux, `en0` no macOS. */
    fun nomeDeRedeLocal(nome: String): Boolean = nome.startsWith("wlan") || nome.startsWith("eth") || nome.startsWith("en")

    fun privada(e: Long): Boolean = e shr 24 == 10L || e shr 20 == 0xAC1L || e shr 16 == 0xC0A8L

    fun mascara(prefixo: Int): Long = if (prefixo == 0) 0L else (0xFFFFFFFFL shl (32 - prefixo)) and 0xFFFFFFFFL

    // Endereços a testar

    /**
     * Todos os endereços da rede menos o da rede, o de broadcast e o do próprio aparelho. Redes maiores
     * que o [limite] ficam só com os endereços mais perto do aparelho (as câmeras de casa costumam estar ali).
     */
    fun enderecos(rede: Rede, limite: Int = 1024): List<String> {
        val base = rede.endereco and rede.mascara
        val broadcast = base or (rede.mascara.inv() and 0xFFFFFFFFL)
        if (broadcast <= base + 1) return emptyList()                 // /31 e /32: não há hosts
        val primeiro = base + 1
        val ultimo = broadcast - 1
        val total = ultimo - primeiro + 1
        var inicio = primeiro
        var fim = ultimo
        if (total > limite) {
            val metade = (limite / 2).toLong()
            inicio = maxOf(primeiro, if (rede.endereco > metade) rede.endereco - metade else primeiro)
            fim = minOf(ultimo, inicio + limite)                       // um a mais: o próprio sai abaixo
            if (fim == ultimo) inicio = maxOf(primeiro, fim - limite)
        }
        val lista = ArrayList<String>(minOf(total, limite.toLong()).toInt())
        var e = inicio
        while (e <= fim) {
            if (e != rede.endereco) lista += texto(e)
            if (lista.size == limite) break
            e++
        }
        return lista
    }

    fun texto(e: Long): String = "${e shr 24 and 0xFF}.${e shr 16 and 0xFF}.${e shr 8 and 0xFF}.${e and 0xFF}"

    /** Só IPv4 em quatro números decimais; o resto é recusado (o endereço vai direto para um soquete). */
    fun numero(texto: String): Long? {
        val partes = texto.split(".")
        if (partes.size != 4) return null
        var n = 0L
        for (p in partes) {
            if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' }) return null
            val v = p.toInt()
            if (v > 255) return null
            n = (n shl 8) or v.toLong()
        }
        return n
    }

    fun numero(a: Inet4Address): Long = a.address.fold(0L) { n, b -> (n shl 8) or (b.toLong() and 0xFF) }

    // Teste das portas

    /**
     * Quais endereços aceitam conexão TCP na [porta]. Conecta [paralelo] de cada vez sem bloquear
     * e desiste de cada um depois de [tempo] segundos.
     */
    fun testar(hosts: List<String>, porta: Int, tempo: Double = 0.6, paralelo: Int = 64): Resultado {
        val r = Resultado()
        Selector.open().use { seletor ->
            for (lote in hosts.chunked(paralelo)) {
                val pendentes = HashMap<SocketChannel, String>()
                for (h in lote) {
                    val n = numero(h) ?: continue
                    r.tentados++
                    val canal = SocketChannel.open()
                    try {
                        canal.configureBlocking(false)
                        val endereco = InetSocketAddress(InetAddress.getByAddress(bytes(n)), porta)
                        if (canal.connect(endereco)) {
                            r.abertos += h
                            canal.close()
                        } else {
                            canal.register(seletor, SelectionKey.OP_CONNECT)
                            pendentes[canal] = h
                        }
                    } catch (e: IOException) {
                        classificar(e, r)
                        canal.close()
                    }
                }
                val limite = System.nanoTime() + (tempo * 1e9).toLong()
                while (pendentes.isNotEmpty()) {
                    val resta = (limite - System.nanoTime()) / 1_000_000
                    if (resta <= 0) break
                    seletor.select(resta)
                    val prontas = seletor.selectedKeys().toList()
                    seletor.selectedKeys().clear()
                    for (k in prontas) {
                        val canal = k.channel() as SocketChannel
                        val h = pendentes.remove(canal) ?: continue
                        try {
                            if (canal.finishConnect()) r.abertos += h
                        } catch (e: IOException) {
                            classificar(e, r)
                        }
                        k.cancel()
                        canal.close()
                    }
                }
                for (c in pendentes.keys) c.close()
                seletor.selectNow()                                    // tira do seletor as chaves canceladas
            }
        }
        r.abertos.sortBy { numero(it) ?: 0 }
        return r
    }

    private fun classificar(e: IOException, r: Resultado) {
        val m = e.message?.lowercase() ?: ""
        when {
            e is NoRouteToHostException || "unreachable" in m || "no route" in m -> r.semRota++
            "not permitted" in m || "permission denied" in m || "eperm" in m || "eacces" in m -> r.negados++
        }
    }

    private fun bytes(n: Long) = byteArrayOf((n shr 24).toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())

    /** Nada atendeu e quase tudo voltou "sem rota" ou barrado na hora: a rede local foi negada ao app. */
    fun pareceSemPermissao(r: Resultado): Boolean =
        r.abertos.isEmpty() && r.tentados > 0 && (r.semRota + r.negados) * 10 >= r.tentados * 9
}
