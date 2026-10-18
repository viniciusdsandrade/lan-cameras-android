package com.lancameras.nucleo.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * O caminho do texto falado até o alto-falante da câmera, menos a síntese em si (no app, o
 * `TextToSpeech` do Android grava um WAV): limpar o texto, ler o WAV, levar a 8 kHz mono, subir
 * o volume e codificar em A-law.
 */
object Voz {
    const val MAXIMO_DE_CARACTERES = 300

    class Falha(mensagem: String) : Exception(mensagem)

    /** Espaços juntados e no máximo [MAXIMO_DE_CARACTERES]; vazio é recusado. */
    fun limpar(texto: String): String {
        val limpo = texto.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ").take(MAXIMO_DE_CARACTERES)
        if (limpo.isEmpty()) throw Falha("escreva o texto que a câmera deve falar")
        return limpo
    }

    /** Um WAV PCM de 16 bits (qualquer taxa, qualquer número de canais) virado A-law de 8 kHz. */
    fun alawDoWav(wav: ByteArray): ByteArray {
        val (pcm, taxa) = lerWav(wav) ?: throw Falha("não consegui gerar a voz (áudio em formato inesperado)")
        if (pcm.isEmpty()) throw Falha("não consegui gerar a voz (áudio vazio)")
        return ALaw.codificar(normalizar(reamostrar(pcm, taxa, Som.TAXA.toInt())))
    }

    /** Amostras (misturadas em mono) e taxa de um WAV PCM de 16 bits; null se for outro formato. */
    fun lerWav(b: ByteArray): Pair<ShortArray, Int>? {
        if (b.size < 12 || String(b, 0, 4, Charsets.US_ASCII) != "RIFF" || String(b, 8, 4, Charsets.US_ASCII) != "WAVE") return null
        val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        var canais = 0
        var taxa = 0
        var bits = 0
        var i = 12
        while (i + 8 <= b.size) {
            val nome = String(b, i, 4, Charsets.US_ASCII)
            val tam = buf.getInt(i + 4).toLong() and 0xFFFFFFFFL
            val inicio = i + 8
            val fim = minOf(b.size.toLong(), inicio + tam).toInt()
            when (nome) {
                "fmt " -> {
                    if (fim - inicio < 16 || buf.getShort(inicio).toInt() != 1) return null      // só PCM
                    canais = buf.getShort(inicio + 2).toInt()
                    taxa = buf.getInt(inicio + 4)
                    bits = buf.getShort(inicio + 14).toInt()
                }
                "data" -> {
                    if (canais <= 0 || taxa <= 0 || bits != 16) return null
                    val quadros = (fim - inicio) / (2 * canais)
                    val saida = ShortArray(quadros) { q ->
                        var soma = 0
                        for (c in 0 until canais) soma += buf.getShort(inicio + 2 * (q * canais + c))
                        (soma / canais).toShort()
                    }
                    return saida to taxa
                }
            }
            i = inicio + ((tam + 1) and 1L.inv()).toInt()                              // pedaços têm tamanho par
            if (i < 0) return null
        }
        return null
    }

    /** Muda a taxa: para baixo tira a média de cada intervalo (evita chiado), para cima interpola. */
    fun reamostrar(pcm: ShortArray, de: Int, para: Int): ShortArray {
        if (de == para || pcm.isEmpty()) return pcm
        val n = (pcm.size.toLong() * para / de).toInt().coerceAtLeast(1)
        return if (de > para) {
            ShortArray(n) { k ->
                val a = (k.toLong() * de / para).toInt()
                val z = maxOf(a + 1, minOf(pcm.size, ((k + 1).toLong() * de / para).toInt()))
                var soma = 0L
                for (j in a until z) soma += pcm[j]
                (soma / (z - a)).toInt().toShort()
            }
        } else {
            ShortArray(n) { k ->
                val pos = k.toDouble() * de / para
                val a = pos.toInt().coerceAtMost(pcm.size - 1)
                val z = minOf(a + 1, pcm.size - 1)
                val f = pos - a
                (pcm[a] * (1 - f) + pcm[z] * f).roundToInt().toShort()
            }
        }
    }

    /** Sobe o volume até o pico chegar perto do máximo (no máximo 8 vezes), para a fala ficar audível. */
    fun normalizar(pcm: ShortArray): ShortArray {
        val pico = pcm.maxOfOrNull { abs(it.toInt()) } ?: 0
        if (pico == 0) return pcm
        val ganho = minOf(8.0, 0.9 * 32767.0 / pico)
        return ShortArray(pcm.size) { (pcm[it] * ganho).roundToInt().coerceIn(-32768, 32767).toShort() }
    }
}
