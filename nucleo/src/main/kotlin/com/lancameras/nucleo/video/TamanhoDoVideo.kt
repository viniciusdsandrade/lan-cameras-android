package com.lancameras.nucleo.video

/**
 * Largura e altura da imagem, lidas do SPS (a sintaxe de "seq_parameter_set_rbsp" das normas
 * ITU-T H.264 e H.265), já descontado o recorte. O MediaCodec é configurado com elas antes do primeiro quadro.
 */
data class TamanhoDoVideo(val largura: Int, val altura: Int) {
    companion object {
        /** Devolve null se o SPS estiver truncado ou não for do codec. */
        fun doSps(codec: CodecDeVideo, sps: ByteArray): TamanhoDoVideo? = try {
            when (codec) {
                CodecDeVideo.H264 -> if (sps.size > 1 && codec.tipo(sps) == 7) h264(LeitorDeBits(semEmulacao(sps, 1))) else null
                CodecDeVideo.H265 -> if (sps.size > 2 && codec.tipo(sps) == 33) h265(LeitorDeBits(semEmulacao(sps, 2))) else null
            }
        } catch (_: LeitorDeBits.Fim) {
            null
        }

        private val perfisComCroma = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)

        private fun h264(b: LeitorDeBits): TamanhoDoVideo? {
            val perfil = b.bits(8)
            b.pular(16)                                         // restrições e nível
            b.ue()                                              // seq_parameter_set_id
            var croma = 1
            var planosSeparados = false
            if (perfil in perfisComCroma) {
                croma = b.ue()
                if (croma == 3) planosSeparados = b.bit() == 1
                b.ue(); b.ue()                                  // profundidade de bits
                b.pular(1)
                if (b.bit() == 1) {                             // matrizes de escala
                    repeat(if (croma != 3) 8 else 12) { i ->
                        if (b.bit() == 1) pularListaDeEscala(b, if (i < 6) 16 else 64)
                    }
                }
            }
            b.ue()                                              // log2_max_frame_num_minus4
            when (b.ue()) {                                     // pic_order_cnt_type
                0 -> b.ue()
                1 -> {
                    b.pular(1)
                    b.se(); b.se()
                    repeat(b.ue()) { b.se() }
                }
            }
            b.ue()                                              // max_num_ref_frames
            b.pular(1)
            val larguraEmMb = b.ue() + 1
            val alturaEmUnidades = b.ue() + 1
            val soQuadros = b.bit()
            if (soQuadros == 0) b.pular(1)
            b.pular(1)                                          // direct_8x8_inference_flag
            var largura = larguraEmMb * 16
            var altura = (2 - soQuadros) * alturaEmUnidades * 16
            if (b.bit() == 1) {                                 // frame_cropping_flag
                val esq = b.ue(); val dir = b.ue(); val cima = b.ue(); val baixo = b.ue()
                val tipoDeCroma = if (planosSeparados) 0 else croma
                val unidadeX = if (tipoDeCroma == 0 || tipoDeCroma == 3) 1 else 2
                val unidadeY = (if (tipoDeCroma == 1) 2 else 1) * (2 - soQuadros)
                largura -= unidadeX * (esq + dir)
                altura -= unidadeY * (cima + baixo)
            }
            return if (largura > 0 && altura > 0) TamanhoDoVideo(largura, altura) else null
        }

        private fun pularListaDeEscala(b: LeitorDeBits, tamanho: Int) {
            var ultima = 8
            var proxima = 8
            repeat(tamanho) {
                if (proxima != 0) proxima = (ultima + b.se() + 256) % 256
                if (proxima != 0) ultima = proxima
            }
        }

        private fun h265(b: LeitorDeBits): TamanhoDoVideo? {
            b.pular(4)                                          // sps_video_parameter_set_id
            val subcamadas = b.bits(3)                          // sps_max_sub_layers_minus1
            b.pular(1)
            // profile_tier_level: a parte geral tem 96 bits
            b.pular(96)
            val perfilPresente = BooleanArray(subcamadas)
            val nivelPresente = BooleanArray(subcamadas)
            for (i in 0 until subcamadas) {
                perfilPresente[i] = b.bit() == 1
                nivelPresente[i] = b.bit() == 1
            }
            if (subcamadas > 0) repeat(8 - subcamadas) { b.pular(2) }
            for (i in 0 until subcamadas) {
                if (perfilPresente[i]) b.pular(88)
                if (nivelPresente[i]) b.pular(8)
            }
            b.ue()                                              // sps_seq_parameter_set_id
            val croma = b.ue()
            val planosSeparados = croma == 3 && b.bit() == 1
            var largura = b.ue()
            var altura = b.ue()
            if (b.bit() == 1) {                                 // conformance_window_flag
                val esq = b.ue(); val dir = b.ue(); val cima = b.ue(); val baixo = b.ue()
                val tipoDeCroma = if (planosSeparados) 0 else croma
                val subX = if (tipoDeCroma == 1 || tipoDeCroma == 2) 2 else 1
                val subY = if (tipoDeCroma == 1) 2 else 1
                largura -= subX * (esq + dir)
                altura -= subY * (cima + baixo)
            }
            return if (largura > 0 && altura > 0) TamanhoDoVideo(largura, altura) else null
        }

        /** Tira os bytes de prevenção de emulação (00 00 03) e o cabeçalho do NAL. */
        private fun semEmulacao(nal: ByteArray, cabecalho: Int): ByteArray {
            val saida = ByteArray(nal.size)
            var n = 0
            var zeros = 0
            for (i in cabecalho until nal.size) {
                val v = nal[i].toInt() and 0xFF
                if (zeros >= 2 && v == 3) {
                    zeros = 0
                    continue
                }
                zeros = if (v == 0) zeros + 1 else 0
                saida[n++] = nal[i]
            }
            return saida.copyOf(n)
        }
    }
}

/** Leitura bit a bit, com Exp-Golomb (ue/se). */
internal class LeitorDeBits(private val b: ByteArray) {
    class Fim : Exception("SPS truncado")

    private var pos = 0

    fun bit(): Int {
        if (pos >= b.size * 8) throw Fim()
        val v = (b[pos shr 3].toInt() shr (7 - (pos and 7))) and 1
        pos++
        return v
    }

    fun bits(n: Int): Int {
        var v = 0
        repeat(n) { v = (v shl 1) or bit() }
        return v
    }

    fun pular(n: Int) {
        pos += n
        if (pos > b.size * 8) throw Fim()
    }

    fun ue(): Int {
        var zeros = 0
        while (bit() == 0) {
            zeros++
            if (zeros > 30) throw Fim()                         // nenhum campo do SPS chega perto disso
        }
        return (1 shl zeros) - 1 + bits(zeros)
    }

    fun se(): Int {
        val k = ue()
        return if (k and 1 == 1) (k + 1) / 2 else -(k / 2)
    }
}
