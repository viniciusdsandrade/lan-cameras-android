package com.lancameras.android.sessao

import com.lancameras.android.apoio.Depuracao
import com.lancameras.android.video.DistribuidorDeVideo
import com.lancameras.android.video.LenteExibida
import com.lancameras.nucleo.modelo.CameraConfig
import com.lancameras.nucleo.modelo.StreamDeVideo
import com.lancameras.nucleo.rtsp.PacoteRtp
import com.lancameras.nucleo.video.FluxoDeVideo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * O vídeo de uma câmera na tela: um fluxo RTSP (que ocupa uma sessão na câmera) mais o reparte de
 * quadros entre as telas que o mostram.
 */
class SessaoDeVideo(val camera: CameraConfig, val stream: StreamDeVideo, comAudio: Boolean = false) {
    val distribuidor = DistribuidorDeVideo()
    private val _estado = MutableStateFlow<FluxoDeVideo.Estado>(FluxoDeVideo.Estado.Parado)
    val estado: StateFlow<FluxoDeVideo.Estado> = _estado

    /** Quem tira a foto: a tela que mostra o quadro inteiro registra aqui como capturá-lo em JPEG. */
    @Volatile var capturador: (suspend (LenteExibida) -> ByteArray?)? = null

    /**
     * A câmera recusou o usuário e a senha guardados e o fluxo parou de tentar: quem abriu a sessão
     * decide o que fazer (o modelo pega a senha atual com a própria câmera). Chamado de uma thread interna.
     */
    @Volatile var aoRecusarCredenciais: (() -> Unit)? = null

    private val fluxo = FluxoDeVideo(camera.urlDeVideo(stream), comAudio) { _, aoExibir -> distribuidor.novaSessao(aoExibir) }

    init {
        val rotulo = "${camera.nome} (${stream.name.lowercase()})"
        fluxo.aoMudarEstado = { novo ->
            Depuracao.registrar { "$rotulo: $novo" }
            _estado.value = novo
        }
        fluxo.aoRecusarCredenciais = { aoRecusarCredenciais?.invoke() }
    }

    fun iniciar() = fluxo.iniciar()

    /** Libera a sessão na câmera (elas aguentam poucas). */
    fun parar() = fluxo.parar()

    val quadrosRecebidos: Int get() = fluxo.quadrosRecebidos

    /** Quem recebe o áudio da câmera (null para ignorar). Chamado de uma thread interna. */
    fun definirOuvinteDeAudio(f: ((PacoteRtp) -> Unit)?) {
        fluxo.aoReceberAudio = f
    }
}
