package com.lancameras.nucleo.apoio

import java.io.ByteArrayOutputStream

/**
 * Faz o caminho inverso do depacotador: transforma NALs em pacotes RTP (RFC 6184 e RFC 7798),
 * usando NAL único, agregado (STAP-A / AP) e fragmentado (FU-A / FU).
 */
object Empacotador {
    /** [maxCarga] pequeno de propósito, para forçar a fragmentação dos quadros grandes. */
    fun h264(quadros: List<List<ByteArray>>, maxCarga: Int = 300, pt: Int = 96, passo: Long = 9000): List<ByteArray> =
        montar(quadros, pt, passo) { nals ->
            val cargas = mutableListOf<ByteArray>()
            var resto = nals
            if (nals.size >= 2 && nals[0][0].toInt() and 0x1F == 7 && nals[1][0].toInt() and 0x1F == 8) {
                val agregado = ByteArrayOutputStream()
                agregado.write((nals[0][0].toInt() and 0xE0) or 24)                     // STAP-A
                for (n in nals.subList(0, 2)) {
                    agregado.write(n.size shr 8); agregado.write(n.size and 0xFF); agregado.write(n)
                }
                cargas += agregado.toByteArray()
                resto = nals.drop(2)
            }
            for (n in resto) {
                if (n.size <= maxCarga) { cargas += n; continue }
                val indicador = (n[0].toInt() and 0xE0) or 28                         // FU-A
                val tipo = n[0].toInt() and 0x1F
                var pos = 1
                while (pos < n.size) {
                    val fim = minOf(pos + maxCarga - 2, n.size)
                    var cab = tipo
                    if (pos == 1) cab = cab or 0x80
                    if (fim == n.size) cab = cab or 0x40
                    cargas += bytes(indicador, cab) + n.copyOfRange(pos, fim)
                    pos = fim
                }
            }
            cargas
        }

    fun h265(quadros: List<List<ByteArray>>, maxCarga: Int = 300, pt: Int = 98, passo: Long = 9000): List<ByteArray> =
        montar(quadros, pt, passo) { nals ->
            val cargas = mutableListOf<ByteArray>()
            var resto = nals
            if (nals.take(3).map { (it[0].toInt() shr 1) and 0x3F } == listOf(32, 33, 34)) {
                val agregado = ByteArrayOutputStream()
                agregado.write((nals[0][0].toInt() and 0x81) or (48 shl 1)); agregado.write(nals[0][1].toInt())  // AP
                for (n in nals.subList(0, 3)) {
                    agregado.write(n.size shr 8); agregado.write(n.size and 0xFF); agregado.write(n)
                }
                cargas += agregado.toByteArray()
                resto = nals.drop(3)
            }
            for (n in resto) {
                if (n.size <= maxCarga) { cargas += n; continue }
                val tipo = (n[0].toInt() shr 1) and 0x3F
                var pos = 2
                while (pos < n.size) {
                    val fim = minOf(pos + maxCarga - 3, n.size)
                    var cab = tipo                                                      // FU
                    if (pos == 2) cab = cab or 0x80
                    if (fim == n.size) cab = cab or 0x40
                    cargas += bytes((n[0].toInt() and 0x81) or (49 shl 1), n[1].toInt(), cab) + n.copyOfRange(pos, fim)
                    pos = fim
                }
            }
            cargas
        }

    /** Um pacote de áudio PCMA (tipo 8) de 40 ms, só silêncio. */
    fun audio(sequencia: Int): ByteArray =
        cabecalho(sequencia, sequencia * 320L, marcador = false, pt = 8) + ByteArray(320) { 0xD5.toByte() }

    private fun montar(
        quadros: List<List<ByteArray>>,
        pt: Int,
        passo: Long,
        cargas: (List<ByteArray>) -> List<ByteArray>,
    ): List<ByteArray> {
        val pacotes = mutableListOf<ByteArray>()
        var sequencia = 0
        for ((k, nals) in quadros.withIndex()) {
            val lista = cargas(nals)
            for ((i, c) in lista.withIndex()) {
                pacotes += cabecalho(sequencia, k * passo, marcador = i == lista.size - 1, pt = pt) + c
                sequencia = (sequencia + 1) and 0xFFFF
            }
        }
        return pacotes
    }

    fun cabecalho(sequencia: Int, timestamp: Long, marcador: Boolean, pt: Int): ByteArray = bytes(
        0x80, (if (marcador) 0x80 else 0) or pt,
        sequencia shr 8, sequencia and 0xFF,
        (timestamp shr 24).toInt() and 0xFF, (timestamp shr 16).toInt() and 0xFF,
        (timestamp shr 8).toInt() and 0xFF, timestamp.toInt() and 0xFF,
        0x12, 0x34, 0x56, 0x78,
    )
}
