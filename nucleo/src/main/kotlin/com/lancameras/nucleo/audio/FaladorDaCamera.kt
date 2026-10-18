package com.lancameras.nucleo.audio

import com.lancameras.nucleo.Redact
import com.lancameras.nucleo.dvrip.ClienteDvrip
import com.lancameras.nucleo.modelo.CameraConfig
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/**
 * Uma sessão de fala com o alto-falante de uma câmera: esvazia uma fila de pacotes G.711 no ritmo
 * em que o alto-falante toca (40 ms por pacote de 320 bytes).
 *
 * A câmera só aceita uma fala por vez, e o áudio toca em tempo real: mandar rápido demais estoura
 * o buffer dela, e devagar demais corta a voz. Por isso o ritmo.
 */
class FaladorDaCamera(
    private val camera: CameraConfig,
    private val porta: Int = ClienteDvrip.PORTA_PADRAO,
    private val aoVivo: Boolean = false,
) {
    private val trava = ReentrantLock()
    private val mudou = trava.newCondition()
    private val fila = ArrayDeque<ByteArray>()
    private var cancelado = false
    private var fimPedido = false
    private var _ativo = false
    private var _erro: String? = null
    private var reservou = false
    private val pronto = CountDownLatch(1)

    val ativo: Boolean get() = trava.withLock { _ativo }
    val erro: String? get() = trava.withLock { _erro }

    /** Abre a sessão em outra thread. Use [esperarPronto] para saber se a câmera aceitou. */
    fun iniciar() {
        trava.withLock { _ativo = true }
        thread(name = "fala-${camera.id.takeLast(4)}", isDaemon = true, priority = Thread.MAX_PRIORITY) { executar() }
    }

    /** Espera a câmera aceitar (ou recusar) a fala. Devolve true se o alto-falante foi reservado. */
    fun esperarPronto(timeout: Double = 3.0): Boolean {
        pronto.await((timeout * 1000).toLong(), TimeUnit.MILLISECONDS)
        return trava.withLock { reservou && _erro == null }
    }

    /** Acrescenta áudio G.711 à fila (em pedaços de 320 bytes). */
    fun enfileirar(alaw: ByteArray) = trava.withLock {
        val tam = ClienteDvrip.BYTES_POR_PACOTE_DE_FALA
        var i = 0
        while (i < alaw.size) {
            val p = ByteArray(tam) { ALaw.SILENCIO }
            alaw.copyInto(p, 0, i, minOf(i + tam, alaw.size))
            fila.addLast(p)
            i += tam
        }
        if (aoVivo) while (fila.size > MAXIMO_DA_FILA_AO_VIVO) fila.removeFirst()
        mudou.signalAll()
    }

    /** Termina depois de tocar o que já está na fila. */
    fun encerrar() = trava.withLock {
        fimPedido = true
        mudou.signalAll()
    }

    /** Termina agora. */
    fun cancelar() = trava.withLock {
        cancelado = true
        fila.clear()
        mudou.signalAll()
    }

    // Thread da sessão

    private fun executar() {
        try {
            ClienteDvrip.executar(camera.host, camera.usuario, camera.hash, porta) { d ->
                d.iniciarFala()
                trava.withLock { reservou = true }
                pronto.countDown()
                laco(d)
            }
        } catch (e: Exception) {
            trava.withLock { _erro = Redact.texto(e.toString()) }
        }
        trava.withLock { _ativo = false }
        pronto.countDown()
    }

    private fun proximoPacote(esperaMs: Long): ByteArray? = trava.withLock {
        if (fila.isEmpty() && !cancelado && !fimPedido) mudou.await(esperaMs, TimeUnit.MILLISECONDS)
        if (cancelado || fila.isEmpty()) null else fila.removeFirst()
    }

    /** Espera até [instante] (de `System.nanoTime`); true se cancelaram nesse meio-tempo. */
    private fun aguardar(instante: Long): Boolean {
        trava.withLock {
            while (!cancelado) {
                val falta = instante - System.nanoTime()
                if (falta <= 0) return false
                mudou.awaitNanos(falta)
            }
            return true
        }
    }

    private fun laco(d: ClienteDvrip) {
        val passo = ClienteDvrip.MILISSEGUNDOS_POR_PACOTE_DE_FALA * 1_000_000L
        var proximo = System.nanoTime() - ANTECEDENCIA_NS
        var ultimo = System.nanoTime()
        var keepalive = ultimo
        var terminouNormalmente = false
        while (!trava.withLock { cancelado }) {
            val pacote = proximoPacote(100)
            if (pacote == null) {
                val agora = System.nanoTime()
                val (fim, parar) = trava.withLock { fimPedido to cancelado }
                if (parar) break
                if (fim || agora - ultimo > OCIOSO_NS) {
                    terminouNormalmente = true
                    break
                }
                if (agora - keepalive > 15_000_000_000L) {
                    d.manterVivo()
                    keepalive = agora
                }
                d.drenar()
                continue
            }
            val agora = System.nanoTime()
            if (proximo < agora - 250_000_000L) proximo = agora - ANTECEDENCIA_NS     // ficou parado: reancora
            d.enviarFala(pacote)
            ultimo = System.nanoTime()
            proximo += passo
            if (aguardar(proximo)) break
            d.drenar()
        }
        if (terminouNormalmente) aguardar(System.nanoTime() + 350_000_000L)          // deixa o último pacote tocar
    }

    companion object {
        /** Sem nada novo na fila por este tempo, a sessão termina sozinha. */
        private const val OCIOSO_NS = 2_000_000_000L
        /** No modo ao vivo a fila é curta (~480 ms): acima disso descarta o mais velho. */
        const val MAXIMO_DA_FILA_AO_VIVO = 12
        /** Pacotes à frente do relógio, contra a variação da rede. */
        private const val ANTECEDENCIA_NS = 120_000_000L
    }
}
