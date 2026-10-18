package com.lancameras.android.video

import com.lancameras.android.sessao.Zoom
import org.junit.Assert.assertEquals
import org.junit.Test

class MatrizDaLenteTest {
    private fun ponto(m: MatrizDaLente, x: Float, y: Float) = (m.escalaX * x + m.deslocamentoX) to (m.escalaY * y + m.deslocamentoY)

    @Test
    fun aLenteMovelEhAMetadeDeBaixoDoQuadro() {
        val m = MatrizDaLente.calcular(LenteExibida.MOVEL, null, 400f, 225f)
        // o meio do quadro (começo da metade de baixo) vai para o topo da vista; o fim, para o pé
        assertEquals(0f to 0f, ponto(m, 0f, 112.5f))
        assertEquals(400f to 225f, ponto(m, 400f, 225f))
    }

    @Test
    fun aLenteFixaEhAMetadeDeCima() {
        val m = MatrizDaLente.calcular(LenteExibida.FIXA, null, 400f, 225f)
        assertEquals(0f to 0f, ponto(m, 0f, 0f))
        assertEquals(400f to 225f, ponto(m, 400f, 112.5f))
    }

    @Test
    fun oQuadroInteiroNaoMuda() {
        assertEquals(MatrizDaLente(1f, 1f, 0f, 0f), MatrizDaLente.calcular(LenteExibida.INTEIRA, Zoom(), 400f, 225f))
    }

    @Test
    fun oZoomAmpliaEmVoltaDoCentroEDeslocaDentroDoLimite() {
        val m = MatrizDaLente.calcular(LenteExibida.INTEIRA, Zoom(2.0, 50.0, 0.0), 400f, 300f)
        // o centro da vista continua no centro, deslocado 50 px
        assertEquals(250f to 150f, ponto(m, 200f, 150f))
        assertEquals(2f, m.escalaX)
        // um deslocamento maior que a borda é limitado: (2 - 1) * 400 / 2 = 200
        val limitado = MatrizDaLente.calcular(LenteExibida.INTEIRA, Zoom(2.0, 999.0, 0.0), 400f, 300f)
        assertEquals(400f to 150f, ponto(limitado, 200f, 150f))
    }

    @Test
    fun zoomNaLenteMovelMantemORecorteCerto() {
        val m = MatrizDaLente.calcular(LenteExibida.MOVEL, Zoom(2.0, 0.0, 0.0), 400f, 225f)
        // o centro da lente móvel (3/4 do quadro) fica no centro da vista
        val (x, y) = ponto(m, 200f, 168.75f)
        assertEquals(200f, x, 1e-3f)
        assertEquals(112.5f, y, 1e-3f)
    }
}
