package com.lancameras.nucleo.onvif

import com.lancameras.nucleo.Redact
import com.lancameras.nucleo.rede.ErroDeRede
import com.lancameras.nucleo.rede.HttpSimples
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.Locale

/** Falha do ONVIF; a mensagem passa por [Redact]. */
class ErroOnvif(descricao: String) : Exception(Redact.texto(descricao)) {
    override fun toString(): String = message ?: "erro ONVIF"
}

data class InformacaoDoAparelho(val fabricante: String, val modelo: String, val firmware: String, val serie: String)

data class CredenciaisDaCamera(
    val usuario: String,
    /** Hash Sofia da senha: é o que a câmera aceita no lugar dela. Tratar como senha. */
    val hash: String,
) {
    override fun toString() = "CredenciaisDaCamera(***)"
}

data class PresetDaCamera(val token: String, val nome: String)

internal object SegurancaOnvif {
    private const val WSSE = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd"
    private const val WSU = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd"
    private const val DIGEST = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest"
    private const val BASE64 = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary"
    private val aleatorio = SecureRandom()

    /** UsernameToken com PasswordDigest = Base64(SHA1(nonce + criado + senha)). */
    fun cabecalho(usuario: String, senha: String, nonce: ByteArray, criado: String): String {
        val sha1 = MessageDigest.getInstance("SHA-1")
        sha1.update(nonce)
        sha1.update(criado.toByteArray(Charsets.UTF_8))
        sha1.update(senha.toByteArray(Charsets.UTF_8))
        val resumo = Base64.getEncoder().encodeToString(sha1.digest())
        val nonceTexto = Base64.getEncoder().encodeToString(nonce)
        return "<s:Header><Security xmlns=\"$WSSE\"><UsernameToken>" +
            "<Username>${XmlMinimo.escapar(usuario)}</Username>" +
            "<Password Type=\"$DIGEST\">$resumo</Password>" +
            "<Nonce EncodingType=\"$BASE64\">$nonceTexto</Nonce>" +
            "<Created xmlns=\"$WSU\">$criado</Created>" +
            "</UsernameToken></Security></s:Header>"
    }

    fun cabecalho(usuario: String, senha: String): String {
        val nonce = ByteArray(16).also { aleatorio.nextBytes(it) }
        // "2026-01-02T03:04:05Z", sem frações
        return cabecalho(usuario, senha, nonce, Instant.now().truncatedTo(ChronoUnit.SECONDS).toString())
    }
}

/**
 * ONVIF das câmeras XiongMai (porta 8899): identificação, credenciais, movimento (PTZ) e posições
 * salvas. Todas as chamadas bloqueiam: use fora da thread principal.
 *
 * Sem [usuario] e [hash] as chamadas vão sem assinatura (o firmware aceita para identificação e
 * `GetStreamUri`, que é como o app descobre as credenciais).
 */
class ClienteOnvif(
    private val host: String,
    private val porta: Int = 8899,
    private val usuario: String? = null,
    private val hash: String? = null,
    private val perfil: String = "000",
) {
    // SOAP

    internal fun soap(servico: String, corpo: String, assinar: Boolean = true): String {
        val cabecalho = if (assinar && usuario != null && hash != null) SegurancaOnvif.cabecalho(usuario, hash) else ""
        val envelope = "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope $ESPACOS>$cabecalho<s:Body>$corpo</s:Body></s:Envelope>"
        val r = try {
            HttpSimples.post(host, porta, "/onvif/${servico}_service", envelope, "application/soap+xml; charset=utf-8")
        } catch (e: ErroDeRede) {
            throw ErroOnvif(e.toString())
        }
        val texto = r.texto
        XmlMinimo.falha(texto)?.let { throw ErroOnvif(it) }
        return texto
    }

    // Identificação

    fun informacoes(): InformacaoDoAparelho {
        val xml = soap("device", "<tds:GetDeviceInformation/>")
        fun um(tag: String) = XmlMinimo.valores(xml, tag).firstOrNull() ?: ""
        val serie = um("SerialNumber")
        if (serie.isEmpty()) throw ErroOnvif("a câmera não informou o número de série")
        return InformacaoDoAparelho(um("Manufacturer"), um("Model"), um("FirmwareVersion"), serie)
    }

    /** Usuário e hash da senha, tirados da URL RTSP que a câmera entrega sem pedir autenticação. */
    fun credenciais(): CredenciaisDaCamera {
        val corpo = "<trt:GetStreamUri><trt:StreamSetup><tt:Stream>RTP-Unicast</tt:Stream>" +
            "<tt:Transport><tt:Protocol>RTSP</tt:Protocol></tt:Transport></trt:StreamSetup>" +
            "<trt:ProfileToken>$perfil</trt:ProfileToken></trt:GetStreamUri>"
        val xml = soap("media", corpo, assinar = false)
        val uri = XmlMinimo.valores(xml, "Uri").firstOrNull()
        val u = uri?.indexOf("user=") ?: -1
        val s = if (u >= 0) uri!!.indexOf("_password=", u + 5) else -1
        val f = if (s >= 0) uri!!.indexOf("_channel", s + 10) else -1
        if (uri == null || f < 0) throw ErroOnvif("a câmera não devolveu usuário e senha no endereço do vídeo")
        return CredenciaisDaCamera(uri.substring(u + 5, s), uri.substring(s + 10, f))
    }

    // Movimento

    /**
     * Movimento contínuo, velocidades em [-1, 1]. `x > 0` vira à ESQUERDA: o pan vem invertido no
     * firmware. [timeout] faz a própria câmera parar sozinha se o app travar.
     */
    fun mover(x: Double, y: Double, timeout: Double? = 1.0) {
        val limite = timeout?.let { "<tptz:Timeout>PT${decimal(it).replace(".000", "")}S</tptz:Timeout>" } ?: ""
        soap(
            "ptz",
            "<tptz:ContinuousMove><tptz:ProfileToken>$perfil</tptz:ProfileToken>" +
                "<tptz:Velocity><tt:PanTilt x=\"${decimal(x)}\" y=\"${decimal(y)}\"/>" +
                "<tt:Zoom x=\"0.000\"/></tptz:Velocity>$limite</tptz:ContinuousMove>",
        )
    }

    fun parar() {
        soap(
            "ptz",
            "<tptz:Stop><tptz:ProfileToken>$perfil</tptz:ProfileToken>" +
                "<tptz:PanTilt>true</tptz:PanTilt><tptz:Zoom>true</tptz:Zoom></tptz:Stop>",
        )
    }

    /** A câmera diz que o motor de pan/tilt está andando? (`MoveStatus` do ONVIF GetStatus.) */
    fun movendo(): Boolean {
        val xml = soap("ptz", "<tptz:GetStatus><tptz:ProfileToken>$perfil</tptz:ProfileToken></tptz:GetStatus>")
        val status = XmlMinimo.valores(xml, "PanTilt").firstOrNull { it == "IDLE" || it == "MOVING" }
            ?: throw ErroOnvif("a câmera não informou se está se movendo")
        return status == "MOVING"
    }

    // Posições salvas

    fun presets(): List<PresetDaCamera> {
        val xml = soap("ptz", "<tptz:GetPresets><tptz:ProfileToken>$perfil</tptz:ProfileToken></tptz:GetPresets>")
        return RE_PRESET.findAll(xml).map { m ->
            val token = m.groupValues[1]
            PresetDaCamera(token, XmlMinimo.valores(m.groupValues[2], "Name").firstOrNull() ?: token)
        }.toList()
    }

    fun irParaPreset(token: String) {
        soap(
            "ptz",
            "<tptz:GotoPreset><tptz:ProfileToken>$perfil</tptz:ProfileToken>" +
                "<tptz:PresetToken>${XmlMinimo.escapar(token)}</tptz:PresetToken></tptz:GotoPreset>",
        )
    }

    /** Salva a posição atual e devolve o número que a câmera deu a ela. */
    fun salvarPreset(nome: String): String {
        val antes = presets().map { it.token }.toSet()
        val xml = soap(
            "ptz",
            "<tptz:SetPreset><tptz:ProfileToken>$perfil</tptz:ProfileToken>" +
                "<tptz:PresetName>${XmlMinimo.escapar(nome)}</tptz:PresetName></tptz:SetPreset>",
        )
        val novos = presets().map { it.token }.toSet() - antes
        novos.sorted().firstOrNull()?.let { return it }
        XmlMinimo.valores(xml, "PresetToken").firstOrNull()?.let { return it }
        throw ErroOnvif("a câmera não devolveu o número da posição")
    }

    fun removerPreset(token: String) {
        soap(
            "ptz",
            "<tptz:RemovePreset><tptz:ProfileToken>$perfil</tptz:ProfileToken>" +
                "<tptz:PresetToken>${XmlMinimo.escapar(token)}</tptz:PresetToken></tptz:RemovePreset>",
        )
    }

    private companion object {
        const val ESPACOS = "xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\" " +
            "xmlns:tds=\"http://www.onvif.org/ver10/device/wsdl\" " +
            "xmlns:trt=\"http://www.onvif.org/ver10/media/wsdl\" " +
            "xmlns:tptz=\"http://www.onvif.org/ver20/ptz/wsdl\" " +
            "xmlns:tt=\"http://www.onvif.org/ver10/schema\""

        val RE_PRESET = Regex("<[^>]*Preset\\b[^>]*token=\"([^\"]+)\"[^>]*>(.*?)</[^>]*Preset>", RegexOption.DOT_MATCHES_ALL)

        /** Ponto como separador, qualquer que seja o idioma do aparelho. */
        fun decimal(v: Double): String = String.format(Locale.ROOT, "%.3f", v)
    }
}
