package com.lancameras.android.apoio

enum class Direcao(
    /** `x > 0` vira à ESQUERDA: o firmware das câmeras tem o pan invertido. */
    val vetorX: Double,
    val vetorY: Double,
    val nome: String,
) {
    CIMA(0.0, 1.0, "cima"),
    BAIXO(0.0, -1.0, "baixo"),
    ESQUERDA(1.0, 0.0, "esquerda"),
    DIREITA(-1.0, 0.0, "direita"),
}
