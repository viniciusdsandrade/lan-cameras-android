package com.lancameras.android.plataforma

import com.lancameras.android.telas.mover
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class NomesTest {
    @Test
    fun nomeDaFotoNaoLevaBarraNemDoisPontos() {
        assertEquals("Portão-frente-12-30", PlataformaAndroid.nomeDeArquivo("Portão/frente:12:30"))
        val carimbo = PlataformaAndroid.carimbo(Date(0))
        assertTrue(carimbo, Regex("\\d{8}-\\d{6}").matches(carimbo))
    }

    @Test
    fun moverTrocaAOrdemSemSairDaLista() {
        assertEquals(listOf("b", "a", "c"), mover(listOf("a", "b", "c"), 1, 0))
        assertEquals(listOf("a", "c", "b"), mover(listOf("a", "b", "c"), 1, 2))
        assertEquals(listOf("a", "b", "c"), mover(listOf("a", "b", "c"), 0, -1))
        assertEquals(listOf("a", "b", "c"), mover(listOf("a", "b", "c"), 2, 3))
    }
}
