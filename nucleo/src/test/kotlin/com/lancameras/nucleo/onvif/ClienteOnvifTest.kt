package com.lancameras.nucleo.onvif

import com.lancameras.nucleo.apoio.ServidorHttpFalso
import com.lancameras.nucleo.apoio.ServidorHttpFalso.Modo
import com.lancameras.nucleo.apoio.ServidorHttpFalso.Resposta
import com.lancameras.nucleo.apoio.loopback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

private fun envelopeSoap(corpo: String) =
    "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\" " +
        "xmlns:tds=\"d\" xmlns:trt=\"m\" xmlns:tptz=\"p\" xmlns:tt=\"t\"><s:Body>$corpo</s:Body></s:Envelope>"

class ClienteOnvifTest {
    // Valores inventados; a URL é montada por interpolação para não parecer credencial real.
    private val usuario = "fulano"
    private val senhaFalsa = "AbC123xY"

    private fun servidor(presets: String = "") = ServidorHttpFalso { req ->
        val c = req.corpo
        when {
            c.contains("GetDeviceInformation") -> Resposta(envelopeSoap(
                "<tds:GetDeviceInformationResponse><tds:Manufacturer>Fabricante</tds:Manufacturer>" +
                    "<tds:Model>Modelo-X</tds:Model><tds:FirmwareVersion>1.2.3</tds:FirmwareVersion>" +
                    "<tds:SerialNumber>SERIE-FALSA-0001</tds:SerialNumber></tds:GetDeviceInformationResponse>",
            ))
            c.contains("GetStreamUri") -> {
                val uri = "rtsp://192.0.2.10:554/user=${usuario}_password=${senhaFalsa}_channel=0_stream=0&amp;onvif=0.sdp?real_stream"
                Resposta(envelopeSoap("<trt:GetStreamUriResponse><trt:MediaUri><tt:Uri>$uri</tt:Uri></trt:MediaUri></trt:GetStreamUriResponse>"))
            }
            c.contains("GetPresets") -> Resposta(envelopeSoap("<tptz:GetPresetsResponse>$presets</tptz:GetPresetsResponse>"))
            c.contains("RemovePreset") && c.contains("PROIBIDO") -> Resposta(
                envelopeSoap("<s:Fault><s:Reason><s:Text xml:lang=\"en\">Sender not authorized</s:Text></s:Reason></s:Fault>"), status = 400,
            )
            else -> Resposta(envelopeSoap("<tptz:Resposta/>"))
        }
    }

    private fun cliente(porta: Int, assinado: Boolean = false) =
        if (assinado) ClienteOnvif(loopback, porta, usuario, senhaFalsa) else ClienteOnvif(loopback, porta)

    // Assinatura

    @Test
    fun assinaturaWsSecurityBateComOVetorCalculadoEmPython() {
        val nonce = ByteArray(16) { it.toByte() }
        val x = SegurancaOnvif.cabecalho(usuario, "senha-falsa-123", nonce, "2026-01-02T03:04:05Z")
        assertTrue(x.contains("<Password Type=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest\">2+SDPeO6FxZw1/kTxRUgO6aF5F8=</Password>"))
        assertTrue(x.contains(">AAECAwQFBgcICQoLDA0ODw==</Nonce>"))
        assertTrue(x.contains("<Created xmlns=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd\">2026-01-02T03:04:05Z</Created>"))
        assertTrue(x.contains("<Username>$usuario</Username>"))
    }

    @Test
    fun assinaturaEscapaOUsuario() {
        val x = SegurancaOnvif.cabecalho("a<b&c", "x", byteArrayOf(1), "2026-01-02T03:04:05Z")
        assertTrue(x.contains("<Username>a&lt;b&amp;c</Username>"))
    }

    @Test
    fun assinaturaDeAgoraTemInstanteSemFracoes() {
        val x = SegurancaOnvif.cabecalho(usuario, "x")
        assertTrue(x, Regex("<Created [^>]*>\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z</Created>").containsMatchIn(x))
    }

    // Identificação e credenciais

    @Test
    fun informacoesDoAparelho() = servidor().use { s ->
        val info = cliente(s.porta).informacoes()
        assertEquals(InformacaoDoAparelho("Fabricante", "Modelo-X", "1.2.3", "SERIE-FALSA-0001"), info)
        assertEquals("/onvif/device_service", s.requisicoes.first().caminho)
    }

    @Test
    fun credenciaisSaemDaUrlQueACameraEntrega() = servidor().use { s ->
        val c = cliente(s.porta).credenciais()
        assertEquals(CredenciaisDaCamera(usuario, senhaFalsa), c)
        assertEquals("/onvif/media_service", s.requisicoes.first().caminho)
        assertFalse("GetStreamUri vai sem assinatura", s.requisicoes[0].corpo.contains("UsernameToken"))
        assertFalse("o toString não mostra o hash", c.toString().contains(senhaFalsa))
    }

    // Movimento

    @Test
    fun moverMandaVelocidadeComTimeoutEAssina() = servidor().use { s ->
        cliente(s.porta, assinado = true).mover(0.4, -0.4)
        val r = s.requisicoes.last()
        assertEquals("/onvif/ptz_service", r.caminho)
        assertTrue(r.corpo, r.corpo.contains("<tt:PanTilt x=\"0.400\" y=\"-0.400\"/>"))
        assertTrue(r.corpo.contains("<tptz:Timeout>PT1S</tptz:Timeout>"))
        assertTrue(r.corpo.contains("<tptz:ProfileToken>000</tptz:ProfileToken>"))
        assertTrue("o PTZ vai assinado", r.corpo.contains("PasswordDigest"))
    }

    @Test
    fun moverNaoDependeDoSeparadorDecimalDoSistema() = servidor().use { s ->
        val padrao = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.forLanguageTag("pt-BR"))
        try {
            cliente(s.porta).mover(0.2, 0.0)
        } finally {
            java.util.Locale.setDefault(padrao)
        }
        assertTrue(s.requisicoes.last().corpo.contains("x=\"0.200\" y=\"0.000\""))
    }

    @Test
    fun pararParaPanTiltEZoom() = servidor().use { s ->
        cliente(s.porta).parar()
        val corpo = s.requisicoes.last().corpo
        assertTrue(corpo.contains("<tptz:Stop>"))
        assertTrue(corpo.contains("<tptz:PanTilt>true</tptz:PanTilt><tptz:Zoom>true</tptz:Zoom>"))
    }

    @Test
    fun movendoLeOMoveStatusDaCamera() {
        for ((texto, esperado) in listOf("MOVING" to true, "IDLE" to false)) {
            ServidorHttpFalso {
                Resposta(envelopeSoap(
                    "<tptz:GetStatusResponse><tptz:PTZStatus><tt:Position><tt:PanTilt x=\"-1.0\" y=\"-1.0\"/></tt:Position>" +
                        "<tt:MoveStatus><tt:PanTilt>$texto</tt:PanTilt><tt:Zoom>IDLE</tt:Zoom></tt:MoveStatus></tptz:PTZStatus></tptz:GetStatusResponse>",
                ))
            }.use { s -> assertEquals(texto, esperado, cliente(s.porta).movendo()) }
        }
        servidor().use { s -> assertThrows(ErroOnvif::class.java) { cliente(s.porta).movendo() } }
    }

    // Posições salvas

    @Test
    fun listaDePosicoesSalvas() = servidor(
        "<tptz:Preset token=\"1\"><tt:Name>Frente &amp; lado</tt:Name></tptz:Preset>" +
            "<tptz:Preset token=\"100\"><tt:Name>100</tt:Name></tptz:Preset>",
    ).use { s ->
        assertEquals(listOf(PresetDaCamera("1", "Frente & lado"), PresetDaCamera("100", "100")), cliente(s.porta).presets())
    }

    @Test
    fun nomeDeSalvarPresetEhEscapado() = servidor().use { s ->
        runCatching { cliente(s.porta).salvarPreset("a</tptz:PresetName><x/>") }
        val set = s.requisicoes.first { it.corpo.contains("SetPreset") }
        assertTrue(set.corpo.contains("a&lt;/tptz:PresetName&gt;&lt;x/&gt;"))
        assertFalse(set.corpo.contains("<x/>"))
    }

    @Test
    fun salvarPresetDevolveONumeroNovo() {
        val contagem = AtomicInteger()
        ServidorHttpFalso { req ->
            if (req.corpo.contains("GetPresets")) {
                val extra = if (contagem.incrementAndGet() > 1) "<tptz:Preset token=\"7\"><tt:Name>nova</tt:Name></tptz:Preset>" else ""
                Resposta(envelopeSoap("<tptz:GetPresetsResponse><tptz:Preset token=\"100\"><tt:Name>100</tt:Name></tptz:Preset>$extra</tptz:GetPresetsResponse>"))
            } else {
                Resposta(envelopeSoap("<tptz:SetPresetResponse/>"))
            }
        }.use { s -> assertEquals("7", cliente(s.porta).salvarPreset("nova")) }
    }

    // Erros

    @Test
    fun faultViraErroComOTextoDaCamera() = servidor().use { s ->
        val erro = assertThrows(ErroOnvif::class.java) { cliente(s.porta).removerPreset("PROIBIDO") }
        assertTrue(erro.toString(), erro.toString().contains("Sender not authorized"))
    }

    @Test
    fun cameraInacessivelDaErroSemEndereco() {
        val erro = assertThrows(ErroOnvif::class.java) { ClienteOnvif(loopback, 1).parar() }
        assertFalse(erro.toString(), erro.toString().contains(loopback))
    }

    // HTTP

    @Test
    fun respostaEmPedacosChunkedEAteFecharSaoRemontadas() {
        val xml = envelopeSoap(
            "<tds:GetDeviceInformationResponse><tds:SerialNumber>SERIE-FALSA-0001</tds:SerialNumber>" +
                "<tds:Model>M</tds:Model>${"<x>preenchimento</x>".repeat(20)}</tds:GetDeviceInformationResponse>",
        )
        for (modo in Modo.entries) {
            ServidorHttpFalso { Resposta(xml, modo = modo) }.use { s ->
                assertEquals(modo.name, "SERIE-FALSA-0001", cliente(s.porta).informacoes().serie)
            }
        }
    }

    @Test
    fun xmlMinimo() {
        assertEquals(listOf("x & y", "z"), XmlMinimo.valores("<a:Name>x &amp; y</a:Name><b:Name>z</b:Name>", "Name"))
        assertEquals(emptyList<String>(), XmlMinimo.valores("<tt:Rename>nao</tt:Rename>", "Name"))
        assertEquals(listOf("com atributo"), XmlMinimo.valores("<s:Text xml:lang=\"en\">com atributo</s:Text>", "Text"))
        assertEquals(listOf("certo"), XmlMinimo.valores("<tt:Name x=\"1\"/>solto<tt:Name>certo</tt:Name>", "Name"))
        assertNull(XmlMinimo.falha("<ok/>"))
        assertEquals("ruim", XmlMinimo.falha("<s:Fault><s:Text>ruim</s:Text></s:Fault>"))
        assertEquals("a<b>&\"'", XmlMinimo.desfazerEscape(XmlMinimo.escapar("a<b>&\"'")))
    }

    // Descoberta (interpretação das respostas)

    private fun respostaDeDescoberta(urn: String, ip: String) =
        "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"x\"><s:Body><d:ProbeMatches><d:ProbeMatch>" +
            "<a:EndpointReference><a:Address>$urn</a:Address></a:EndpointReference>" +
            "<d:Scopes>onvif://www.onvif.org/type/video_encoder onvif://www.onvif.org/name/Teste</d:Scopes>" +
            "<d:XAddrs>http://$ip:8899/onvif/device_service</d:XAddrs></d:ProbeMatch></d:ProbeMatches></s:Body></s:Envelope>"

    @Test
    fun interpretaUmaRespostaDeDescoberta() {
        val a = DescobertaWs.interpretar(respostaDeDescoberta("urn:uuid:aaaa", "192.0.2.10"), "192.0.2.10")!!
        assertEquals("192.0.2.10", a.host)
        assertEquals("urn:uuid:aaaa", a.identificador)
        assertEquals(listOf("http://192.0.2.10:8899/onvif/device_service"), a.enderecosDeServico)
        assertEquals(2, a.escopos.size)
        assertNull(DescobertaWs.interpretar("<ruido/>", "192.0.2.10"))
    }

    @Test
    fun mesmoAparelhoPorDoisEnderecosViraUmSo() {
        val a = DescobertaWs.interpretar(respostaDeDescoberta("urn:uuid:aaaa", "192.0.2.10"), "192.0.2.10")!!
        val b = DescobertaWs.interpretar(respostaDeDescoberta("urn:uuid:aaaa", "192.0.2.20"), "192.0.2.20")!!
        val c = DescobertaWs.interpretar(respostaDeDescoberta("urn:uuid:bbbb", "192.0.2.30"), "192.0.2.30")!!
        assertEquals(listOf("192.0.2.10", "192.0.2.30"), DescobertaWs.agrupar(listOf(a, b, c)).map { it.host })
    }

    @Test
    fun aSondaEhUmProbeDeNvt() {
        val s = DescobertaWs.sonda()
        assertTrue(s.contains("<d:Types>dn:NetworkVideoTransmitter</d:Types>"))
        assertTrue(Regex("<w:MessageID>uuid:[0-9a-f-]{36}</w:MessageID>").containsMatchIn(s))
    }
}
