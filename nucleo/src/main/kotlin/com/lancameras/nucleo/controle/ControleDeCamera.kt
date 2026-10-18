package com.lancameras.nucleo.controle

import com.lancameras.nucleo.Redact
import com.lancameras.nucleo.dvrip.ClienteDvrip
import com.lancameras.nucleo.dvrip.ModoDaLuz
import com.lancameras.nucleo.modelo.ArmazenamentoDeCameras
import com.lancameras.nucleo.modelo.CameraConfig
import com.lancameras.nucleo.onvif.ClienteOnvif
import com.lancameras.nucleo.onvif.PresetDaCamera

class ErroDeControle(descricao: String) : Exception(Redact.texto(descricao)) {
    override fun toString(): String = message ?: "erro"
}

/** O que a câmera informa sobre si. Leitura apenas. */
data class EstadoDaCamera(
    val luz: ModoDaLuz? = null,
    val rastreioLigado: Boolean? = null,
    val rastreioVoltaEmSegundos: Int? = null,
    val sinalDoWiFi: Int? = null,
    val presets: List<PresetDaCamera> = emptyList(),
    /** Partes que não deu para ler (a câmera pode responder umas coisas e outras não). */
    val avisos: List<String> = emptyList(),
)

/**
 * As operações de uma câmera que não são vídeo nem fala: estado, luz, rastreio e posições salvas.
 * Todas bloqueiam; o app as chama fora da thread principal.
 */
class ControleDeCamera(
    val camera: CameraConfig,
    private val armazenamento: ArmazenamentoDeCameras,
    private val portaOnvif: Int = 8899,
    private val portaDvrip: Int = ClienteDvrip.PORTA_PADRAO,
) {
    private val onvif get() = ClienteOnvif(camera.host, portaOnvif, camera.usuario, camera.hash)

    private fun <T> comDvrip(corpo: (ClienteDvrip) -> T): T =
        ClienteDvrip.executar(camera.host, camera.usuario, camera.hash, portaDvrip, corpo = corpo)

    /** Lê luz, rastreio, sinal e posições salvas sem mudar nada. */
    fun lerEstado(): EstadoDaCamera {
        var e = EstadoDaCamera()
        val avisos = mutableListOf<String>()
        try {
            comDvrip { d ->
                val luz = d.modoDaLuz()
                val r = d.rastreio()
                val sinal = try { d.sinalDoWiFi() } catch (_: Exception) { null }
                e = e.copy(luz = luz, rastreioLigado = r.ligado, rastreioVoltaEmSegundos = r.voltaEmSegundos, sinalDoWiFi = sinal)
            }
        } catch (x: Exception) {
            avisos += "a câmera não respondeu pelo DVRIP ($x)"
        }
        try {
            e = e.copy(presets = onvif.presets())
        } catch (x: Exception) {
            avisos += "não consegui ler as posições salvas ($x)"
        }
        return e.copy(avisos = avisos)
    }

    fun definirLuz(modo: ModoDaLuz) = comDvrip { it.definirLuz(modo) }

    fun definirRastreio(ligado: Boolean) = comDvrip { it.definirRastreio(ligado) }

    fun pararMovimento() = onvif.parar()

    // Posições salvas

    fun irParaPreset(token: String) = onvif.irParaPreset(token)

    /** Guarda a posição atual com um nome (a câmera corta em 24). Devolve o número que a câmera deu a ela. */
    fun salvarPreset(nome: String): String {
        val limpo = limparNome(nome)
        if (limpo.isEmpty()) throw ErroDeControle("dê um nome à posição")
        val token = onvif.salvarPreset(limpo)
        armazenamento.registrarPreset(token, camera.id)
        return token
    }

    /** Só apaga o que este app criou: as posições que já vinham na câmera nunca são tocadas. */
    fun apagarPreset(token: String) {
        if (token !in armazenamento.presetsDoApp(camera.id)) throw ErroDeControle("só apago posições criadas por este app")
        onvif.removerPreset(token)
        armazenamento.esquecerPreset(token, camera.id)
    }

    fun presetsCriadosPeloApp(): Set<String> = armazenamento.presetsDoApp(camera.id)

    companion object {
        /** Sem quebras de linha nem caracteres de controle, sem espaço nas pontas, no máximo 24 caracteres. */
        fun limparNome(nome: String): String {
            val sb = StringBuilder()
            nome.codePoints().forEach { c ->
                if (c == ' '.code || (!Character.isWhitespace(c) && !Character.isISOControl(c) && Character.getType(c) != Character.FORMAT.toInt())) {
                    sb.appendCodePoint(c)
                }
            }
            val t = sb.toString().trim()
            val fim = if (t.codePointCount(0, t.length) > 24) t.offsetByCodePoints(0, 24) else t.length
            return t.substring(0, fim).trim()
        }
    }
}
