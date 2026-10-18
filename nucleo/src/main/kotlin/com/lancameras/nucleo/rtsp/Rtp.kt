package com.lancameras.nucleo.rtsp

/** Um pacote RTP (RFC 3550) já separado do cabeçalho. */
class PacoteRtp(
    val tipoDeCarga: Int,
    val marcador: Boolean,
    val sequencia: Int,
    val timestamp: Long,
    val ssrc: Long = 0,
    val carga: ByteArray,
) {
    companion object {
        /** Interpreta os bytes de um pacote RTP. Devolve null se estiver malformado. */
        fun de(b: ByteArray): PacoteRtp? {
            if (b.size < 12 || u(b, 0) shr 6 != 2) return null
            val temPreenchimento = u(b, 0) and 0x20 != 0
            val temExtensao = u(b, 0) and 0x10 != 0
            val csrc = u(b, 0) and 0x0F
            var inicio = 12 + 4 * csrc
            if (temExtensao) {
                if (b.size < inicio + 4) return null
                val palavras = u(b, inicio + 2) shl 8 or u(b, inicio + 3)
                inicio += 4 + 4 * palavras
            }
            var fim = b.size
            if (temPreenchimento) fim -= u(b, b.size - 1)
            if (inicio > fim || fim > b.size) return null
            return PacoteRtp(
                tipoDeCarga = u(b, 1) and 0x7F,
                marcador = u(b, 1) and 0x80 != 0,
                sequencia = u(b, 2) shl 8 or u(b, 3),
                timestamp = (u(b, 4).toLong() shl 24) or (u(b, 5).toLong() shl 16) or (u(b, 6).toLong() shl 8) or u(b, 7).toLong(),
                ssrc = (u(b, 8).toLong() shl 24) or (u(b, 9).toLong() shl 16) or (u(b, 10).toLong() shl 8) or u(b, 11).toLong(),
                carga = b.copyOfRange(inicio, fim),
            )
        }

        private fun u(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    }
}
