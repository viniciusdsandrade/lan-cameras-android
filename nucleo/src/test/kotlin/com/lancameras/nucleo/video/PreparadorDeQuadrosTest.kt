package com.lancameras.nucleo.video

import com.lancameras.nucleo.apoio.Amostras
import com.lancameras.nucleo.apoio.Empacotador
import com.lancameras.nucleo.apoio.bytes
import com.lancameras.nucleo.apoio.hex
import com.lancameras.nucleo.rtsp.PacoteRtp
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreparadorDeQuadrosTest {
    private class Resultado(val quadros: List<QuadroDeVideo>, val preparador: PreparadorDeQuadros)

    private fun preparar(h265: Boolean, descartarNoInicio: Int = 0, semParametros: Boolean = false, semear: Boolean = false): Resultado {
        var quadros = Amostras.quadros(h265)
        if (semParametros) {
            val params = if (h265) setOf(32, 33, 34) else setOf(7, 8)
            quadros = quadros.map { q -> q.filter { Amostras.tipo(it, h265) !in params } }
        }
        quadros = quadros.drop(descartarNoInicio)
        val pacotes = if (h265) Empacotador.h265(quadros) else Empacotador.h264(quadros)
        val codec = if (h265) CodecDeVideo.H265 else CodecDeVideo.H264
        val preparador = PreparadorDeQuadros(codec)
        if (semear) preparador.semear(Amostras.parametros(h265))
        val depacotador = Depacotador.para(codec)
        val saida = pacotes.flatMap { depacotador.empurrar(PacoteRtp.de(it)!!) }.mapNotNull { preparador.preparar(it) }
        return Resultado(saida, preparador)
    }

    @Test
    fun todosOsQuadrosSaemEmAnnexBComOsParametrosDoFluxo() {
        for (h265 in listOf(false, true)) {
            val r = preparar(h265)
            assertEquals(20, r.quadros.size)
            assertTrue(r.quadros.all { it.dados.take(4) == listOf<Byte>(0, 0, 0, 1) })
            assertEquals(listOf(0, 10), r.quadros.withIndex().filter { it.value.chave }.map { it.index })
            assertEquals(1, r.preparador.versao)
            assertEquals(TamanhoDoVideo(160, 96), r.preparador.parametros!!.tamanho)
            // 25 quadros por segundo: 9000 unidades de 90 kHz entre eles, 100 ms
            assertEquals((0 until 20).map { it * 100_000L }, r.quadros.map { it.tempoUs })
        }
    }

    @Test
    fun soComecaNoQuadroDeReferencia() {
        // Sem o primeiro quadro (que traz IDR e parâmetros), nada sai até o próximo IDR (quadro 10).
        for (h265 in listOf(false, true)) {
            val r = preparar(h265, descartarNoInicio = 1)
            assertEquals(if (h265) "H.265" else "H.264", 10, r.quadros.size)
            assertTrue(r.quadros.first().chave)
            assertTrue(r.preparador.descartados > 0)
        }
    }

    @Test
    fun usaOsParametrosDoSdpQuandoOVideoNaoOsRepete() {
        for (h265 in listOf(false, true)) {
            val r = preparar(h265, semParametros = true, semear = true)
            assertEquals(if (h265) "H.265" else "H.264", 20, r.quadros.size)
        }
    }

    @Test
    fun semParametrosNemSdpNaoSaiNada() {
        val r = preparar(h265 = false, semParametros = true)
        assertEquals(0, r.quadros.size)
        assertNull(r.preparador.parametros)
    }

    @Test
    fun parametrosRepetidosNaoReconfiguramMasNovosSim() {
        val p = PreparadorDeQuadros(CodecDeVideo.H264)
        val sps = Amostras.parametros(false)
        p.semear(sps)
        p.semear(sps)
        assertEquals(1, p.versao)
        val outroSps = sps.first { Amostras.tipo(it, false) == 7 }.copyOf().also { it[it.size - 1] = (it.last() + 1).toByte() }
        // o mesmo quadro de referência de sempre, agora com um SPS diferente na frente
        val quadro = Amostras.quadros(false).first().filter { Amostras.tipo(it, false) != 7 }
        val q = p.preparar(UnidadeDeAcesso(listOf(outroSps) + quadro, 0, chave = true))
        assertEquals(2, p.versao)
        assertTrue(q != null && q.chave)
    }

    @Test
    fun csdDoMediaCodec() {
        val h264 = PreparadorDeQuadros(CodecDeVideo.H264).apply { semear(Amostras.parametros(false)) }.parametros!!
        assertEquals(2, h264.csd.size)
        assertEquals(listOf(7), Amostras.separarAnnexB(h264.csd[0]).map { Amostras.tipo(it, false) })
        assertEquals(listOf(8), Amostras.separarAnnexB(h264.csd[1]).map { Amostras.tipo(it, false) })
        val h265 = PreparadorDeQuadros(CodecDeVideo.H265).apply { semear(Amostras.parametros(true)) }.parametros!!
        assertEquals(1, h265.csd.size)
        assertEquals(listOf(32, 33, 34), Amostras.separarAnnexB(h265.csd[0]).map { Amostras.tipo(it, true) })
    }

    @Test
    fun tempoContinuaQuandoOTimestampDoRtpDaAVolta() {
        val p = PreparadorDeQuadros(CodecDeVideo.H264).apply { semear(Amostras.parametros(false)) }
        val idr = Amostras.quadros(false).first().filter { Amostras.tipo(it, false) == 5 }
        val fatia = bytes(0x41, 0x9A, 0x00)
        val t0 = 0x100000000L - 9000                                 // um quadro antes de dar a volta
        val tempos = listOf(
            p.preparar(UnidadeDeAcesso(idr, t0, chave = true))!!.tempoUs,
            p.preparar(UnidadeDeAcesso(listOf(fatia), 0, chave = false))!!.tempoUs,
            p.preparar(UnidadeDeAcesso(listOf(fatia), 9000, chave = false))!!.tempoUs,
        )
        assertEquals(listOf(0L, 100_000L, 200_000L), tempos)
    }

    @Test
    fun annexBPoeOPrefixoEmCadaNal() {
        assertArrayEquals(bytes(0, 0, 0, 1, 0x67, 1, 0, 0, 0, 1, 0x68), annexB(listOf(bytes(0x67, 1), bytes(0x68))))
    }

    // Tamanho lido do SPS. Os SPS abaixo são de vídeos de barras de cor gerados pelo ffmpeg
    // (libx264 e libx265, yuv420p): 1080 não é múltiplo de 16, o que exige o recorte.

    @Test
    fun tamanhoDoSpsH264ComRecorte() {
        val alto = hex("67640028acd94078" + "0227e5c044000003" + "000400000300083c" + "60c658")             // High, 1920x1080
        assertEquals(TamanhoDoVideo(1920, 1080), TamanhoDoVideo.doSps(CodecDeVideo.H264, alto))
        val basico = hex("6742c01fd9005005" + "bb01100000030010" + "0000030020f18324" + "80")               // Baseline, 1280x720
        assertEquals(TamanhoDoVideo(1280, 720), TamanhoDoVideo.doSps(CodecDeVideo.H264, basico))
    }

    @Test
    fun tamanhoDoSpsH265() {
        val fullHd = hex("4201010160000003" + "0090000003000003" + "0078a003c08010e5" + "96566924caf01680" + "8000000300800000" + "030084")
        assertEquals(TamanhoDoVideo(1920, 1080), TamanhoDoVideo.doSps(CodecDeVideo.H265, fullHd))
        val hd = hex("4201010160000003" + "0090000003000003" + "005da00280802d16" + "5959a4932bc05a02" + "0000030002000003" + "000210")
        assertEquals(TamanhoDoVideo(1280, 720), TamanhoDoVideo.doSps(CodecDeVideo.H265, hd))
    }

    @Test
    fun spsTruncadoOuDeOutroTipoNaoDaTamanho() {
        val alto = hex("67640028acd94078" + "0227e5c044000003" + "000400000300083c" + "60c658")
        assertNull(TamanhoDoVideo.doSps(CodecDeVideo.H264, alto.copyOf(6)))
        assertNull(TamanhoDoVideo.doSps(CodecDeVideo.H264, bytes(0x68, 0xEE, 0x3C, 0x80)))  // um PPS
        assertNull(TamanhoDoVideo.doSps(CodecDeVideo.H265, alto))
    }
}
