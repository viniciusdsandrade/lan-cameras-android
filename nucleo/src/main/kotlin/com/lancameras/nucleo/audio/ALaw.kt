package com.lancameras.nucleo.audio

import kotlin.math.abs

/** G.711 A-law (ITU-T G.711): o áudio de ida e volta das câmeras, 8 kHz, mono. */
object ALaw {
    /** 0xD5 é a amostra zero em A-law: o "silêncio" que completa pacotes curtos. */
    const val SILENCIO: Byte = 0xD5.toByte()

    private val fimDeSegmento = intArrayOf(0x1F, 0x3F, 0x7F, 0xFF, 0x1FF, 0x3FF, 0x7FF, 0xFFF)

    /** Codificação de todos os 65536 valores possíveis de PCM de 16 bits. */
    private val tabelaDeCodificacao = ByteArray(65536) { codificarUma(it - 32768) }
    private val tabelaDeDecodificacao = ShortArray(256) { decodificarUma(it) }

    private fun codificarUma(amostra: Int): Byte {
        var pcm = amostra shr 3
        val mascara: Int
        if (pcm >= 0) {
            mascara = 0xD5
        } else {
            mascara = 0x55
            pcm = -pcm - 1
        }
        val segmento = fimDeSegmento.indexOfFirst { pcm <= it }
        if (segmento < 0) return (0x7F xor mascara).toByte()
        var valor = segmento shl 4
        valor = valor or if (segmento < 2) (pcm shr 1) and 0x0F else (pcm shr segmento) and 0x0F
        return (valor xor mascara).toByte()
    }

    private fun decodificarUma(byte: Int): Short {
        val a = byte xor 0x55
        var t = (a and 0x0F) shl 4
        val segmento = (a and 0x70) shr 4
        t = when (segmento) {
            0 -> t + 8
            1 -> t + 0x108
            else -> (t + 0x108) shl (segmento - 1)
        }
        return (if (a and 0x80 != 0) t else -t).toShort()
    }

    fun codificar(pcm: ShortArray): ByteArray = ByteArray(pcm.size) { tabelaDeCodificacao[pcm[it] + 32768] }

    fun decodificar(alaw: ByteArray): ShortArray = ShortArray(alaw.size) { tabelaDeDecodificacao[alaw[it].toInt() and 0xFF] }

    /** PCM de 16 bits little-endian (como vem do microfone) para A-law; um byte sobrando é ignorado. */
    fun codificarPcm16(pcm16: ByteArray, tamanho: Int = pcm16.size): ByteArray = ByteArray(tamanho / 2) { i ->
        val v = ((pcm16[2 * i].toInt() and 0xFF) or (pcm16[2 * i + 1].toInt() shl 8)).toShort()
        tabelaDeCodificacao[v + 32768]
    }

    /** Escala o volume de um trecho em A-law (1.0 = igual), sem estourar. */
    fun aplicarGanho(alaw: ByteArray, ganho: Double): ByteArray {
        if (abs(ganho - 1.0) <= 1e-3) return alaw
        return ByteArray(alaw.size) {
            val v = (tabelaDeDecodificacao[alaw[it].toInt() and 0xFF] * ganho).toInt()      // trunca em direção ao zero
            tabelaDeCodificacao[v.coerceIn(-32768, 32767) + 32768]
        }
    }
}
