package com.lancameras.android.sessao

import com.lancameras.android.apoio.PreferenciasEmMemoria
import com.lancameras.nucleo.audio.ALaw
import com.lancameras.nucleo.rtsp.PacoteRtp
import java.util.concurrent.atomic.AtomicInteger

/** A plataforma dos testes: nada de som, microfone, voz nem galeria; só anota o que foi pedido. */
class PlataformaFalsa : Plataforma {
    override val preferencias = PreferenciasEmMemoria()
    override val esteAparelho = "este aparelho de teste"
    var microfonePermitido = true
    var redeLocalPermitida = true
    val fotosGuardadas = mutableListOf<Pair<String, Int>>()
    val escutasAtivas = AtomicInteger()
    val focosAtivos = AtomicInteger()

    override fun criarEscuta(): Escuta = object : Escuta {
        override fun iniciar() { escutasAtivas.incrementAndGet() }
        override fun empurrar(pacote: PacoteRtp) {}
        override fun parar() { escutasAtivas.decrementAndGet() }
    }

    override fun criarMicrofone(aoReceber: (ByteArray, Int) -> Unit): Microfone = object : Microfone {
        override fun iniciar() {}
        override fun parar() {}
        override val nivel = 0f
    }

    override suspend fun pedirMicrofone() = microfonePermitido
    override suspend fun pedirRedeLocal() = redeLocalPermitida

    override fun vozParaAlaw(texto: String): ByteArray = ByteArray(320) { ALaw.SILENCIO }

    override suspend fun guardarFoto(jpeg: ByteArray, nomeDaCamera: String): FotoGuardada {
        fotosGuardadas += nomeDaCamera to jpeg.size
        return FotoGuardada("galeria de teste")
    }

    override fun seguraMulticast(): AutoCloseable = AutoCloseable {}

    override fun pedirFocoDeAudio(aoPerder: () -> Unit): Boolean { focosAtivos.incrementAndGet(); return true }
    override fun soltarFocoDeAudio() { focosAtivos.set(0) }
}
