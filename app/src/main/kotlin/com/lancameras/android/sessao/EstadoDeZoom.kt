package com.lancameras.android.sessao

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Zoom digital (as câmeras não têm zoom óptico) e deslocamento, em pixels a partir do centro. */
data class Zoom(val fator: Double = 1.0, val x: Double = 0.0, val y: Double = 0.0) {
    val ampliado: Boolean get() = fator > 1.01
}

/** Compartilhado entre a tela da lente e os controles ao lado. */
class EstadoDeZoom {
    private val _zoom = MutableStateFlow(Zoom())
    val zoom: StateFlow<Zoom> = _zoom

    val fator: Double get() = _zoom.value.fator

    fun definir(novo: Double) {
        val f = novo.coerceIn(MINIMO, MAXIMO)
        _zoom.value = if (f <= MINIMO) Zoom() else _zoom.value.copy(fator = f)
    }

    fun reiniciar() {
        _zoom.value = Zoom()
    }

    /** Muda o zoom mantendo parado o ponto ([px], [py]) (medido a partir do centro da tela). */
    fun ampliar(por: Double, px: Double, py: Double) {
        val antes = _zoom.value
        val f = (antes.fator * por).coerceIn(MINIMO, MAXIMO)
        if (f <= MINIMO) {
            _zoom.value = Zoom()
            return
        }
        val razao = f / antes.fator
        _zoom.value = Zoom(f, px - (px - antes.x) * razao, py - (py - antes.y) * razao)
    }

    /** Arrasta a imagem ampliada; o que passa da borda se perde. */
    fun deslocar(dx: Double, dy: Double, largura: Double, altura: Double) {
        val z = _zoom.value
        if (!z.ampliado) return
        val (x, y) = deslocamentoLimitado(z, largura, altura)
        _zoom.value = z.copy(x = x + dx, y = y + dy)
    }

    companion object {
        const val MINIMO = 1.0
        const val MAXIMO = 5.0

        /** O deslocamento que cabe numa tela de [largura] x [altura] (a imagem nunca descola da borda). */
        fun deslocamentoLimitado(z: Zoom, largura: Double, altura: Double): Pair<Double, Double> {
            val limiteX = (z.fator - 1) * largura / 2
            val limiteY = (z.fator - 1) * altura / 2
            return z.x.coerceIn(-limiteX, limiteX) to z.y.coerceIn(-limiteY, limiteY)
        }
    }
}
