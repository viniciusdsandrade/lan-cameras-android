package com.lancameras.nucleo.audio

import com.lancameras.nucleo.controle.ErroDeControle
import com.lancameras.nucleo.dvrip.ClienteDvrip
import com.lancameras.nucleo.modelo.CameraConfig

/**
 * Quem fala pela câmera: garante UMA fala por vez (o alto-falante só aceita uma sessão) e cuida do
 * volume. Tocar um som ou começar outra fala cancela a anterior.
 */
class GerenteDeFala(private val camera: CameraConfig, private val porta: Int = ClienteDvrip.PORTA_PADRAO) {
    private val trava = Any()
    private var atual: FaladorDaCamera? = null

    val falando: Boolean get() = synchronized(trava) { atual?.ativo == true }

    private fun novoFalador(aoVivo: Boolean): FaladorDaCamera = synchronized(trava) {
        val velho = atual
        if (velho != null && velho.ativo) {
            velho.cancelar()
            val limite = System.nanoTime() + 1_500_000_000L                  // a câmera só aceita uma fala por vez
            while (velho.ativo && System.nanoTime() < limite) Thread.sleep(20)
        }
        FaladorDaCamera(camera, porta, aoVivo).also { atual = it }
    }

    /**
     * Toca um áudio pronto (som ou texto falado) e volta assim que a câmera aceitar. O áudio segue
     * tocando em segundo plano; [parar] o interrompe. Bloqueia por poucos segundos.
     */
    fun tocar(alaw: ByteArray, ganho: Double) {
        val f = novoFalador(aoVivo = false)
        f.enfileirar(ALaw.aplicarGanho(alaw, ganho))
        f.encerrar()                                                         // termina quando a fila esvaziar
        f.iniciar()
        if (!f.esperarPronto()) throw ErroDeControle(f.erro ?: "a câmera não aceitou o som agora (ocupada?)")
    }

    /** Reserva o alto-falante para fala ao vivo; depois use [enviarAoVivo]. */
    fun iniciarAoVivo() {
        val f = novoFalador(aoVivo = true)
        f.iniciar()
        if (!f.esperarPronto()) throw ErroDeControle(f.erro ?: "a câmera não aceitou a fala agora (ocupada?)")
    }

    /** PCM de 16 bits, 8 kHz, mono, como sai do microfone. Pode ser chamado de qualquer thread. */
    fun enviarAoVivo(pcm16: ByteArray, ganho: Double, tamanho: Int = pcm16.size) {
        val f = synchronized(trava) { atual } ?: return
        if (!f.ativo) return
        f.enfileirar(ALaw.aplicarGanho(ALaw.codificarPcm16(pcm16, tamanho), ganho))
    }

    /** Fim da fala ao vivo: toca o que já está na fila e libera o alto-falante. */
    fun pararAoVivo() {
        synchronized(trava) { atual }?.encerrar()
    }

    /** Corta qualquer som ou fala agora. */
    fun parar() {
        synchronized(trava) { atual }?.cancelar()
    }
}
