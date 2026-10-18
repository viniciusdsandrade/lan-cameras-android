package com.lancameras.nucleo.video

import com.lancameras.nucleo.apoio.Amostras
import com.lancameras.nucleo.apoio.Empacotador
import com.lancameras.nucleo.apoio.bytes
import com.lancameras.nucleo.rtsp.PacoteRtp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DepacotadorTest {
    private fun reconstruir(pacotes: List<ByteArray>, d: Depacotador): List<UnidadeDeAcesso> =
        pacotes.flatMap { d.empurrar(PacoteRtp.de(it)!!) }

    /** Compara listas de NALs pelo conteúdo (ByteArray não tem igualdade estrutural). */
    private fun conteudo(nals: List<ByteArray>) = nals.map { it.toList() }

    // O vídeo sintético vai e volta: o que sai do depacotador tem de ser idêntico ao que entrou.

    @Test
    fun h264RemontaOsQuadrosOriginais() {
        val quadros = Amostras.quadros(h265 = false)
        val aus = reconstruir(Empacotador.h264(quadros), DepacotadorH264())
        assertEquals(quadros.size, aus.size)
        assertEquals(quadros.map(::conteudo), aus.map { conteudo(it.nals) })
        assertEquals((0 until 20).map { it * 9000L }, aus.map { it.timestamp })
        assertEquals(listOf(0, 10), aus.withIndex().filter { it.value.chave }.map { it.index })
    }

    @Test
    fun h265RemontaOsQuadrosOriginais() {
        val quadros = Amostras.quadros(h265 = true)
        val aus = reconstruir(Empacotador.h265(quadros), DepacotadorH265())
        assertEquals(quadros.size, aus.size)
        assertEquals(quadros.map(::conteudo), aus.map { conteudo(it.nals) })
        assertEquals(listOf(0, 10), aus.withIndex().filter { it.value.chave }.map { it.index })
    }

    @Test
    fun fragmentacaoMuitoPequenaTambemFunciona() {
        val quadros = Amostras.quadros(h265 = false)
        val aus = reconstruir(Empacotador.h264(quadros, maxCarga = 40), DepacotadorH264())
        assertEquals(quadros.map(::conteudo), aus.map { conteudo(it.nals) })
    }

    @Test
    fun h264StapAEmPacoteAvulso() {
        val sps = bytes(0x67, 1, 2, 3)
        val pps = bytes(0x68, 4, 5)
        val fatia = bytes(0x65, 9, 9, 9, 9)
        var stap = bytes(0x78)                                       // STAP-A, NRI 3
        for (n in listOf(sps, pps)) stap += bytes(0, n.size) + n
        val pacotes = listOf(
            Empacotador.cabecalho(0, 100, marcador = false, pt = 96) + stap,
            Empacotador.cabecalho(1, 100, marcador = true, pt = 96) + fatia,
        )
        val aus = reconstruir(pacotes, DepacotadorH264())
        assertEquals(1, aus.size)
        assertEquals(conteudo(listOf(sps, pps, fatia)), conteudo(aus[0].nals))
        assertTrue(aus[0].chave)
    }

    @Test
    fun quadroSemMarcadorFechaQuandoOTimestampMuda() {
        val a = bytes(0x41, 1, 1)
        val b = bytes(0x41, 2, 2)
        val pacotes = listOf(
            Empacotador.cabecalho(0, 1000, marcador = false, pt = 96) + a,
            Empacotador.cabecalho(1, 2000, marcador = true, pt = 96) + b,
        )
        val aus = reconstruir(pacotes, DepacotadorH264())
        assertEquals(listOf(conteudo(listOf(a)), conteudo(listOf(b))), aus.map { conteudo(it.nals) })
        assertEquals(listOf(false, false), aus.map { it.chave })
    }

    @Test
    fun fragmentoComSaltoDeSequenciaEhDescartado() {
        // início de um FU-A, salto de sequência, fim do FU-A: o NAL pela metade não pode sair
        val ind = 0x7C
        val pacotes = listOf(
            Empacotador.cabecalho(0, 1, marcador = false, pt = 96) + bytes(ind, 0x81, 1, 2),
            Empacotador.cabecalho(5, 1, marcador = true, pt = 96) + bytes(ind, 0x41, 3, 4),
        )
        assertTrue(reconstruir(pacotes, DepacotadorH264()).isEmpty())
    }

    @Test
    fun sequenciaQueDaAVoltaNaoEhSalto() {
        // 65535 -> 0 é a sequência seguinte: o FU-A tem de sair inteiro
        val ind = 0x7C
        val pacotes = listOf(
            Empacotador.cabecalho(65535, 1, marcador = false, pt = 96) + bytes(ind, 0x81, 1, 2),
            Empacotador.cabecalho(0, 1, marcador = true, pt = 96) + bytes(ind, 0x41, 3, 4),
        )
        val aus = reconstruir(pacotes, DepacotadorH264())
        assertEquals(listOf(conteudo(listOf(bytes(0x61, 1, 2, 3, 4)))), aus.map { conteudo(it.nals) })
    }

    @Test
    fun h265PrefixoEhChaveSoParaTiposDeReferencia() {
        fun nal(tipo: Int) = bytes(tipo shl 1, 1, 0xAA)
        for ((tipo, esperado) in listOf(19 to true, 20 to true, 21 to true, 16 to true, 1 to false, 0 to false)) {
            val p = Empacotador.cabecalho(0, 1, marcador = true, pt = 98) + nal(tipo)
            val aus = reconstruir(listOf(p), DepacotadorH265())
            assertEquals("tipo $tipo", esperado, aus.first().chave)
        }
    }
}
