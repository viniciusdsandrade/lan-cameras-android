package com.lancameras.android.apoio

import com.lancameras.nucleo.Redact

/**
 * Registro para depurar o ciclo de vida e as sessões. Nunca leva credenciais (tudo passa por
 * [Redact]). No app vai para o logcat com a etiqueta "Cameras", só quando ela está ligada
 * (`adb shell setprop log.tag.Cameras DEBUG`); nos testes, para onde o teste quiser.
 */
object Depuracao {
    /** Quem escreve; null desliga. */
    @Volatile var registrador: ((String) -> Unit)? = null

    fun registrar(texto: () -> String) {
        val r = registrador ?: return
        r(Redact.texto(texto()))
    }
}
