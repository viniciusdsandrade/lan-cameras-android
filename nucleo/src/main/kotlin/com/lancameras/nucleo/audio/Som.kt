package com.lancameras.nucleo.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/** Sons prontos para tocar no alto-falante das câmeras: G.711 A-law, 8 kHz, mono. */
enum class Som(val rotulo: String) {
    CAMPAINHA("Campainha"),
    BIP("Bip"),
    ALARME("Alarme"),
    SIRENE("Sirene");

    /** O som em A-law, gerado uma vez e guardado. */
    val alaw: ByteArray by lazy { paraAlaw(gerar()) }

    private fun gerar(): DoubleArray = when (this) {
        BIP -> juntar(List(3) { listOf(tom(1000.0, 0.22), silencio(0.16)) }.flatten())
        // "ding-dong": mi e dó, cada um com decaimento exponencial
        CAMPAINHA -> juntar(listOf(tom(659.3, 0.55, decaimento = 3.0), tom(523.3, 0.95, decaimento = 2.6)))
        ALARME -> juntar(List(6) { listOf(tom(880.0, 0.3), tom(660.0, 0.3)) }.flatten())
        SIRENE -> varredura(6.0)
    }

    companion object {
        const val TAXA = 8000.0

        private fun juntar(partes: List<DoubleArray>): DoubleArray {
            val saida = DoubleArray(partes.sumOf { it.size })
            var i = 0
            for (p in partes) { p.copyInto(saida, i); i += p.size }
            return saida
        }

        private fun tom(freq: Double, duracao: Double, amplitude: Double = 0.8, decaimento: Double = 0.0, rampa: Double = 0.01): DoubleArray {
            val n = (TAXA * duracao).toInt()
            val r = maxOf(1, (TAXA * rampa).toInt())
            return DoubleArray(n) { i ->
                val t = i / TAXA
                var envelope = if (decaimento > 0) exp(-decaimento * t) else 1.0
                if (i < r) envelope *= i.toDouble() / r else if (i > n - r) envelope *= (n - i).toDouble() / r
                amplitude * envelope * sin(2 * PI * freq * t)
            }
        }

        private fun silencio(duracao: Double) = DoubleArray((TAXA * duracao).toInt())

        /** Varredura de 700 a 1500 Hz com período de 1,2 s. */
        private fun varredura(duracao: Double): DoubleArray {
            val n = (TAXA * duracao).toInt()
            var fase = 0.0
            return DoubleArray(n) { i ->
                val t = i / TAXA
                val triangular = abs(((t / 1.2) % 1.0) * 2 - 1)
                fase += 2 * PI * (700 + 800 * triangular) / TAXA
                val envelope = minOf(1.0, t / 0.05, (duracao - t) / 0.05)
                0.8 * envelope * sin(fase)
            }
        }

        private fun paraAlaw(amostras: DoubleArray): ByteArray =
            ALaw.codificar(ShortArray(amostras.size) { (32767 * amostras[it]).toInt().coerceIn(-32767, 32767).toShort() })
    }
}
