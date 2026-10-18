package com.lancameras.android.apoio

import java.util.concurrent.atomic.AtomicLong

/** Uma mensagem curta na tela (some sozinha). */
data class Aviso(val texto: String, val tipo: Tipo, val id: Long = proximo.incrementAndGet()) {
    enum class Tipo { INFO, OK, ERRO }

    private companion object {
        val proximo = AtomicLong()
    }
}
