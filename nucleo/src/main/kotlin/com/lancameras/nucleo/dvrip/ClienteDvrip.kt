package com.lancameras.nucleo.dvrip

import com.lancameras.nucleo.Redact
import com.lancameras.nucleo.rede.ErroDeRede
import com.lancameras.nucleo.rede.SoqueteTcp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Falha do DVRIP; a mensagem passa por [Redact]. */
class ErroDvrip(descricao: String) : Exception(Redact.texto(descricao)) {
    override fun toString(): String = message ?: "erro DVRIP"
}

enum class ModoDaLuz(val valor: String) {
    AUTOMATICO("Intelligent"),
    LIGADA("KeepOpen"),
    DESLIGADA("Close");

    companion object {
        fun doValor(v: String?): ModoDaLuz? = entries.firstOrNull { it.valor == v }
    }
}

data class EstadoDoRastreio(val ligado: Boolean, val voltaEmSegundos: Int?)

/**
 * DVRIP / "Sofia" (porta 34567), o protocolo próprio das câmeras XiongMai: luz, rastreio
 * automático e o alto-falante. Todas as chamadas bloqueiam.
 *
 * Quadro: cabeçalho de 20 bytes + corpo. Bytes 0 e 1 = 0xFF 0x00; 4..<8 sessão; 8..<12 sequência;
 * 14..<16 id da mensagem; 16..<20 tamanho do corpo (tudo little-endian). O corpo JSON termina em
 * 0x0A 0x00, e esse par conta no tamanho.
 */
class ClienteDvrip(private val host: String, private val porta: Int = PORTA_PADRAO, private val timeout: Double = 8.0) {
    private val soquete = SoqueteTcp()
    private var sessao = 0L
    private var sequencia = 0L

    fun conectar() {
        try { soquete.conectar(host, porta, minOf(timeout, 5.0)) } catch (e: ErroDeRede) { throw ErroDvrip(e.toString()) }
    }

    fun fechar() = soquete.fechar()

    // Quadros

    private val sessaoTexto: String get() = "0x%08X".format(sessao)

    private fun jsonComTerminador(obj: JsonObject): ByteArray = obj.toString().toByteArray(Charsets.UTF_8) + byteArrayOf(0x0A, 0x00)

    private fun enviarQuadro(id: Int, corpo: ByteArray) {
        sequencia = (sequencia + 1) and 0xFFFFFFFFL
        val cab = ByteArray(20)
        cab[0] = 0xFF.toByte()
        gravar32(cab, 4, sessao)
        gravar32(cab, 8, sequencia)
        cab[14] = (id and 0xFF).toByte()
        cab[15] = ((id shr 8) and 0xFF).toByte()
        gravar32(cab, 16, corpo.size.toLong())
        try { soquete.escrever(cab + corpo) } catch (e: ErroDeRede) { throw ErroDvrip(e.toString()) }
    }

    private fun gravar32(b: ByteArray, i: Int, v: Long) {
        for (k in 0..3) b[i + k] = ((v shr (8 * k)) and 0xFF).toByte()
    }

    private fun lerExato(n: Int): ByteArray {
        val saida = ByteArray(n)
        var lidos = 0
        val limite = System.nanoTime() + (timeout * 1e9).toLong()
        while (lidos < n) {
            val restante = (limite - System.nanoTime()) / 1e9
            if (restante <= 0) throw ErroDvrip("a câmera não respondeu pelo DVRIP")
            val novos = try { soquete.ler(n - lidos, minOf(1.0, restante)) } catch (e: ErroDeRede) { throw ErroDvrip(e.toString()) }
            if (novos != null) { novos.copyInto(saida, lidos); lidos += novos.size }
        }
        return saida
    }

    private fun receberQuadro(): ByteArray {
        val cab = lerExato(20)
        fun u(i: Int) = cab[i].toInt() and 0xFF
        val tam = u(16) or (u(17) shl 8) or (u(18) shl 16) or (u(19) shl 24)
        if (tam < 0 || tam > 4 shl 20) throw ErroDvrip("resposta da câmera com tamanho absurdo")
        return if (tam > 0) lerExato(tam) else ByteArray(0)
    }

    /** Envia um JSON e devolve a resposta como objeto (vazio se não for JSON). */
    internal fun pedir(id: Int, corpo: JsonObject): JsonObject {
        enviarQuadro(id, jsonComTerminador(corpo))
        val dados = receberQuadro()
        var fim = dados.size
        while (fim > 0 && (dados[fim - 1].toInt() == 0 || dados[fim - 1].toInt() == 0x0A)) fim--
        return try {
            Json.parseToJsonElement(String(dados, 0, fim, Charsets.UTF_8)) as? JsonObject ?: JsonObject(emptyMap())
        } catch (_: Exception) {
            JsonObject(emptyMap())
        }
    }

    private fun ret(r: JsonObject): Int? = (r["Ret"] as? JsonPrimitive)?.intOrNull
    private fun retTexto(r: JsonObject) = ret(r)?.toString() ?: "?"

    // Operações

    fun entrar(usuario: String, hash: String) {
        val r = pedir(MSG_ENTRAR, buildJsonObject {
            put("EncryptType", "MD5"); put("LoginType", "DVRIP-Web"); put("PassWord", hash); put("UserName", usuario)
        })
        val sid = (r["SessionID"] as? JsonPrimitive)?.contentOrNull
        if (ret(r) !in RETORNOS_OK || sid == null) throw ErroDvrip("a câmera recusou o usuário ou a senha (Ret=${retTexto(r)})")
        sessao = sid.drop(2).toLongOrNull(16) ?: 0L
    }

    fun manterVivo() = enviarQuadro(MSG_MANTER_VIVO, jsonComTerminador(buildJsonObject {
        put("Name", "KeepAlive"); put("SessionID", sessaoTexto)
    }))

    /** Lê uma configuração inteira (ex.: "Camera.WhiteLight"). */
    fun configuracao(nome: String): JsonObject {
        val r = pedir(MSG_LER_CONFIG, buildJsonObject { put("Name", nome); put("SessionID", sessaoTexto) })
        val valor = r[nome] as? JsonObject
        if (ret(r) !in RETORNOS_OK || valor == null) throw ErroDvrip("não consegui ler $nome (Ret=${retTexto(r)})")
        return valor
    }

    /** Grava uma configuração inteira: leia, modifique e grave de volta. */
    fun gravarConfiguracao(nome: String, valor: JsonObject) {
        val r = pedir(MSG_GRAVAR_CONFIG, buildJsonObject { put("Name", nome); put("SessionID", sessaoTexto); put(nome, valor) })
        if (ret(r) !in RETORNOS_OK) throw ErroDvrip("a câmera recusou a mudança (Ret=${retTexto(r)})")
    }

    /** Qualidade do Wi-Fi que a câmera vê, de 0 a 100 (null se não informar). */
    fun sinalDoWiFi(): Int? {
        val r = pedir(MSG_SISTEMA, buildJsonObject { put("Name", "WifiRouteInfo"); put("SessionID", sessaoTexto) })
        return ((r["WifiRouteInfo"] as? JsonObject)?.get("SignalLevel") as? JsonPrimitive)?.intOrNull
    }

    // Luz e rastreio

    fun modoDaLuz(): ModoDaLuz? = ModoDaLuz.doValor((configuracao("Camera.WhiteLight")["WorkMode"] as? JsonPrimitive)?.contentOrNull)

    /** Muda a luz e confirma lendo de volta. */
    fun definirLuz(modo: ModoDaLuz) {
        gravarConfiguracao("Camera.WhiteLight", com(configuracao("Camera.WhiteLight"), "WorkMode", JsonPrimitive(modo.valor)))
        if (modoDaLuz() != modo) throw ErroDvrip("a câmera aceitou, mas a luz não mudou")
    }

    fun rastreio(): EstadoDoRastreio {
        val c = configuracao("Detect.DetectTrack")
        return EstadoDoRastreio((c["Enable"] as? JsonPrimitive)?.booleanOrNull ?: false, (c["ReturnTime"] as? JsonPrimitive)?.intOrNull)
    }

    /** Liga ou desliga o rastreio automático e confirma lendo de volta. */
    fun definirRastreio(ligado: Boolean) {
        gravarConfiguracao("Detect.DetectTrack", com(configuracao("Detect.DetectTrack"), "Enable", JsonPrimitive(ligado)))
        if (rastreio().ligado != ligado) throw ErroDvrip("a câmera aceitou, mas o rastreio não mudou")
    }

    private fun com(base: JsonObject, chave: String, valor: JsonElement) = JsonObject(base + (chave to valor))

    // Fala

    private fun corpoDeFala(acao: String) = buildJsonObject {
        put("Name", "OPTalk"); put("SessionID", sessaoTexto)
        putJsonObject("OPTalk") { put("Action", acao); putJsonObject("AudioFormat") { put("EncodeType", "G711_ALAW") } }
    }

    /** Reserva o alto-falante da câmera (G.711 A-law, 8 kHz). Só uma fala por vez. */
    fun iniciarFala() {
        val r = pedir(MSG_FALA_RESERVAR, corpoDeFala("Claim"))
        if (ret(r) !in RETORNOS_OK) throw ErroDvrip("a câmera recusou a fala (ocupada?) (Ret=${retTexto(r)})")
        enviarQuadro(MSG_FALA_INICIAR, jsonComTerminador(corpoDeFala("Start")))          // sem resposta
    }

    /**
     * Envia G.711 A-law em pacotes de 320 bytes. Quem chama dita o ritmo: o alto-falante toca em
     * tempo real, então mande um pacote a cada 40 ms.
     */
    fun enviarFala(alaw: ByteArray) {
        val cabecalho = byteArrayOf(0x00, 0x00, 0x01, 0xFA.toByte(), 14, 2,                 // 14 = PCMA, 2 = 8000 Hz
            (BYTES_POR_PACOTE_DE_FALA and 0xFF).toByte(), (BYTES_POR_PACOTE_DE_FALA shr 8).toByte())
        var i = 0
        while (i < alaw.size) {
            val pedaco = ByteArray(BYTES_POR_PACOTE_DE_FALA) { 0xD5.toByte() }              // 0xD5 = silêncio
            alaw.copyInto(pedaco, 0, i, minOf(i + BYTES_POR_PACOTE_DE_FALA, alaw.size))
            enviarQuadro(MSG_FALA_DADOS, cabecalho + pedaco)
            i += BYTES_POR_PACOTE_DE_FALA
        }
    }

    /** Descarta o que a câmera mandou sem ser pedido (não bloqueia). */
    fun drenar() {
        try { while (soquete.ler(4096, 0.0) != null) { /* descarta */ } } catch (e: ErroDeRede) { throw ErroDvrip(e.toString()) }
    }

    companion object {
        const val PORTA_PADRAO = 34567
        /** Pacote de fala: 320 bytes de G.711 A-law a 8 kHz = 40 ms de áudio. */
        const val BYTES_POR_PACOTE_DE_FALA = 320
        const val MILISSEGUNDOS_POR_PACOTE_DE_FALA = 40

        internal const val MSG_ENTRAR = 1000
        internal const val MSG_MANTER_VIVO = 1006
        internal const val MSG_SISTEMA = 1020
        internal const val MSG_GRAVAR_CONFIG = 1040
        internal const val MSG_LER_CONFIG = 1042
        internal const val MSG_FALA_INICIAR = 1430
        internal const val MSG_FALA_DADOS = 1432
        internal const val MSG_FALA_RESERVAR = 1434
        /** 100 e 515 significam sucesso; o resto vem cru para diagnóstico. */
        internal val RETORNOS_OK = setOf<Int?>(100, 515)

        /** Conecta, entra, roda [corpo] e fecha. O jeito mais seguro de usar. */
        fun <T> executar(host: String, usuario: String, hash: String, porta: Int = PORTA_PADRAO, timeout: Double = 6.0, corpo: (ClienteDvrip) -> T): T {
            val c = ClienteDvrip(host, porta, timeout)
            c.conectar()
            try {
                c.entrar(usuario, hash)
                return corpo(c)
            } finally {
                c.fechar()
            }
        }
    }
}
