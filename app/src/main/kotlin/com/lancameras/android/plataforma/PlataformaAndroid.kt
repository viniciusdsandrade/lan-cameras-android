package com.lancameras.android.plataforma

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.lancameras.android.apoio.PreferenciasEmMemoria
import com.lancameras.android.sessao.Escuta
import com.lancameras.android.sessao.FalhaDaPlataforma
import com.lancameras.android.sessao.FotoGuardada
import com.lancameras.android.sessao.Microfone
import com.lancameras.android.sessao.Plataforma
import com.lancameras.nucleo.audio.ALaw
import com.lancameras.nucleo.audio.Voz
import com.lancameras.nucleo.rtsp.PacoteRtp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.min
import kotlin.math.sqrt

/** Preferências em `SharedPreferences` (nada de segredo aqui). */
class PreferenciasAndroid(context: Context) : PreferenciasEmMemoria() {
    private val sp = context.getSharedPreferences("preferencias", Context.MODE_PRIVATE)

    init {
        qualidadeHD.value = sp.getBoolean("qualidadeHD", true)
        mostrarLenteFixa.value = sp.getBoolean("mostrarLenteFixa", false)   // a tela é pequena: começa recolhida
        velocidade.value = sp.getFloat("velocidade", 0.4f).toDouble()
        volume.value = sp.getFloat("volume", 30f).toDouble()
    }

    override fun definirQualidadeHD(v: Boolean) { super.definirQualidadeHD(v); sp.edit { putBoolean("qualidadeHD", v) } }
    override fun definirMostrarLenteFixa(v: Boolean) { super.definirMostrarLenteFixa(v); sp.edit { putBoolean("mostrarLenteFixa", v) } }
    override fun definirVelocidade(v: Double) { super.definirVelocidade(v); sp.edit { putFloat("velocidade", velocidade.value.toFloat()) } }
    override fun definirVolume(v: Double) { super.definirVolume(v); sp.edit { putFloat("volume", volume.value.toFloat()) } }
}

/** Toca o áudio da câmera (A-law, 8 kHz) num `AudioTrack`; só o que cabe no buffer entra, sem acumular atraso. */
class EscutaAndroid : Escuta {
    private val trava = Any()
    private var track: AudioTrack? = null
    private var recebidos = 0

    override fun iniciar() = synchronized(trava) {
        if (track != null) return
        val minimo = AudioTrack.getMinBufferSize(TAXA, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(TAXA).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(minimo * 2, TAXA))                 // ~0,5 s
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (t.state != AudioTrack.STATE_INITIALIZED) {
            t.release()
            throw FalhaDaPlataforma("não consegui abrir o som do aparelho")
        }
        track = t
        recebidos = 0
    }

    override fun empurrar(pacote: PacoteRtp) {
        if (pacote.tipoDeCarga != 8 || pacote.carga.isEmpty()) return        // 8 = PCMA
        synchronized(trava) {
            val t = track ?: return
            val pcm = ALaw.decodificar(pacote.carga)
            t.write(pcm, 0, pcm.size, AudioTrack.WRITE_NON_BLOCKING)
            recebidos++
            if (recebidos == PACOTES_ANTES_DE_TOCAR) t.play()
        }
    }

    override fun parar() = synchronized(trava) {
        track?.let {
            try { it.pause(); it.flush() } catch (_: IllegalStateException) {}
            it.release()
        }
        track = null
    }

    private companion object {
        const val TAXA = 8000
        /** ~120 ms de folga contra a variação da rede. */
        const val PACOTES_ANTES_DE_TOCAR = 3
    }
}

/** Capta o microfone a 8 kHz (ou a 16 kHz, reduzido) e entrega PCM de 16 bits em pedaços de 40 ms. */
class MicrofoneAndroid(private val context: Context, private val aoReceber: (ByteArray, Int) -> Unit) : Microfone {
    private var gravador: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var ativo = false
    @Volatile override var nivel = 0f
        private set

    // A permissão é conferida na linha abaixo; sem ela a falha vira mensagem, não exceção do sistema.
    @SuppressLint("MissingPermission")
    override fun iniciar() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw FalhaDaPlataforma("o app não tem acesso ao microfone: permita nas configurações do aparelho")
        }
        if (ativo) return
        var taxa = 8000
        var g = criar(taxa)
        if (g == null) {
            taxa = 16000
            g = criar(taxa) ?: throw FalhaDaPlataforma("nenhum microfone disponível agora")
        }
        gravador = g
        ativo = true
        g.startRecording()
        thread = thread(name = "microfone", priority = Thread.MAX_PRIORITY) { laco(g, taxa) }
    }

    @SuppressLint("MissingPermission")
    private fun criar(taxa: Int): AudioRecord? {
        val minimo = AudioRecord.getMinBufferSize(taxa, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minimo <= 0) return null
        val r = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, taxa, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimo * 2, taxa * 2 / 5))
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            return null
        }
        return r
    }

    private fun laco(g: AudioRecord, taxa: Int) {
        val amostras = taxa / 25                                              // 40 ms
        val buf = ShortArray(amostras)
        while (ativo) {
            val n = g.read(buf, 0, amostras)
            if (n <= 0) continue
            medir(buf, n)
            val pcm = if (taxa == 8000) buf.copyOf(n) else Voz.reamostrar(buf.copyOf(n), taxa, 8000)
            val bytes = ByteArray(pcm.size * 2)
            for (i in pcm.indices) {
                bytes[2 * i] = (pcm[i].toInt() and 0xFF).toByte()
                bytes[2 * i + 1] = (pcm[i].toInt() shr 8).toByte()
            }
            aoReceber(bytes, bytes.size)
        }
    }

    private fun medir(buf: ShortArray, n: Int) {
        var soma = 0.0
        for (i in 0 until n) {
            val v = buf[i] / 32768.0
            soma += v * v
        }
        nivel = min(1.0, sqrt(soma / n) * 4).toFloat()
    }

    override fun parar() {
        ativo = false
        thread?.join(500)
        thread = null
        gravador?.let {
            try { it.stop() } catch (_: IllegalStateException) {}
            it.release()
        }
        gravador = null
        nivel = 0f
    }
}

/** Texto em português falado pela voz do Android (`TextToSpeech`), gravado em WAV e virado A-law de 8 kHz. */
class TextoFaladoAndroid(private val context: Context) {
    private val trava = Any()
    private var motor: TextToSpeech? = null

    private fun motor(): TextToSpeech = synchronized(trava) {
        motor?.let { return it }
        val pronto = CountDownLatch(1)
        var situacao = TextToSpeech.ERROR
        val m = TextToSpeech(context) { s -> situacao = s; pronto.countDown() }
        if (!pronto.await(10, TimeUnit.SECONDS) || situacao != TextToSpeech.SUCCESS) {
            m.shutdown()
            throw FalhaDaPlataforma("não há voz instalada neste aparelho")
        }
        val idioma = m.setLanguage(Locale.forLanguageTag("pt-BR"))
        if (idioma == TextToSpeech.LANG_MISSING_DATA || idioma == TextToSpeech.LANG_NOT_SUPPORTED) {
            m.shutdown()
            throw FalhaDaPlataforma("instale a voz em português nas configurações do aparelho")
        }
        motor = m
        m
    }

    /** Bloqueia enquanto a voz é gerada (poucos segundos): chame fora da thread principal. */
    fun alaw(texto: String): ByteArray {
        val limpo = Voz.limpar(texto)
        val m = motor()
        val arquivo = File.createTempFile("voz", ".wav", context.cacheDir)
        try {
            val id = UUID.randomUUID().toString()
            val pronto = CountDownLatch(1)
            var erro: String? = null
            m.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { if (utteranceId == id) pronto.countDown() }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { if (utteranceId == id) { erro = "a voz falhou"; pronto.countDown() } }
                override fun onError(utteranceId: String?, errorCode: Int) { if (utteranceId == id) { erro = "a voz falhou ($errorCode)"; pronto.countDown() } }
            })
            if (m.synthesizeToFile(limpo, Bundle(), arquivo, id) != TextToSpeech.SUCCESS) throw FalhaDaPlataforma("não consegui gerar a voz")
            if (!pronto.await(20, TimeUnit.SECONDS)) throw FalhaDaPlataforma("a voz demorou demais")
            erro?.let { throw FalhaDaPlataforma(it) }
            return Voz.alawDoWav(arquivo.readBytes())
        } finally {
            arquivo.delete()
        }
    }
}

/** O que a camada de sessão precisa do Android. Vive enquanto o app vive. */
class PlataformaAndroid(private val context: Context) : Plataforma {
    override val preferencias = PreferenciasAndroid(context)
    override val esteAparelho = "este celular"

    /** Registrado pela tela, que é quem pode pedir permissões ao sistema. */
    @Volatile var pedidorDePermissao: (suspend (String) -> Boolean)? = null

    private val voz = TextoFaladoAndroid(context)
    private val audio get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focoDeAudio: AudioFocusRequest? = null

    override fun criarEscuta(): Escuta = EscutaAndroid()

    override fun criarMicrofone(aoReceber: (ByteArray, Int) -> Unit): Microfone = MicrofoneAndroid(context, aoReceber)

    private fun concedida(permissao: String) = ContextCompat.checkSelfPermission(context, permissao) == PackageManager.PERMISSION_GRANTED

    private suspend fun pedir(permissao: String): Boolean {
        if (concedida(permissao)) return true
        return pedidorDePermissao?.invoke(permissao) ?: false
    }

    override suspend fun pedirMicrofone(): Boolean = pedir(Manifest.permission.RECORD_AUDIO)

    /** No Android 17 (API 37) a rede local é uma permissão pedida durante o uso. */
    override suspend fun pedirRedeLocal(): Boolean =
        if (Build.VERSION.SDK_INT >= 37) pedir("android.permission.ACCESS_LOCAL_NETWORK") else true

    override fun vozParaAlaw(texto: String): ByteArray = voz.alaw(texto)

    override suspend fun guardarFoto(jpeg: ByteArray, nomeDaCamera: String): FotoGuardada = withContext(Dispatchers.IO) {
        val valores = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "${nomeDeArquivo(nomeDaCamera)}-${carimbo()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${android.os.Environment.DIRECTORY_PICTURES}/$PASTA_DE_FOTOS")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, valores) ?: throw FalhaDaPlataforma("a galeria recusou a foto")
        try {
            (resolver.openOutputStream(uri) ?: throw FalhaDaPlataforma("não consegui gravar a foto")).use { it.write(jpeg) }
            valores.clear()
            valores.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, valores, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        FotoGuardada("Galeria > $PASTA_DE_FOTOS")
    }

    override fun seguraMulticast(): AutoCloseable {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("cameras").apply {
            setReferenceCounted(false)
            acquire()
        }
        return AutoCloseable { if (lock.isHeld) lock.release() }
    }

    override fun pedirFocoDeAudio(aoPerder: () -> Unit): Boolean {
        soltarFocoDeAudio()
        val pedido = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { mudanca ->
                if (mudanca == AudioManager.AUDIOFOCUS_LOSS || mudanca == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) aoPerder()
            }
            .build()
        focoDeAudio = pedido
        return audio.requestAudioFocus(pedido) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    override fun soltarFocoDeAudio() {
        focoDeAudio?.let { audio.abandonAudioFocusRequest(it) }
        focoDeAudio = null
    }

    companion object {
        const val PASTA_DE_FOTOS = "Câmeras de Casa"

        fun nomeDeArquivo(nome: String): String = nome.replace("/", "-").replace(":", "-")

        fun carimbo(data: Date = Date()): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(data)
    }
}
