package com.lancameras.nucleo.modelo

import com.lancameras.nucleo.onvif.ClienteOnvif
import kotlinx.serialization.Serializable

enum class StreamDeVideo(val numero: Int) {
    /** 2304x2592, as duas lentes empilhadas; pesado (HD). */
    PRINCIPAL(0),
    /** 640x720; leve. */
    SECUNDARIO(1),
}

/** Uma câmera cadastrada neste aparelho. */
@Serializable
data class CameraConfig(
    /** Número de série informado pela câmera: não muda quando o IP muda. */
    val id: String,
    val nome: String,
    val host: String,
    val usuario: String,
    /** Hash Sofia da senha. Segredo: nunca vai para log, tela ou mensagem de erro. */
    val hash: String,
    val modelo: String? = null,
    /** As câmeras de duas lentes mandam as vistas empilhadas no mesmo quadro. */
    val duasLentes: Boolean = true,
    val ordem: Int = 0,
) {
    /** URL RTSP da câmera (carrega usuário e senha: nunca imprimir; use `Redact`). */
    fun urlDeVideo(stream: StreamDeVideo): String =
        "rtsp://$host:554/user=${usuario}_password=${hash}_channel=0_stream=${stream.numero}&onvif=0.sdp?real_stream"

    val onvif: ClienteOnvif get() = ClienteOnvif(host, usuario = usuario, hash = hash)

    override fun toString(): String = "Câmera($nome, ${modelo ?: "?"})"
}
