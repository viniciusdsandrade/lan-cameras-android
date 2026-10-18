package com.lancameras.nucleo.dvrip

import com.lancameras.nucleo.apoio.ServidorDvripFalso
import com.lancameras.nucleo.apoio.esperar
import com.lancameras.nucleo.apoio.loopback
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ClienteDvripTest {
    /** As configurações da câmera falsa. */
    private class Estado(vararg inicial: Pair<String, JsonObject>) {
        private val config = mutableMapOf(*inicial)
        @Volatile var aceitaGravacao = true
        fun ler(n: String) = synchronized(config) { config[n] }
        fun gravar(n: String, v: JsonObject) = synchronized(config) { if (aceitaGravacao) config[n] = v }
    }

    private fun texto(o: JsonObject, chave: String) = (o[chave] as? JsonPrimitive)?.contentOrNull

    private fun servidor(estado: Estado, senhaCerta: String = "AbC123xY") = ServidorDvripFalso { id, corpo ->
        when (id) {
            1000 -> if (texto(corpo, "PassWord") == senhaCerta) {
                buildJsonObject { put("Ret", 100); put("SessionID", "0x00000042") }
            } else {
                buildJsonObject { put("Ret", 205) }
            }
            1042 -> {
                val nome = texto(corpo, "Name") ?: ""
                val v = estado.ler(nome)
                if (v == null) buildJsonObject { put("Ret", 607) } else buildJsonObject { put("Ret", 100); put("Name", nome); put(nome, v) }
            }
            1040 -> {
                val nome = texto(corpo, "Name") ?: ""
                (corpo[nome] as? JsonObject)?.let { estado.gravar(nome, it) }
                buildJsonObject { put("Ret", 100) }
            }
            1434 -> buildJsonObject { put("Ret", 100) }
            1020 -> buildJsonObject { put("Ret", 100); putJsonObject("WifiRouteInfo") { put("SignalLevel", 68); put("WlanStatus", true) } }
            else -> null
        }
    }

    private fun entrar(s: ServidorDvripFalso, hash: String = "AbC123xY") =
        ClienteDvrip(loopback, s.porta, 3.0).also { it.conectar(); it.entrar("fulano", hash) }

    private fun json(corpo: ByteArray) = Json.parseToJsonElement(String(corpo, 0, corpo.size - 2, Charsets.UTF_8)) as JsonObject

    private fun luz(modo: String, outro: Int? = null) = buildJsonObject { put("WorkMode", modo); outro?.let { put("Outro", it) } }

    @Test
    fun loginMandaOsCamposEDevolveASessao() = servidor(Estado()).use { s ->
        val c = entrar(s)
        try {
            val login = s.quadros.first()
            assertEquals(1000, login.id)
            val j = json(login.corpo)
            assertEquals("DVRIP-Web", texto(j, "LoginType"))
            assertEquals("MD5", texto(j, "EncryptType"))
            assertEquals("o corpo termina em 0A 00", listOf<Byte>(0x0A, 0x00), login.corpo.takeLast(2))
            c.sinalDoWiFi()
            assertEquals("os pedidos seguintes levam a sessão no cabeçalho", 0x42L, s.quadros.last().sessao)
        } finally {
            c.fechar()
        }
    }

    @Test
    fun loginRecusadoNaoVazaACredencial() = servidor(Estado()).use { s ->
        val c = ClienteDvrip(loopback, s.porta, 3.0)
        c.conectar()
        try {
            val erro = assertThrows(ErroDvrip::class.java) { c.entrar("fulano", "errada") }
            assertTrue(erro.toString(), erro.toString().contains("recusou"))
            assertFalse(erro.toString().contains("errada"))
        } finally {
            c.fechar()
        }
    }

    @Test
    fun definirLuzGravaEConfirmaLendoDeVolta() {
        val estado = Estado("Camera.WhiteLight" to luz("Intelligent", outro = 7))
        servidor(estado).use { s ->
            val c = entrar(s)
            try {
                assertEquals(ModoDaLuz.AUTOMATICO, c.modoDaLuz())
                c.definirLuz(ModoDaLuz.LIGADA)
                assertEquals(ModoDaLuz.LIGADA, c.modoDaLuz())
                assertEquals("os outros campos são preservados", 7, (estado.ler("Camera.WhiteLight")!!["Outro"] as JsonPrimitive).intOrNull)
                val gravacao = json(s.quadros.first { it.id == 1040 }.corpo)
                assertEquals("KeepOpen", texto(gravacao["Camera.WhiteLight"] as JsonObject, "WorkMode"))
            } finally {
                c.fechar()
            }
        }
    }

    @Test
    fun luzQueACameraAceitaMasNaoMudouEhDetectada() {
        val estado = Estado("Camera.WhiteLight" to luz("Close")).apply { aceitaGravacao = false }
        servidor(estado).use { s ->
            val c = entrar(s)
            try {
                val erro = assertThrows(ErroDvrip::class.java) { c.definirLuz(ModoDaLuz.LIGADA) }
                assertTrue(erro.toString(), erro.toString().contains("não mudou"))
            } finally {
                c.fechar()
            }
        }
    }

    @Test
    fun rastreioLigaEDesliga() {
        val estado = Estado("Detect.DetectTrack" to buildJsonObject { put("Enable", false); put("ReturnTime", 300) })
        servidor(estado).use { s ->
            val c = entrar(s)
            try {
                assertEquals(EstadoDoRastreio(false, 300), c.rastreio())
                c.definirRastreio(true)
                assertTrue(c.rastreio().ligado)
                c.definirRastreio(false)
                assertFalse(c.rastreio().ligado)
            } finally {
                c.fechar()
            }
        }
    }

    @Test
    fun configuracaoInexistenteDaErro() {
        servidor(Estado()).use { s ->
            val c = entrar(s)
            try { assertThrows(ErroDvrip::class.java) { c.configuracao("Nao.Existe") } } finally { c.fechar() }
        }
    }

    @Test
    fun sinalDoWiFi() = servidor(Estado()).use { s ->
        val c = entrar(s)
        try { assertEquals(68, c.sinalDoWiFi()) } finally { c.fechar() }
    }

    @Test
    fun falaReservaOAltoFalanteEEnviaPacotesDe320Bytes() = servidor(Estado()).use { s ->
        val c = entrar(s)
        try {
            c.iniciarFala()
            c.enviarFala(ByteArray(1000) { 0x55 })                  // 3 inteiros + 1 de 40 bytes completado
            assertTrue(esperar(3.0) { s.quadros.count { it.id == 1432 } == 4 })
            assertEquals("reserva e depois começa", listOf(1434, 1430), s.quadros.map { it.id }.filter { it == 1434 || it == 1430 })
            val dados = s.quadros.filter { it.id == 1432 }.map { it.corpo }
            for (d in dados) {
                assertEquals(8 + 320, d.size)
                assertEquals(listOf(0x00, 0x00, 0x01, 0xFA, 14, 2, 0x40, 0x01), d.take(8).map { it.toInt() and 0xFF })
            }
            assertTrue("o resto do último pacote é silêncio", dados[3].takeLast(280).all { it == 0xD5.toByte() })
            assertTrue(dados[3].drop(8).take(40).all { it == 0x55.toByte() })
        } finally {
            c.fechar()
        }
    }

    @Test
    fun manterVivoNaoEsperaResposta() = servidor(Estado()).use { s ->
        val c = entrar(s)
        try {
            c.manterVivo()
            assertTrue(esperar(3.0) { s.quadros.any { it.id == 1006 } })
        } finally {
            c.fechar()
        }
    }

    @Test
    fun executarAbreEntraEFecha() {
        servidor(Estado("Detect.DetectTrack" to buildJsonObject { put("Enable", true); put("ReturnTime", 5) })).use { s ->
            assertEquals(1, ClienteDvrip.executar(loopback, "fulano", "AbC123xY", porta = s.porta) { 1 })
            assertEquals(1000, s.quadros.first().id)
        }
    }

    @Test
    fun cameraInacessivelDaErroSemEndereco() {
        val erro = assertThrows(ErroDvrip::class.java) { ClienteDvrip(loopback, 1, 2.0).conectar() }
        assertFalse(erro.toString(), erro.toString().contains(loopback))
    }
}
