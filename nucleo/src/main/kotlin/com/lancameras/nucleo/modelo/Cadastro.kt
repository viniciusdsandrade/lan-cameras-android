package com.lancameras.nucleo.modelo

import com.lancameras.nucleo.onvif.ClienteOnvif
import com.lancameras.nucleo.onvif.DescobertaWs
import com.lancameras.nucleo.rede.VarreduraDeRede

/**
 * Como uma câmera entra no app: acha na rede (ou pelo endereço digitado), pergunta quem ela é e pega
 * as credenciais que o próprio firmware entrega. Nada é digitado pelo usuário.
 */
object Cadastro {
    class Resultado {
        val cameras = mutableListOf<CameraConfig>()
        /** Aparelhos que responderam mas não deu para identificar, com o motivo. */
        val falhas = mutableListOf<String>()
    }

    /** Lê o número de série, o modelo e as credenciais de uma câmera. */
    fun identificar(host: String, porta: Int = 8899, nome: String? = null): CameraConfig {
        val cliente = ClienteOnvif(host, porta)
        val info = cliente.informacoes()
        val cred = cliente.credenciais()
        return CameraConfig(id = info.serie, nome = nome ?: "Câmera", host = host, usuario = cred.usuario, hash = cred.hash, modelo = info.modelo)
    }

    /**
     * Procura câmeras por WS-Discovery e identifica cada uma. No Android, [seguraMulticast] pega o
     * `MulticastLock` do Wi-Fi durante a busca e o solta no fim.
     */
    fun procurar(duracao: Double = 4.0, seguraMulticast: () -> AutoCloseable = { AutoCloseable {} }): Resultado =
        identificarTodos(seguraMulticast().use { DescobertaWs.descobrir(duracao) }.map { it.host })

    /** Procura câmeras batendo na porta do ONVIF de cada endereço do Wi-Fi (sem multicast). */
    fun procurarPorVarredura(porta: Int = 8899): Resultado {
        val redes = VarreduraDeRede.redesLocais()
        if (redes.isEmpty()) throw VarreduraDeRede.Falha.SemRedeLocal()
        val hosts = mutableListOf<String>()
        for (rede in redes) hosts += VarreduraDeRede.enderecos(rede).filter { it !in hosts }
        val r = VarreduraDeRede.testar(hosts, porta)
        if (VarreduraDeRede.pareceSemPermissao(r)) throw VarreduraDeRede.Falha.SemPermissao()
        return identificarTodos(r.abertos, porta)
    }

    /** WS-Discovery e, se nada responder (roteador que filtra multicast), a varredura. */
    fun procurarNaRede(duracao: Double = 4.0, seguraMulticast: () -> AutoCloseable = { AutoCloseable {} }): Resultado {
        val porMulticast = try { procurar(duracao, seguraMulticast) } catch (_: Exception) { null }
        if (porMulticast != null && porMulticast.cameras.isNotEmpty()) return porMulticast
        return procurarPorVarredura()
    }

    /** Identifica cada endereço; os que não responderem como câmera vão para `falhas`. */
    fun identificarTodos(hosts: List<String>, porta: Int = 8899): Resultado {
        val r = Resultado()
        for (h in hosts) {
            try {
                r.cameras += identificar(h, porta, nome = "Câmera ${r.cameras.size + 1}")
            } catch (e: Exception) {
                r.falhas += "$h: $e"
            }
        }
        return r
    }

    /** Aceita um IPv4 ou um nome de rede simples; recusa o resto (o endereço vai direto para um soquete). */
    fun enderecoValido(texto: String): String? {
        val t = texto.trim()
        if (t.isEmpty() || t.length > 253) return null
        if (!t.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '.' || it == '-' }) return null
        if (t.startsWith("-") || t.startsWith(".")) return null
        val partes = t.split(".")
        val numeros = partes.map { p -> p.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }?.toIntOrNull() }
        if (partes.size == 4 && numeros.all { it != null && it in 0..255 }) return t
        return if (partes.all { it.isNotEmpty() }) t else null
    }
}
