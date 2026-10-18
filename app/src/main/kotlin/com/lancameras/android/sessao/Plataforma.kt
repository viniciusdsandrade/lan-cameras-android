package com.lancameras.android.sessao

import com.lancameras.android.apoio.Preferencias
import com.lancameras.nucleo.rtsp.PacoteRtp

/** Toca no aparelho o áudio que a câmera manda (RTP, G.711 A-law, 8 kHz). */
interface Escuta {
    fun iniciar()
    fun empurrar(pacote: PacoteRtp)
    fun parar()
}

/** Capta o microfone e entrega PCM de 16 bits, 8 kHz, mono, little-endian, em pedaços. */
interface Microfone {
    fun iniciar()
    fun parar()
    /** Volume captado agora, de 0 a 1 (para um medidor na tela). */
    val nivel: Float
}

class FotoGuardada(
    /** Onde procurar a foto, para o aviso. */
    val onde: String,
)

class FalhaDaPlataforma(mensagem: String) : Exception(mensagem)

/**
 * O que a camada de sessão precisa do aparelho e não é protocolo: som, microfone, voz, fotos,
 * permissões e preferências. O app dá a versão Android; os testes, uma falsa.
 */
interface Plataforma {
    val preferencias: Preferencias

    /** "este celular": nas mensagens de ajuda. */
    val esteAparelho: String

    fun criarEscuta(): Escuta

    /** [aoReceber] recebe o PCM (os bytes e quantos valem) numa thread de áudio. */
    fun criarMicrofone(aoReceber: (ByteArray, Int) -> Unit): Microfone

    /** Pede (se preciso) a permissão do microfone; true se liberada. */
    suspend fun pedirMicrofone(): Boolean

    /** No Android 17 a rede local é uma permissão; antes dele é sempre true. */
    suspend fun pedirRedeLocal(): Boolean

    /** Texto em português virado G.711 A-law de 8 kHz. Bloqueia por alguns segundos. */
    fun vozParaAlaw(texto: String): ByteArray

    /** Guarda um JPEG na galeria do aparelho. */
    suspend fun guardarFoto(jpeg: ByteArray, nomeDaCamera: String): FotoGuardada

    /** Segura o multicast do Wi-Fi enquanto a descoberta roda. */
    fun seguraMulticast(): AutoCloseable

    /** O som que se ouve da câmera precisa do "foco de áudio"; [aoPerder] avisa quando outro app toma. */
    fun pedirFocoDeAudio(aoPerder: () -> Unit): Boolean
    fun soltarFocoDeAudio()
}
