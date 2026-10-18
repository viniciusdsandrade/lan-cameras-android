package com.lancameras.nucleo.rtsp

import java.util.Base64

/** Descrição de sessão (RFC 4566) reduzida ao que o cliente RTSP precisa. */
class Sdp(val midias: List<Midia>) {
    class Midia(val tipo: String) {
        /** Tipos de carga (payload types) */
        val formatos = mutableListOf<Int>()
        var controle: String? = null
        /** tipo de carga -> "H265", "PCMA"... */
        val codificacoes = HashMap<Int, String>()
        /** tipo de carga -> taxa do relógio */
        val taxas = HashMap<Int, Int>()
        val fmtp = HashMap<Int, String>()

        val tipoDeCarga: Int? get() = formatos.firstOrNull()

        /** Nome do codec em maiúsculas ("H264", "H265", "PCMA"), se declarado. */
        val codificacao: String? get() = tipoDeCarga?.let { codificacoes[it] }?.uppercase()

        /** Parâmetros de `a=fmtp` do formato principal, como chave/valor. */
        val parametros: Map<String, String>
            get() {
                val bruto = tipoDeCarga?.let { fmtp[it] } ?: return emptyMap()
                val saida = HashMap<String, String>()
                for (parte in bruto.split(";")) {
                    val item = parte.trim()
                    val igual = item.indexOf('=')
                    if (igual < 0) continue
                    saida[item.substring(0, igual).trim().lowercase()] = item.substring(igual + 1)
                }
                return saida
            }

        /** NALs de parâmetros (VPS/SPS/PPS) declarados no SDP, sem prefixo de início. */
        val conjuntosDeParametros: List<ByteArray>
            get() {
                val p = parametros
                val nomes = mutableListOf<String>()
                p["sprop-parameter-sets"]?.let { nomes += it.split(",") }
                for (k in listOf("sprop-vps", "sprop-sps", "sprop-pps")) p[k]?.let { nomes += it.split(",") }
                return nomes.mapNotNull {
                    try { Base64.getDecoder().decode(it.trim()) } catch (_: IllegalArgumentException) { null }
                }.filter { it.isNotEmpty() }
            }
    }

    companion object {
        fun interpretar(texto: String): Sdp {
            val midias = mutableListOf<Midia>()
            var atual: Midia? = null
            for (linha in texto.replace("\r\n", "\n").split("\n")) {
                val l = linha.trim()
                if (l.startsWith("m=")) {
                    atual?.let { midias += it }
                    val campos = l.substring(2).split(" ").filter { it.isNotEmpty() }
                    val m = Midia(campos.firstOrNull() ?: "")
                    m.formatos += campos.drop(3).mapNotNull { it.toIntOrNull() }
                    atual = m
                } else if (atual != null && l.startsWith("a=")) {
                    val attr = l.substring(2)
                    when {
                        attr.startsWith("control:") -> atual.controle = attr.removePrefix("control:").trim()
                        attr.startsWith("rtpmap:") -> {
                            // a=rtpmap:98 H265/90000 (as câmeras ainda põem um espaço no fim)
                            val partes = attr.removePrefix("rtpmap:").trim().split(" ", limit = 2)
                            val pt = partes.getOrNull(0)?.toIntOrNull()
                            if (partes.size == 2 && pt != null) {
                                val cod = partes[1].split("/").map { it.trim() }
                                atual.codificacoes[pt] = cod[0]
                                cod.getOrNull(1)?.toIntOrNull()?.let { atual.taxas[pt] = it }
                            }
                        }
                        attr.startsWith("fmtp:") -> {
                            val partes = attr.removePrefix("fmtp:").trim().split(" ", limit = 2)
                            val pt = partes.getOrNull(0)?.toIntOrNull()
                            if (partes.size == 2 && pt != null) atual.fmtp[pt] = partes[1]
                        }
                    }
                }
            }
            atual?.let { midias += it }
            return Sdp(midias)
        }
    }
}
