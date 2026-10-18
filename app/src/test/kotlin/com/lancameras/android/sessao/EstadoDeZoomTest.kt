package com.lancameras.android.sessao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EstadoDeZoomTest {
    @Test
    fun zoomFicaNosLimitesEAmpliaEmVoltaDoPonto() {
        val z = EstadoDeZoom()
        z.definir(50.0)
        assertEquals(EstadoDeZoom.MAXIMO, z.fator, 0.0)
        z.definir(0.1)
        assertEquals(EstadoDeZoom.MINIMO, z.fator, 0.0)
        assertFalse(z.zoom.value.ampliado)

        // amplia 2x em volta de um ponto 100 px à direita do centro: o ponto fica parado
        z.ampliar(2.0, 100.0, 0.0)
        assertEquals(2.0, z.fator, 1e-9)
        assertEquals(-100.0, z.zoom.value.x, 1e-9)
        assertEquals(0.0, z.zoom.value.y, 1e-9)
        assertTrue(z.zoom.value.ampliado)

        // voltar a 1x zera o deslocamento
        z.ampliar(0.5, 0.0, 0.0)
        assertEquals(1.0, z.fator, 1e-9)
        assertEquals(0.0, z.zoom.value.x, 0.0)
    }

    @Test
    fun deslocamentoNuncaDescolaDaBorda() {
        val z = EstadoDeZoom()
        z.definir(2.0)
        z.deslocar(10_000.0, -10_000.0, 400.0, 300.0)
        val (x, y) = EstadoDeZoom.deslocamentoLimitado(z.zoom.value, 400.0, 300.0)
        assertEquals(200.0, x, 0.0)                                     // (2 - 1) * 400 / 2
        assertEquals(-150.0, y, 0.0)
        z.reiniciar()
        z.deslocar(50.0, 50.0, 400.0, 300.0)
        assertEquals("sem zoom não há o que arrastar", 0.0, z.zoom.value.x, 0.0)
    }
}
