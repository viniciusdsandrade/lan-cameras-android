package com.lancameras.android.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.lancameras.android.apoio.Depuracao
import com.lancameras.nucleo.video.Decodificador
import com.lancameras.nucleo.video.ParametrosDeVideo
import com.lancameras.nucleo.video.QuadroDeVideo
import com.lancameras.nucleo.video.TamanhoDoVideo
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Decodifica H.264 e H.265 com o hardware do aparelho (MediaCodec) direto numa [Surface].
 *
 * Recebe os quadros já em Annex B, com os parâmetros (VPS/SPS/PPS) à parte; se eles mudarem, o
 * decodificador é refeito. Depois de qualquer tropeço (buffer cheio, erro do codec) só volta num
 * quadro de referência, para não mostrar imagem quebrada. [decodificar] roda na thread do RTSP e
 * nunca espera mais que um instante; a saída é entregue pela thread própria do codec.
 */
class DecodificadorMediaCodec(private val superficie: Surface, private val aoFalhar: (String) -> Unit = {}) : Decodificador {
    private val thread = HandlerThread("mediacodec-${numero.incrementAndGet()}").apply { start() }
    private val handler = Handler(thread.looper)
    private val trava = Any()
    @Volatile private var codec: MediaCodec? = null
    private var configurado: ParametrosDeVideo? = null
    @Volatile private var entradasLivres = LinkedBlockingDeque<Int>()
    @Volatile private var falhou = false
    @Volatile private var aguardandoChave = true
    private var tentativas = 0
    private var encerrado = false
    private val exibidos = AtomicInteger()

    /** Quadros que o hardware já desenhou na superfície. */
    val quadrosExibidos: Int get() = exibidos.get()

    override fun configurar(parametros: ParametrosDeVideo) {
        synchronized(trava) {
            if (encerrado) return
            val atual = configurado
            if (codec != null && !falhou && atual != null && mesmos(atual, parametros)) return
            liberar()
            configurado = parametros
            tentativas = 0
            criar(parametros)
        }
    }

    private fun mesmos(a: ParametrosDeVideo, b: ParametrosDeVideo): Boolean =
        a.codec == b.codec && a.csd.size == b.csd.size && a.csd.indices.all { a.csd[it].contentEquals(b.csd[it]) }

    /** Só com a trava. */
    private fun criar(p: ParametrosDeVideo) {
        val t = p.tamanho ?: TamanhoDoVideo(1920, 1080)
        val formato = MediaFormat.createVideoFormat(p.codec.mime, t.largura, t.altura)
        for ((i, b) in p.csd.withIndex()) formato.setByteBuffer("csd-$i", ByteBuffer.wrap(b))
        formato.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(1 shl 20, t.largura * t.altura / 2))
        var c: MediaCodec? = null
        try {
            val nome = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(formato)
            c = if (nome != null) MediaCodec.createByCodecName(nome) else MediaCodec.createDecoderByType(p.codec.mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                c.codecInfo.getCapabilitiesForType(p.codec.mime).isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
            ) {
                formato.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            entradasLivres = LinkedBlockingDeque()
            c.setCallback(Retorno(c), handler)
            c.configure(formato, superficie, null, 0)
            c.start()
            codec = c
            falhou = false
            aguardandoChave = true
            Depuracao.registrar { "decodificador ${c.name} para ${p.codec.nome} ${t.largura}x${t.altura}" }
        } catch (e: Exception) {
            c?.release()
            codec = null
            falhou = true
            tentativas++
            aoFalhar("decodificador de ${p.codec.nome}: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    override fun decodificar(quadro: QuadroDeVideo): Boolean {
        if (falhou) {
            // depois de um erro do codec, tenta refazê-lo num quadro de referência, poucas vezes
            if (!quadro.chave) return false
            synchronized(trava) {
                if (encerrado || tentativas >= 3) return false
                liberar()
                configurado?.let { criar(it) }
            }
            if (falhou) return false
        }
        val c = codec ?: return false
        if (aguardandoChave) {
            if (!quadro.chave) return false
            aguardandoChave = false
        }
        val indice = try {
            entradasLivres.pollFirst(150, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            null
        }
        if (indice == null) {
            aguardandoChave = true                                           // o codec não deu vazão: recomeça limpo
            return false
        }
        return try {
            val buffer = c.getInputBuffer(indice)
            if (buffer == null || buffer.capacity() < quadro.dados.size) {
                c.queueInputBuffer(indice, 0, 0, 0, 0)                        // devolve o buffer vazio
                aguardandoChave = true
                return false
            }
            buffer.clear()
            buffer.put(quadro.dados)
            c.queueInputBuffer(indice, 0, quadro.dados.size, quadro.tempoUs, 0)
            true
        } catch (e: IllegalStateException) {
            falhou = true
            aoFalhar("decodificador parou: ${e.message ?: "estado inválido"}")
            false
        }
    }

    override fun encerrar() {
        synchronized(trava) {
            encerrado = true
            liberar()
        }
        thread.quitSafely()
    }

    /** Só com a trava. */
    private fun liberar() {
        val c = codec ?: return
        codec = null
        entradasLivres.clear()
        try { c.stop() } catch (_: IllegalStateException) {}
        c.release()
    }

    private inner class Retorno(private val meu: MediaCodec) : MediaCodec.Callback() {
        override fun onInputBufferAvailable(c: MediaCodec, index: Int) {
            if (codec === meu) entradasLivres.offerLast(index)
        }

        override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (codec !== meu) return
            try {
                c.releaseOutputBuffer(index, true)                           // desenha já: só o quadro mais novo importa
                exibidos.incrementAndGet()
            } catch (_: IllegalStateException) {
            }
        }

        override fun onError(c: MediaCodec, e: MediaCodec.CodecException) {
            if (codec !== meu) return
            falhou = true
            aoFalhar("decodificador: ${e.diagnosticInfo}")
        }

        override fun onOutputFormatChanged(c: MediaCodec, format: MediaFormat) {}
    }

    private companion object {
        val numero = AtomicInteger()
    }
}
