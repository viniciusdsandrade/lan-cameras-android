package com.lancameras.android.apoio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** As preferências do usuário (nada de segredo aqui). */
interface Preferencias {
    val qualidadeHD: StateFlow<Boolean>
    val mostrarLenteFixa: StateFlow<Boolean>
    /** Velocidade do movimento, em [0, 1]. */
    val velocidade: StateFlow<Double>
    /** Volume no alto-falante da câmera, de 5 a 100. */
    val volume: StateFlow<Double>

    fun definirQualidadeHD(v: Boolean)
    fun definirMostrarLenteFixa(v: Boolean)
    fun definirVelocidade(v: Double)
    fun definirVolume(v: Double)
}

/** Preferências que vivem só em memória (testes). */
open class PreferenciasEmMemoria : Preferencias {
    override val qualidadeHD = MutableStateFlow(true)
    override val mostrarLenteFixa = MutableStateFlow(false)
    override val velocidade = MutableStateFlow(0.4)
    override val volume = MutableStateFlow(30.0)

    override fun definirQualidadeHD(v: Boolean) { qualidadeHD.value = v }
    override fun definirMostrarLenteFixa(v: Boolean) { mostrarLenteFixa.value = v }
    override fun definirVelocidade(v: Double) { velocidade.value = v.coerceIn(0.0, 1.0) }
    override fun definirVolume(v: Double) { volume.value = v.coerceIn(5.0, 100.0) }
}
