package com.lancameras.android.video

import com.lancameras.nucleo.video.CodecDeVideo
import com.lancameras.nucleo.video.Decodificador
import com.lancameras.nucleo.video.ParametrosDeVideo
import com.lancameras.nucleo.video.QuadroDeVideo
import com.lancameras.nucleo.video.TamanhoDoVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DistribuidorDeVideoTest {
    private class SaidaFalsa : Decodificador {
        var parametros: ParametrosDeVideo? = null
        val quadros = mutableListOf<QuadroDeVideo>()
        var encerrada = false
        override fun configurar(parametros: ParametrosDeVideo) { this.parametros = parametros }
        override fun decodificar(quadro: QuadroDeVideo): Boolean { quadros += quadro; return true }
        override fun encerrar() { encerrada = true }
    }

    // um SPS de H.264 de 1280x720 (vídeo sintético), para o tamanho ser lido
    private val parametros = ParametrosDeVideo(
        CodecDeVideo.H264,
        null,
        ("6742c01fd9005005" + "bb01100000030010" + "0000030020f18324" + "80").chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
        byteArrayOf(0x68, 0xEE.toByte(), 0x3C, 0x80.toByte()),
    )

    private fun quadro(n: Int, chave: Boolean) = QuadroDeVideo(byteArrayOf(0, 0, 0, 1, n.toByte()), n * 40_000L, chave)

    @Test
    fun umaTelaQueChegaDepoisRecebeOsParametrosEOsQuadrosDesdeOUltimoDeReferencia() {
        val d = DistribuidorDeVideo()
        val tamanhos = mutableListOf<TamanhoDoVideo>()
        val sessao = d.novaSessao { tamanhos += it }
        sessao.configurar(parametros)
        for (n in 0 until 5) sessao.decodificar(quadro(n, chave = n == 0 || n == 3))
        assertEquals(5, tamanhos.size)
        assertEquals(TamanhoDoVideo(1280, 720), tamanhos.last())

        val tela = SaidaFalsa()
        val saida = d.ligar(tela)
        assertEquals(parametros, tela.parametros)
        assertEquals("do quadro de referência 3 em diante", listOf(3L * 40_000, 4L * 40_000), tela.quadros.map { it.tempoUs })
        assertTrue(tela.quadros.first().chave)

        sessao.decodificar(quadro(5, chave = false))
        assertEquals(3, tela.quadros.size)
        saida.desligar()
        assertTrue(tela.encerrada)
        sessao.decodificar(quadro(6, chave = false))
        assertEquals("desligada, não recebe mais", 3, tela.quadros.size)
        assertEquals(0, d.saidasLigadas)
    }

    @Test
    fun aTelaSobreviveAReconexaoDoFluxo() {
        val d = DistribuidorDeVideo()
        val tela = SaidaFalsa()
        d.ligar(tela)
        assertNull("sem fluxo ainda, nada a configurar", tela.parametros)

        val primeira = d.novaSessao {}
        primeira.configurar(parametros)
        primeira.decodificar(quadro(0, chave = true))
        primeira.encerrar()
        val segunda = d.novaSessao {}
        segunda.configurar(parametros)
        segunda.decodificar(quadro(10, chave = true))
        assertEquals(listOf(0L, 10L * 40_000), tela.quadros.map { it.tempoUs })
        assertEquals(1, d.saidasLigadas)
    }

    @Test
    fun duasTelasRecebemOMesmoQuadro() {
        val d = DistribuidorDeVideo()
        val a = SaidaFalsa()
        val b = SaidaFalsa()
        d.ligar(a)
        d.ligar(b)
        val s = d.novaSessao {}
        s.configurar(parametros)
        s.decodificar(quadro(0, chave = true))
        assertEquals(1, a.quadros.size)
        assertEquals(1, b.quadros.size)
        assertTrue(a.quadros[0].dados === b.quadros[0].dados)
    }
}
