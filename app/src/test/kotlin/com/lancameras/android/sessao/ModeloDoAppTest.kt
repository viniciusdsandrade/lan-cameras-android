package com.lancameras.android.sessao

import com.lancameras.android.apoio.Direcao
import com.lancameras.nucleo.modelo.ArmazenamentoDeCameras
import com.lancameras.nucleo.modelo.CameraConfig
import com.lancameras.nucleo.modelo.StreamDeVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.util.UUID

/**
 * O modelo do app: quantas sessões ficam abertas nas câmeras em cada tela e o que acontece ao pausar
 * (ninguém vendo) e retomar. As câmeras apontam para o loopback, onde nada atende: as sessões
 * existem, só não chegam a mostrar imagem.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModeloDoAppTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var pasta: File
    private lateinit var armazenamento: ArmazenamentoDeCameras
    private val modelos = mutableListOf<ModeloDoApp>()

    @Before
    fun preparar() {
        Dispatchers.setMain(dispatcher)
        pasta = File(System.getProperty("java.io.tmpdir"), "modelo-${UUID.randomUUID()}")
        armazenamento = ArmazenamentoDeCameras(pasta)
        val loopback = InetAddress.getLoopbackAddress().hostAddress
        armazenamento.mesclar((1..3).map { CameraConfig(id = "SERIE-FALSA-$it", nome = "Câmera $it", host = loopback, usuario = "fulano", hash = "x") })
    }

    @After
    fun limpar() {
        modelos.forEach { it.pausar() }
        Dispatchers.resetMain()
        pasta.deleteRecursively()
    }

    private fun modelo(argumentos: List<String> = emptyList(), plataforma: PlataformaFalsa = PlataformaFalsa(), comecarPausado: Boolean = false) =
        ModeloDoApp(armazenamento, plataforma, argumentos, TestScope(dispatcher), comecarPausado).also { modelos += it }

    @Test
    fun visaoGeralAbreUmaSessaoLevePorCameraEATelaDaCameraSoUma() {
        val m = modelo()
        assertEquals(listOf("Câmera 1", "Câmera 2", "Câmera 3"), m.cameras.value.map { it.nome })
        assertEquals(3, m.sessoesGerais.value.size)
        assertTrue(m.sessoesGerais.value.values.all { it.stream == StreamDeVideo.SECUNDARIO })
        assertNull(m.sessaoDaCamera.value)

        m.selecionar(ModeloDoApp.Selecao.Camera("SERIE-FALSA-2"))
        assertTrue("a visão geral fecha antes de a câmera abrir", m.sessoesGerais.value.isEmpty())
        assertEquals("SERIE-FALSA-2", m.sessaoDaCamera.value?.camera?.id)
        assertEquals("a tela da câmera usa o fluxo pesado por padrão", StreamDeVideo.PRINCIPAL, m.sessaoDaCamera.value?.video?.stream)

        m.selecionar(ModeloDoApp.Selecao.Geral)
        assertNull(m.sessaoDaCamera.value)
        assertEquals(3, m.sessoesGerais.value.size)
    }

    @Test
    fun pausarSoltaTudoERetomarVoltaParaOMesmoLugar() {
        val m = modelo()
        m.selecionar(ModeloDoApp.Selecao.Camera("SERIE-FALSA-3"))
        m.pausar()
        assertNull(m.sessaoDaCamera.value)
        assertTrue(m.sessoesGerais.value.isEmpty())
        assertEquals("a seleção sobrevive à pausa", ModeloDoApp.Selecao.Camera("SERIE-FALSA-3"), m.selecao.value)

        m.selecionar(ModeloDoApp.Selecao.Geral)
        assertTrue("pausado, trocar de tela não abre nada", m.sessoesGerais.value.isEmpty())

        m.retomar()
        assertEquals(3, m.sessoesGerais.value.size)
    }

    @Test
    fun noAndroidOAppNasceParadoEOCicloDeVidaORetoma() {
        val m = modelo(comecarPausado = true)
        assertTrue(m.pausado)
        assertTrue("nada aberto antes de a tela aparecer", m.sessoesGerais.value.isEmpty())
        m.retomar()
        assertEquals(3, m.sessoesGerais.value.size)
    }

    @Test
    fun pausaAgendadaCaiSeORetomarChegarAntes() = runTest(dispatcher) {
        val m = modelo()
        m.pausarDepois(1.0)
        m.retomarDepois(0.0)
        advanceTimeBy(2000)
        assertFalse("o retomar cancelou a pausa agendada", m.pausado)
        assertEquals(3, m.sessoesGerais.value.size)

        m.pausarDepois(0.5)
        advanceTimeBy(1000)
        assertTrue(m.pausado)
    }

    @Test
    fun cameraQueSumiuVoltaParaAVisaoGeral() {
        val m = modelo()
        m.selecionar(ModeloDoApp.Selecao.Camera("nao-existe"))
        assertEquals(ModeloDoApp.Selecao.Geral, m.selecao.value)
        assertEquals(3, m.sessoesGerais.value.size)
    }

    @Test
    fun argumentosAbremACameraPeloNomeSemAcentoNemCaixa() {
        val m = modelo(listOf("--abrir", "CAMERA 2", "--qualidade", "leve", "--zoom", "2.5"))
        assertEquals(ModeloDoApp.Selecao.Camera("SERIE-FALSA-2"), m.selecao.value)
        val s = m.sessaoDaCamera.value
        assertNotNull(s)
        assertEquals(StreamDeVideo.SECUNDARIO, s!!.video.stream)
        assertEquals(2.5, s.zoom.fator, 1e-9)
    }

    @Test
    fun renomearEReordenarFicamGuardados() {
        val m = modelo()
        val c = m.cameras.value[0]
        m.renomear(c, "  Entrada ")
        m.reordenar(listOf("SERIE-FALSA-3", "SERIE-FALSA-1", "SERIE-FALSA-2"))
        val outro = modelo()
        assertEquals(listOf("Câmera 3", "Entrada", "Câmera 2"), outro.cameras.value.map { it.nome })
        m.renomear(c, "   ")
        assertEquals("nome vazio é ignorado", "Entrada", m.camera(c.id)?.nome)
    }

    @Test
    fun avisoSomeSozinhoEOErroDuraMais() = runTest(dispatcher) {
        val m = modelo()
        m.avisar("oi")
        assertEquals("oi", m.aviso.value?.texto)
        advanceTimeBy(3600)
        assertNull(m.aviso.value)
        m.avisar("ruim", com.lancameras.android.apoio.Aviso.Tipo.ERRO)
        advanceTimeBy(3600)
        assertEquals("ruim", m.aviso.value?.texto)
        advanceTimeBy(3000)
        assertNull(m.aviso.value)
    }

    @Test
    fun enderecoInvalidoViraAvisoSemProcurar() {
        val m = modelo()
        m.adicionarPorEndereco("isto não é um endereço")
        assertTrue(m.aviso.value?.texto?.contains("não parece válido") == true)
        assertFalse(m.procurando.value)
    }

    @Test
    fun semPermissaoDeRedeLocalABuscaAvisaENaoSai() {
        val plataforma = PlataformaFalsa().apply { redeLocalPermitida = false }
        val m = modelo(plataforma = plataforma)
        m.procurarCameras()
        assertTrue(m.aviso.value?.texto?.contains("rede local") == true)
        assertFalse(m.procurando.value)
    }

    @Test
    fun direcoesComOPanInvertidoDoFirmware() {
        assertEquals(1.0, Direcao.ESQUERDA.vetorX, 0.0)
        assertEquals(-1.0, Direcao.DIREITA.vetorX, 0.0)
        assertEquals(1.0, Direcao.CIMA.vetorY, 0.0)
        assertEquals(-1.0, Direcao.BAIXO.vetorY, 0.0)
    }

    @Test
    fun sessaoDaCameraSegueOVolumeEAsDirecoes() {
        val plataforma = PlataformaFalsa()
        val m = modelo(plataforma = plataforma)
        m.selecionar(ModeloDoApp.Selecao.Camera("SERIE-FALSA-1"))
        val s = m.sessaoDaCamera.value!!
        assertEquals(0.3, SessaoDaCamera.ganho(30.0), 1e-9)
        assertEquals("nunca abaixo de 5%", 0.05, SessaoDaCamera.ganho(0.0), 1e-9)
        assertEquals(1.0, SessaoDaCamera.ganho(500.0), 1e-9)
        s.segurar(Direcao.CIMA)
        s.segurar(Direcao.ESQUERDA)
        assertEquals(setOf(Direcao.CIMA, Direcao.ESQUERDA), s.direcoesAtivas.value)
        s.soltar(Direcao.CIMA)
        assertEquals(setOf(Direcao.ESQUERDA), s.direcoesAtivas.value)
        s.soltarControles()
        assertTrue(s.direcoesAtivas.value.isEmpty())
        s.alternarOuvir()
        assertTrue(s.ouvindo.value)
        assertEquals(1, plataforma.escutasAtivas.get())
        s.pararAudio()
        assertFalse(s.ouvindo.value)
        assertEquals(0, plataforma.escutasAtivas.get())
        assertEquals(0, plataforma.focosAtivos.get())
    }
}
