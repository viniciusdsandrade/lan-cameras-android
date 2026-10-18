package com.lancameras.android

import android.app.Application
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.lancameras.android.apoio.Depuracao
import com.lancameras.android.plataforma.CofreDoKeystore
import com.lancameras.android.plataforma.PlataformaAndroid
import com.lancameras.android.sessao.ModeloDoApp
import com.lancameras.nucleo.modelo.ArmazenamentoDeCameras
import java.io.File

/**
 * O app: o modelo vive aqui, um só, e segue o ciclo de vida do processo. Não prender as câmeras
 * quando ninguém está olhando: elas aguentam poucas sessões ao mesmo tempo, e o painel, o celular
 * e a nuvem do fabricante disputam essas mesmas sessões.
 */
class CamerasApp : Application() {
    lateinit var plataforma: PlataformaAndroid
        private set
    lateinit var modelo: ModeloDoApp
        private set

    override fun onCreate() {
        super.onCreate()
        // `adb shell setprop log.tag.Cameras DEBUG` liga o registro (nunca com credenciais)
        if (Log.isLoggable(ETIQUETA, Log.DEBUG)) Depuracao.registrador = { Log.d(ETIQUETA, it) }
        plataforma = PlataformaAndroid(this)
        // filesDir fica fora de qualquer backup (regras de extração) e só o app enxerga
        val armazenamento = ArmazenamentoDeCameras(File(filesDir, "cameras"), CofreDoKeystore())
        modelo = ModeloDoApp(armazenamento, plataforma, comecarPausado = true)
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, evento ->
                when (evento) {
                    // em segundo plano o sistema pode suspender o app a qualquer momento: solta tudo na hora
                    Lifecycle.Event.ON_STOP -> { Depuracao.registrar { "segundo plano" }; modelo.pausar() }
                    Lifecycle.Event.ON_START -> { Depuracao.registrar { "primeiro plano" }; modelo.retomar() }
                    else -> {}
                }
            },
        )
    }

    private companion object {
        const val ETIQUETA = "Cameras"
    }
}
