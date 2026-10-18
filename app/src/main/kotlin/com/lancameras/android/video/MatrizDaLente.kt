package com.lancameras.android.video

import com.lancameras.android.sessao.EstadoDeZoom
import com.lancameras.android.sessao.Zoom

/**
 * Qual parte do quadro a tela mostra. As câmeras de duas lentes mandam as duas vistas empilhadas:
 * a móvel (a única que gira) embaixo, a fixa em cima.
 */
enum class LenteExibida { MOVEL, FIXA, INTEIRA }

/**
 * A transformação que leva o quadro inteiro (esticado para o tamanho da vista) à parte que a tela
 * mostra, com o zoom por cima. Só números, para poder ser testada sem Android; a vista a passa para
 * uma `Matrix`.
 */
data class MatrizDaLente(val escalaX: Float, val escalaY: Float, val deslocamentoX: Float, val deslocamentoY: Float) {
    companion object {
        fun calcular(lente: LenteExibida, zoom: Zoom?, largura: Float, altura: Float): MatrizDaLente {
            // o quadro traz as duas lentes empilhadas: a camada tem a altura do quadro inteiro e é
            // deslocada para a metade certa ficar na janela
            var escalaY = if (lente == LenteExibida.INTEIRA) 1f else 2f
            var escalaX = 1f
            var dx = 0f
            var dy = if (lente == LenteExibida.MOVEL) -altura else 0f
            if (zoom != null && zoom.ampliado) {
                val f = zoom.fator.toFloat()
                val (x, y) = EstadoDeZoom.deslocamentoLimitado(zoom, largura.toDouble(), altura.toDouble())
                // ampliar em volta do centro da vista e depois deslocar
                escalaX *= f
                escalaY *= f
                dx = (dx - largura / 2) * f + largura / 2 + x.toFloat()
                dy = (dy - altura / 2) * f + altura / 2 + y.toFloat()
            }
            return MatrizDaLente(escalaX, escalaY, dx, dy)
        }
    }
}
