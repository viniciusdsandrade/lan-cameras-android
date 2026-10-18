package com.lancameras.nucleo.onvif

import java.io.IOException
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.UUID

data class AparelhoDescoberto(
    /** Endereço de onde veio a resposta. */
    val host: String,
    val enderecosDeServico: List<String>,
    val escopos: List<String>,
    /** `urn:uuid:…` do aparelho; o mesmo aparelho pode responder por mais de um IP. */
    val identificador: String?,
)

/**
 * WS-Discovery (UDP multicast 239.255.255.250:3702): acha os aparelhos ONVIF da rede.
 *
 * As respostas voltam direto para o soquete que mandou a sonda. No Android, quem chama segura um
 * `WifiManager.MulticastLock` enquanto procura: sem ele alguns aparelhos descartam o multicast.
 */
object DescobertaWs {
    private const val GRUPO = "239.255.255.250"
    private const val PORTA = 3702

    fun sonda(): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<e:Envelope xmlns:e=\"http://www.w3.org/2003/05/soap-envelope\" " +
            "xmlns:w=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\" " +
            "xmlns:d=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\" " +
            "xmlns:dn=\"http://www.onvif.org/ver10/network/wsdl\"><e:Header>" +
            "<w:MessageID>uuid:${UUID.randomUUID()}</w:MessageID>" +
            "<w:To e:mustUnderstand=\"true\">urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To>" +
            "<w:Action e:mustUnderstand=\"true\">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action>" +
            "</e:Header><e:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></e:Body></e:Envelope>"

    /** Procura por [duracao] segundos, mandando a sonda por todas as interfaces de rede ativas. */
    fun descobrir(duracao: Double = 3.0): List<AparelhoDescoberto> {
        val achados = mutableListOf<AparelhoDescoberto>()
        MulticastSocket().use { s ->
            s.timeToLive = 2
            val destino = InetSocketAddress(InetAddress.getByName(GRUPO), PORTA)
            val pacote = sonda().toByteArray(Charsets.UTF_8)
            val interfaces = interfacesDeMulticast()
            fun enviarUmaVez() = repeat(2) {
                try { s.send(DatagramPacket(pacote, pacote.size, destino)) } catch (_: IOException) {}
            }
            // UDP perde pacote (principalmente no Wi-Fi): a sonda sai de novo a cada segundo.
            fun enviarSondas() {
                if (interfaces.isEmpty()) return enviarUmaVez()
                for (ni in interfaces) {
                    try { s.networkInterface = ni } catch (_: IOException) { continue }
                    enviarUmaVez()
                }
            }

            val buf = ByteArray(65535)
            val limite = System.nanoTime() + (duracao * 1e9).toLong()
            var proximoEnvio = System.nanoTime()
            while (true) {
                val agora = System.nanoTime()
                val restante = (limite - agora) / 1e9
                if (restante <= 0) break
                if (agora >= proximoEnvio) {
                    enviarSondas()
                    proximoEnvio = System.nanoTime() + 1_000_000_000L
                }
                val p = DatagramPacket(buf, buf.size)
                s.soTimeout = (minOf(restante, 0.5) * 1000).toInt().coerceAtLeast(1)
                try {
                    s.receive(p)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val host = p.address?.hostAddress ?: continue
                val a = interpretar(String(p.data, p.offset, p.length, Charsets.UTF_8), host) ?: continue
                if (a !in achados) achados += a
            }
        }
        return agrupar(achados)
    }

    /** Junta respostas do mesmo aparelho (mesmo `urn:uuid`) que veio por mais de um IP. */
    fun agrupar(lista: List<AparelhoDescoberto>): List<AparelhoDescoberto> {
        val saida = mutableListOf<AparelhoDescoberto>()
        for (a in lista) {
            if (a.identificador != null && saida.any { it.identificador == a.identificador }) continue
            if (a.identificador == null && saida.any { it.host == a.host }) continue
            saida += a
        }
        return saida
    }

    fun interpretar(xml: String, host: String): AparelhoDescoberto? {
        val enderecos = XmlMinimo.valores(xml, "XAddrs").flatMap { it.split(" ").filter(String::isNotEmpty) }
        if (enderecos.isEmpty()) return null
        val escopos = XmlMinimo.valores(xml, "Scopes").flatMap { it.split(" ").filter(String::isNotEmpty) }
        val id = XmlMinimo.valores(xml, "Address").firstOrNull { it.startsWith("urn:") }
        return AparelhoDescoberto(host, enderecos, escopos, id)
    }

    private fun interfacesDeMulticast(): List<NetworkInterface> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { ni ->
            ni.isUp && ni.supportsMulticast() && !ni.isLoopback && ni.inetAddresses.toList().any { it is Inet4Address }
        }
    } catch (_: SocketException) {
        emptyList()
    }
}
