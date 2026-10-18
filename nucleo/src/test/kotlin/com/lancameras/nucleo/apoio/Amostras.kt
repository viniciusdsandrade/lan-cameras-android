package com.lancameras.nucleo.apoio

/**
 * Vídeo sintético dos testes: barras de cor geradas pelo ffmpeg (160x96, 20 quadros, um quadro
 * de referência a cada 10), sem nenhuma imagem de câmera.
 */
object Amostras {
    fun nals(h265: Boolean): List<ByteArray> {
        val nome = if (h265) "amostra-h265" else "amostra-h264"
        val dados = Amostras::class.java.getResourceAsStream("/fixtures/$nome.annexb")?.use { it.readBytes() }
            ?: error("faltou a amostra $nome")
        return separarAnnexB(dados)
    }

    /** Os NALs agrupados por quadro: parâmetros que precedem a fatia ficam com ela. */
    fun quadros(h265: Boolean): List<List<ByteArray>> {
        val saida = mutableListOf<List<ByteArray>>()
        var pendentes = mutableListOf<ByteArray>()
        for (nal in nals(h265)) {
            pendentes += nal
            val t = tipo(nal, h265)
            val ehFatia = if (h265) t <= 21 else t in 1..5
            if (ehFatia) {
                saida += pendentes
                pendentes = mutableListOf()
            }
        }
        return saida
    }

    /** Separa um fluxo Annex B em NALs, sem o prefixo de início. */
    fun separarAnnexB(d: ByteArray): List<ByteArray> {
        val inicios = mutableListOf<Int>()
        var i = 0
        while (i + 3 <= d.size) {
            if (d[i].toInt() == 0 && d[i + 1].toInt() == 0 && d[i + 2].toInt() == 1) {
                inicios += i + 3
                i += 3
            } else {
                i++
            }
        }
        return inicios.mapIndexed { k, ini ->
            var fim = if (k + 1 < inicios.size) inicios[k + 1] - 3 else d.size
            while (fim > ini && d[fim - 1].toInt() == 0) fim--
            d.copyOfRange(ini, fim)
        }
    }

    fun tipo(nal: ByteArray, h265: Boolean): Int {
        val b0 = nal[0].toInt() and 0xFF
        return if (h265) (b0 shr 1) and 0x3F else b0 and 0x1F
    }

    fun parametros(h265: Boolean): List<ByteArray> {
        val tipos = if (h265) setOf(32, 33, 34) else setOf(7, 8)
        return nals(h265).filter { tipo(it, h265) in tipos }
    }
}

/** Bytes a partir de inteiros, para escrever pacotes nos testes sem `toByte()` em cada um. */
fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

/** Espera uma condição por até [segundos], sem travar o teste para sempre. */
fun esperar(segundos: Double = 10.0, condicao: () -> Boolean): Boolean {
    val limite = System.nanoTime() + (segundos * 1e9).toLong()
    while (System.nanoTime() < limite) {
        if (condicao()) return true
        Thread.sleep(20)
    }
    return condicao()
}
