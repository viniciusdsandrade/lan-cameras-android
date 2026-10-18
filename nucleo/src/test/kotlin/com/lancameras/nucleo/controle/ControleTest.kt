package com.lancameras.nucleo.controle

import com.lancameras.nucleo.apoio.ServidorDvripFalso
import com.lancameras.nucleo.apoio.ServidorHttpFalso
import com.lancameras.nucleo.apoio.ServidorHttpFalso.Resposta
import com.lancameras.nucleo.apoio.esperar
import com.lancameras.nucleo.apoio.loopback
import com.lancameras.nucleo.audio.ALaw
import com.lancameras.nucleo.audio.FaladorDaCamera
import com.lancameras.nucleo.audio.GerenteDeFala
import com.lancameras.nucleo.dvrip.ModoDaLuz
import com.lancameras.nucleo.modelo.ArmazenamentoDeCameras
import com.lancameras.nucleo.modelo.CameraConfig
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class ControleTest {
    private fun camera(id: String = "SERIE-FALSA-0001") =
        CameraConfig(id = id, nome = "Teste", host = loopback, usuario = "fulano", hash = "AbC123xY")

    private val pastas = mutableListOf<File>()

    @After
    fun limpar() = pastas.forEach { it.deleteRecursively() }

    private fun armazenamento() =
        ArmazenamentoDeCameras(File(System.getProperty("java.io.tmpdir"), "controle-teste-${UUID.randomUUID()}").also { pastas += it })

    // Fala (ritmo, fila e cancelamento)

    private fun servidorDeFala(recusar: Boolean = false) = ServidorDvripFalso { id, _ ->
        when (id) {
            1000 -> buildJsonObject { put("Ret", 100); put("SessionID", "0x00000042") }
            1434 -> buildJsonObject { put("Ret", if (recusar) 205 else 100) }
            else -> null
        }
    }

    private fun pacotesDeFala(s: ServidorDvripFalso) = s.quadros.filter { it.id == 1432 }

    @Test
    fun falaSaiNoRitmoDoAltoFalanteENaoDeUmaVez() = servidorDeFala().use { s ->
        val f = FaladorDaCamera(camera(), s.porta)
        f.enfileirar(ByteArray(320 * 25) { 0x55 })                  // 1 s de áudio
        f.iniciar()
        assertTrue(f.esperarPronto())
        f.encerrar()
        assertTrue("chegaram ${pacotesDeFala(s).size} pacotes", esperar(5.0) { pacotesDeFala(s).size == 25 })
        val t = pacotesDeFala(s).map { it.instante }
        val duracao = (t.last() - t.first()) / 1e9
        assertTrue("1 s de áudio chegou em $duracao s: rápido demais, estoura o buffer da câmera", duracao > 0.75)
        assertTrue("chegou em $duracao s: lento demais, a voz picota", duracao < 1.4)
        assertEquals(listOf(1434, 1430), s.quadros.map { it.id }.filter { it == 1434 || it == 1430 })
        assertTrue("a sessão deveria terminar depois de tocar tudo", esperar(3.0) { !f.ativo })
        assertNull(f.erro)
    }

    @Test
    fun cancelarParaNaHoraENaoTocaOResto() = servidorDeFala().use { s ->
        val f = FaladorDaCamera(camera(), s.porta)
        f.enfileirar(ByteArray(320 * 100) { 0x55 })                 // 4 s
        f.iniciar()
        assertTrue(f.esperarPronto())
        Thread.sleep(300)
        f.cancelar()
        assertTrue(esperar(3.0) { !f.ativo })
        val n = pacotesDeFala(s).size
        assertTrue("cancelou aos 0,3 s mas $n pacotes saíram", n < 25)
        Thread.sleep(300)
        assertEquals("nada deve sair depois de cancelar", n, pacotesDeFala(s).size)
    }

    @Test
    fun modoAoVivoDescartaOMaisVelhoQuandoAFilaPassaDoLimite() = servidorDeFala().use { s ->
        val f = FaladorDaCamera(camera(), s.porta, aoVivo = true)
        f.enfileirar(ByteArray(320 * 100) { 0x55 })
        f.iniciar()
        assertTrue(f.esperarPronto())
        f.encerrar()
        assertTrue(esperar(5.0) { !f.ativo })
        assertTrue(pacotesDeFala(s).size <= FaladorDaCamera.MAXIMO_DA_FILA_AO_VIVO + 1)
    }

    @Test
    fun pacoteCurtoEhCompletadoComSilencio() = servidorDeFala().use { s ->
        val f = FaladorDaCamera(camera(), s.porta)
        f.enfileirar(ByteArray(100) { 0x55 })
        f.iniciar()
        assertTrue(f.esperarPronto())
        f.encerrar()
        assertTrue(esperar(3.0) { pacotesDeFala(s).size == 1 })
        val corpo = pacotesDeFala(s)[0].corpo
        assertEquals(8 + 320, corpo.size)
        assertTrue(corpo.takeLast(220).all { it == ALaw.SILENCIO })
    }

    @Test
    fun cameraQueRecusaAFalaDizPorQue() = servidorDeFala(recusar = true).use { s ->
        val f = FaladorDaCamera(camera(), s.porta)
        f.iniciar()
        assertFalse(f.esperarPronto())
        assertTrue(esperar(3.0) { !f.ativo })
        val erro = f.erro!!
        assertTrue(erro, erro.contains("recusou"))
        assertFalse(erro.contains("AbC123xY"))
    }

    // Gerente de fala

    @Test
    fun tocarSomEntregaOAudioEDeixaDeFalarAoTerminar() = servidorDeFala().use { s ->
        val g = GerenteDeFala(camera(), s.porta)
        g.tocar(ByteArray(320 * 3) { 0x55 }, 1.0)
        assertTrue(esperar(3.0) { pacotesDeFala(s).size == 3 })
        assertTrue("ao terminar a fila a sessão se encerra sozinha", esperar(3.0) { !g.falando })
    }

    @Test
    fun comecarOutraFalaCancelaAAnterior() = servidorDeFala().use { s ->
        val g = GerenteDeFala(camera(), s.porta)
        g.tocar(ByteArray(320 * 100) { 0x55 }, 1.0)                  // 4 s
        Thread.sleep(200)
        g.tocar(ByteArray(320 * 5) { 0x66 }, 1.0)                    // chega antes da primeira acabar
        assertTrue(esperar(5.0) { !g.falando })
        val dados = pacotesDeFala(s).map { it.corpo }
        assertTrue("a primeira fala (100 pacotes) deveria ter sido cortada: ${dados.size}", dados.size < 30)
        assertEquals("a segunda inteira", 5, dados.count { it[8] == 0x66.toByte() })
        assertEquals("uma conexão por fala", 2, s.quadros.count { it.id == 1000 })
    }

    @Test
    fun falaAoVivoEnviaOQueChegaDoMicrofoneEPara() = servidorDeFala().use { s ->
        val g = GerenteDeFala(camera(), s.porta)
        g.iniciarAoVivo()
        g.enviarAoVivo(ByteArray(640), 1.0)                          // 320 amostras = 1 pacote
        g.enviarAoVivo(ByteArray(640), 1.0)
        assertTrue(esperar(3.0) { pacotesDeFala(s).size == 2 })
        g.pararAoVivo()
        assertTrue(esperar(3.0) { !g.falando })
        g.enviarAoVivo(ByteArray(640), 1.0)                          // depois de parar, nada sai
        Thread.sleep(300)
        assertEquals(2, pacotesDeFala(s).size)
    }

    @Test
    fun recusaDaCameraViraErroComMotivo() = servidorDeFala(recusar = true).use { s ->
        val g = GerenteDeFala(camera(), s.porta)
        val e = assertThrows(ErroDeControle::class.java) { g.tocar(byteArrayOf(ALaw.SILENCIO), 1.0) }
        assertTrue(e.toString(), e.toString().contains("recusou"))
        assertThrows(ErroDeControle::class.java) { g.iniciarAoVivo() }
        Unit
    }

    // Movimento contínuo

    private fun servidorOnvif() = ServidorHttpFalso { Resposta("<s:Envelope xmlns:s=\"x\"><s:Body><ok/></s:Body></s:Envelope>") }

    private fun contar(s: ServidorHttpFalso, trecho: String) = s.requisicoes.count { it.corpo.contains(trecho) }

    @Test
    fun segurarReenviaOMovimentoESoltarParaDuasVezes() = servidorOnvif().use { s ->
        val m = MovimentoPtz(camera(), s.porta)
        m.iniciar(0.4, 0.0)
        Thread.sleep(1000)
        val enquantoSegura = contar(s, "ContinuousMove")
        assertTrue("segurando por 1 s deveria reenviar a cada 0,3 s: $enquantoSegura", enquantoSegura in 3..5)
        assertTrue(m.emMovimento)
        m.parar()
        assertTrue("soltar manda Stop duas vezes", esperar(3.0) { contar(s, "<tptz:Stop>") == 2 })
        assertFalse(m.emMovimento)
        val movimentos = contar(s, "ContinuousMove")
        Thread.sleep(800)
        assertEquals("depois de soltar não pode haver mais movimento", movimentos, contar(s, "ContinuousMove"))
        assertEquals(2, contar(s, "<tptz:Stop>"))
        m.encerrar()
    }

    @Test
    fun trocarDeDirecaoSemSoltarUsaOVetorNovo() = servidorOnvif().use { s ->
        val m = MovimentoPtz(camera(), s.porta)
        m.iniciar(0.4, 0.0)
        m.iniciar(0.0, -0.7)
        Thread.sleep(700)
        m.parar()
        assertTrue(esperar(3.0) { contar(s, "<tptz:Stop>") == 2 })
        val ultimo = s.requisicoes.last { it.corpo.contains("ContinuousMove") }
        assertTrue(ultimo.corpo, ultimo.corpo.contains("x=\"0.000\" y=\"-0.700\""))
        m.encerrar()
    }

    @Test
    fun falhaDeRedeEncerraOMovimentoEAvisa() {
        val avisos = AtomicInteger()
        val m = MovimentoPtz(camera(), 1) { avisos.incrementAndGet() }          // porta 1: ninguém escuta
        m.iniciar(0.4, 0.0)
        assertTrue(esperar(3.0) { avisos.get() >= 1 })
        assertFalse(m.emMovimento)
        Thread.sleep(500)
        assertEquals("avisa uma vez e para de tentar", 1, avisos.get())
        m.encerrar()
    }

    @Test
    fun pararTodasRelataOErroDeCadaCamera() {
        val a = CameraConfig(id = "1", nome = "A", host = loopback, usuario = "u", hash = "h")
        val b = CameraConfig(id = "2", nome = "B", host = loopback, usuario = "u", hash = "h")
        val erros = MovimentoPtz.pararTodas(listOf(a, b), portaOnvif = 1)
        assertEquals(2, erros.size)
        assertEquals(setOf("A:", "B:"), erros.map { it.take(2) }.toSet())
    }

    // Controle de câmera

    @Test
    fun apagarSoApagaOQueOAppCriou() {
        val arm = armazenamento()
        servidorOnvif().use { s ->
            val c = ControleDeCamera(camera(), arm, portaOnvif = s.porta)
            val e = assertThrows(ErroDeControle::class.java) { c.apagarPreset("100") }
            assertTrue(e.toString(), e.toString().contains("só apago"))
            assertEquals("nem chega a falar com a câmera", 0, s.requisicoes.size)
            arm.registrarPreset("7", camera().id)
            c.apagarPreset("7")
            assertTrue(s.requisicoes.any { it.corpo.contains("RemovePreset") && it.corpo.contains(">7<") })
            assertEquals(emptySet<String>(), arm.presetsDoApp(camera().id))
        }
    }

    @Test
    fun salvarPresetRegistraONumeroENomeVazioEhRecusado() {
        val arm = armazenamento()
        val chamadas = AtomicInteger()
        ServidorHttpFalso { req ->
            if (req.corpo.contains("GetPresets")) {
                val novo = if (chamadas.incrementAndGet() > 1) "<tptz:Preset token=\"9\"><tt:Name>porta</tt:Name></tptz:Preset>" else ""
                Resposta("<s:Envelope xmlns:s=\"x\"><s:Body><tptz:GetPresetsResponse xmlns:tptz=\"p\" xmlns:tt=\"t\">$novo</tptz:GetPresetsResponse></s:Body></s:Envelope>")
            } else {
                Resposta("<s:Envelope xmlns:s=\"x\"><s:Body><ok/></s:Body></s:Envelope>")
            }
        }.use { s ->
            val c = ControleDeCamera(camera(), arm, portaOnvif = s.porta)
            assertThrows(ErroDeControle::class.java) { c.salvarPreset("   ") }
            assertEquals("9", c.salvarPreset("  porta\n "))
            assertEquals(setOf("9"), c.presetsCriadosPeloApp())
            val set = s.requisicoes.first { it.corpo.contains("SetPreset") }
            assertTrue(set.corpo, set.corpo.contains("<tptz:PresetName>porta</tptz:PresetName>"))
        }
    }

    @Test
    fun nomeDaPosicaoEhLimpoECortadoEm24() {
        assertEquals("Vista da rua", ControleDeCamera.limparNome("  Vista da rua \n"))
        assertEquals("Vistada rua", ControleDeCamera.limparNome("Vista\tda rua"))
        assertEquals(24, ControleDeCamera.limparNome("x".repeat(40)).length)
        assertEquals("emoji inteiro", "a".repeat(23) + "😀", ControleDeCamera.limparNome("a".repeat(23) + "😀" + "bbb"))
        assertEquals("", ControleDeCamera.limparNome("\n\t "))
    }

    @Test
    fun lerEstadoNaoMudaNadaEToleraFalhaParcial() {
        val dvrip = ServidorDvripFalso { id, corpo ->
            val nome = (corpo["Name"] as? JsonPrimitive)?.contentOrNull ?: ""
            when (id) {
                1000 -> buildJsonObject { put("Ret", 100); put("SessionID", "0x00000042") }
                1042 -> when (nome) {
                    "Camera.WhiteLight" -> buildJsonObject { put("Ret", 100); putJsonObject(nome) { put("WorkMode", "Close") } }
                    "Detect.DetectTrack" -> buildJsonObject { put("Ret", 100); putJsonObject(nome) { put("Enable", true); put("ReturnTime", 5) } }
                    else -> buildJsonObject { put("Ret", 607) }
                }
                1020 -> buildJsonObject { put("Ret", 100); putJsonObject("WifiRouteInfo") { put("SignalLevel", 67) } }
                else -> null
            }
        }
        dvrip.use {
            // ONVIF fora do ar (porta 1): o DVRIP ainda responde
            val c = ControleDeCamera(camera(), armazenamento(), portaOnvif = 1, portaDvrip = dvrip.porta)
            val e = c.lerEstado()
            assertEquals(ModoDaLuz.DESLIGADA, e.luz)
            assertEquals(true, e.rastreioLigado)
            assertEquals(5, e.rastreioVoltaEmSegundos)
            assertEquals(67, e.sinalDoWiFi)
            assertTrue(e.presets.isEmpty())
            assertEquals(1, e.avisos.size)
            assertFalse("ler o estado nunca grava nada", dvrip.quadros.any { it.id == 1040 })
        }
    }
}
