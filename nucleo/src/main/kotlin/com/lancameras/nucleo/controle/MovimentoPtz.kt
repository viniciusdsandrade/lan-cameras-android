package com.lancameras.nucleo.controle

import com.lancameras.nucleo.modelo.CameraConfig
import com.lancameras.nucleo.onvif.ClienteOnvif
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Movimento de uma câmera enquanto o usuário segura uma seta.
 *
 * - Reenvia o `ContinuousMove` a cada 0,3 s: é o que mantém o movimento vivo.
 * - Cada comando leva `Timeout PT1S`: se o app travar, a PRÓPRIA câmera para em 1 s.
 * - Ao soltar manda `Stop` duas vezes (agora e 0,2 s depois): se a primeira se perder, a segunda cobre.
 */
class MovimentoPtz(camera: CameraConfig, portaOnvif: Int = 8899, private val aoFalhar: ((String) -> Unit)? = null) {
    private val onvif = ClienteOnvif(camera.host, portaOnvif, camera.usuario, camera.hash)
    private val fila = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "movimento-ptz").apply { isDaemon = true } }

    // só a thread da fila mexe nestes dois
    private var tarefa: ScheduledFuture<*>? = null
    private var vetor = 0.0 to 0.0

    @Volatile var emMovimento = false
        private set

    /** Velocidades em [-1, 1]. `x > 0` vira à esquerda (o pan vem invertido no firmware). */
    fun iniciar(x: Double, y: Double) {
        emMovimento = true
        fila.execute {
            tarefa?.cancel(false)
            vetor = x to y
            enviarMover()
            if (!emMovimento) return@execute
            tarefa = fila.scheduleAtFixedRate({ enviarMover() }, REENVIO_MS, REENVIO_MS, TimeUnit.MILLISECONDS)
        }
    }

    fun parar() {
        emMovimento = false
        fila.execute {
            tarefa?.cancel(false)
            tarefa = null
            runCatching { onvif.parar() }
            fila.schedule({ runCatching { onvif.parar() } }, 200, TimeUnit.MILLISECONDS)
        }
    }

    /** Solta a thread; o segundo Stop de um [parar] recente ainda sai. */
    fun encerrar() = fila.shutdown()

    private fun enviarMover() {
        if (!emMovimento) return
        try {
            onvif.mover(vetor.first, vetor.second, timeout = 1.0)
        } catch (e: Exception) {
            emMovimento = false
            tarefa?.cancel(false)
            tarefa = null
            aoFalhar?.invoke(e.toString())
        }
    }

    companion object {
        const val REENVIO_MS = 300L

        /** Manda parar as câmeras indicadas, todas ao mesmo tempo. Devolve os erros, se houver. */
        fun pararTodas(cameras: List<CameraConfig>, portaOnvif: Int = 8899): List<String> {
            val erros = Collections.synchronizedList(mutableListOf<String>())
            cameras.map { c ->
                thread(name = "parar-${c.id.takeLast(4)}") {
                    try {
                        ClienteOnvif(c.host, portaOnvif, c.usuario, c.hash).parar()
                    } catch (e: Exception) {
                        erros += "${c.nome}: $e"
                    }
                }
            }.forEach { it.join() }
            return erros.toList()
        }
    }
}
