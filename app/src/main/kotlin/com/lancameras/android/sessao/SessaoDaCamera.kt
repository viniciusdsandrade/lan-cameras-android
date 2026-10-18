package com.lancameras.android.sessao

import com.lancameras.android.apoio.Aviso
import com.lancameras.android.apoio.Depuracao
import com.lancameras.android.apoio.Direcao
import com.lancameras.android.video.LenteExibida
import com.lancameras.nucleo.Redact
import com.lancameras.nucleo.audio.GerenteDeFala
import com.lancameras.nucleo.audio.Som
import com.lancameras.nucleo.controle.ControleDeCamera
import com.lancameras.nucleo.controle.EstadoDaCamera
import com.lancameras.nucleo.controle.MovimentoPtz
import com.lancameras.nucleo.dvrip.ModoDaLuz
import com.lancameras.nucleo.modelo.CameraConfig
import com.lancameras.nucleo.modelo.StreamDeVideo
import com.lancameras.nucleo.onvif.PresetDaCamera
import com.lancameras.nucleo.video.FluxoDeVideo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tudo o que se faz com uma câmera aberta na tela: vídeo, estado, movimento, posições salvas, luz,
 * rastreio, áudio, fala e foto. Existe enquanto a tela da câmera estiver aberta; [encerrar] solta a
 * sessão de vídeo, o alto-falante e qualquer movimento em curso. Tudo aqui roda na thread principal;
 * o que fala com a câmera vai para `Dispatchers.IO`.
 */
class SessaoDaCamera(val camera: CameraConfig, private val modelo: ModeloDoApp) {
    private val plataforma = modelo.plataforma
    private val escopo = CoroutineScope(modelo.escopo.coroutineContext + SupervisorJob(modelo.escopo.coroutineContext[Job]))

    val video = SessaoDeVideo(camera, if (plataforma.preferencias.qualidadeHD.value) StreamDeVideo.PRINCIPAL else StreamDeVideo.SECUNDARIO, comAudio = true)
    val zoom = EstadoDeZoom()
    private val controle = ControleDeCamera(camera, modelo.armazenamento)
    private val fala = GerenteDeFala(camera)
    private val escuta = plataforma.criarEscuta()
    private var microfone: Microfone? = null
    @Volatile private var ganho = ganho(plataforma.preferencias.volume.value)
    private val movimento = MovimentoPtz(camera) { mensagem -> escopo.launch { movimentoFalhou(mensagem) } }

    private val _estado = MutableStateFlow<EstadoDaCamera?>(null)
    val estado: StateFlow<EstadoDaCamera?> = _estado
    private val _ocupadas = MutableStateFlow<Set<String>>(emptySet())
    val ocupadas: StateFlow<Set<String>> = _ocupadas
    private val _presetsDoApp = MutableStateFlow(controle.presetsCriadosPeloApp())
    val presetsDoApp: StateFlow<Set<String>> = _presetsDoApp
    private val _direcoesAtivas = MutableStateFlow<Set<Direcao>>(emptySet())
    val direcoesAtivas: StateFlow<Set<Direcao>> = _direcoesAtivas
    private val _ouvindo = MutableStateFlow(false)
    val ouvindo: StateFlow<Boolean> = _ouvindo
    private val _falandoAoVivo = MutableStateFlow(false)
    val falandoAoVivo: StateFlow<Boolean> = _falandoAoVivo
    private val _nivelDoMicrofone = MutableStateFlow(0f)
    val nivelDoMicrofone: StateFlow<Float> = _nivelDoMicrofone
    /** Valores que o usuário acabou de pedir, mostrados até a câmera confirmar. */
    private val _luzPedida = MutableStateFlow<ModoDaLuz?>(null)
    val luzPedida: StateFlow<ModoDaLuz?> = _luzPedida
    private val _rastreioPedido = MutableStateFlow<Boolean?>(null)
    val rastreioPedido: StateFlow<Boolean?> = _rastreioPedido

    private var medidor: Job? = null
    private var ouvindoAntesDeFalar = false
    /** O usuário quer falar (botão apertado), mesmo que a fala ainda esteja começando. */
    private var querFalar = false
    private var avisouDoRastreio = false
    private var encerrada = false

    init {
        val escuta = this.escuta
        video.definirOuvinteDeAudio { escuta.empurrar(it) }
        video.aoRecusarCredenciais = { modelo.escopo.launch { modelo.renovarCredenciais(camera) } }
        video.iniciar()
        escopo.launch { cicloDeEstado() }
        escopo.launch { plataforma.preferencias.volume.collect { ganho = ganho(it) } }
    }

    // Ciclo de vida

    fun encerrar() {
        if (encerrada) return
        encerrada = true
        pararMovimento()
        querFalar = false
        microfone?.parar()
        microfone = null
        _falandoAoVivo.value = false
        fala.parar()
        if (_ouvindo.value) {
            escuta.parar()
            plataforma.soltarFocoDeAudio()
        }
        _ouvindo.value = false
        video.definirOuvinteDeAudio(null)
        video.parar()
        movimento.encerrar()
        escopo.coroutineContext[Job]?.cancel()
    }

    /**
     * Ninguém mais está segurando nada (o app perdeu o foco, chegou uma ligação): para o movimento e
     * a fala ao vivo, mas mantém o vídeo.
     */
    fun soltarControles() {
        pararMovimento()
        pararFalaAoVivo()
    }

    /** O sistema tomou o áudio (ligação, outro app tocando): para de ouvir e de falar. */
    fun pararAudio() {
        pararFalaAoVivo()
        if (_ouvindo.value) {
            escuta.parar()
            plataforma.soltarFocoDeAudio()
            _ouvindo.value = false
        }
    }

    // Estado (somente leitura)

    private suspend fun cicloDeEstado() {
        atualizarEstado()
        while (escopo.isActive) {
            delay(10_000)
            atualizarEstado()
        }
    }

    suspend fun atualizarEstado() {
        val novo = withContext(Dispatchers.IO) { controle.lerEstado() }
        if (encerrada) return
        _estado.value = novo
        _presetsDoApp.value = controle.presetsCriadosPeloApp()
        if ("luz" !in _ocupadas.value) _luzPedida.value = null
        if ("rastreio" !in _ocupadas.value) _rastreioPedido.value = null
    }

    /** Roda uma operação que fala com a câmera, sem travar a tela; mostra o erro, se houver, e relê o estado. */
    private fun agir(chave: String, sucesso: String? = null, corpo: () -> Unit) {
        if (chave in _ocupadas.value) return
        _ocupadas.update { it + chave }
        escopo.launch {
            try {
                withContext(Dispatchers.IO) { corpo() }
                if (sucesso != null) modelo.avisar(sucesso, Aviso.Tipo.OK)
            } catch (e: Exception) {
                modelo.avisar(Redact.texto(e.toString()), Aviso.Tipo.ERRO)
                _luzPedida.value = null
                _rastreioPedido.value = null
            }
            _ocupadas.update { it - chave }
            atualizarEstado()
        }
    }

    // Movimento

    fun segurar(d: Direcao) = atualizarDirecoes(_direcoesAtivas.value + d)
    fun soltar(d: Direcao) = atualizarDirecoes(_direcoesAtivas.value - d)

    fun atualizarDirecoes(novas: Set<Direcao>) {
        _direcoesAtivas.value = novas
        if (novas.isEmpty()) {
            movimento.parar()
            return
        }
        val v = plataforma.preferencias.velocidade.value
        val x = novas.sumOf { it.vetorX }.coerceIn(-1.0, 1.0) * v
        val y = novas.sumOf { it.vetorY }.coerceIn(-1.0, 1.0) * v
        val e = _estado.value
        if (e?.rastreioLigado == true && !avisouDoRastreio) {
            avisouDoRastreio = true
            val s = e.rastreioVoltaEmSegundos?.let { "$it s" } ?: "alguns segundos"
            modelo.avisar("Rastreio ligado: a câmera volta sozinha ao ponto de vigia depois de $s.")
        }
        movimento.iniciar(x, y)
    }

    fun pararMovimento() {
        _direcoesAtivas.value = emptySet()
        movimento.parar()
    }

    private fun movimentoFalhou(mensagem: String) {
        _direcoesAtivas.value = emptySet()
        modelo.avisar(Redact.texto(mensagem), Aviso.Tipo.ERRO)
    }

    // Posições salvas

    fun irPara(p: PresetDaCamera) = agir("preset-${p.token}", "Indo para ${p.nome}") { controle.irParaPreset(p.token) }

    fun salvarPosicao(nome: String) = agir("salvar-preset", "Posição salva") { controle.salvarPreset(nome) }

    fun apagar(p: PresetDaCamera) = agir("apagar-${p.token}", "Posição apagada") { controle.apagarPreset(p.token) }

    // Luz e rastreio

    fun definirLuz(modo: ModoDaLuz) {
        if (modo == (_luzPedida.value ?: _estado.value?.luz)) return
        _luzPedida.value = modo
        agir("luz", "Luz: ${nome(modo)}") { controle.definirLuz(modo) }
    }

    fun definirRastreio(ligado: Boolean) {
        if (ligado == (_rastreioPedido.value ?: _estado.value?.rastreioLigado)) return
        _rastreioPedido.value = ligado
        agir("rastreio", if (ligado) "Rastreio ligado" else "Rastreio desligado") { controle.definirRastreio(ligado) }
    }

    // Áudio

    fun alternarOuvir() {
        if (_ouvindo.value) {
            escuta.parar()
            plataforma.soltarFocoDeAudio()
            _ouvindo.value = false
            Depuracao.registrar { "${camera.nome}: ouvir desligado" }
            return
        }
        try {
            if (!plataforma.pedirFocoDeAudio { escopo.launch { pararAudio() } }) throw FalhaDaPlataforma("outro app está com o áudio")
            escuta.iniciar()
            _ouvindo.value = true
            Depuracao.registrar { "${camera.nome}: ouvir ligado" }
        } catch (e: Exception) {
            plataforma.soltarFocoDeAudio()
            modelo.avisar("Não consegui ligar o som: ${Redact.texto(e.message ?: e.toString())}", Aviso.Tipo.ERRO)
        }
    }

    fun tocar(som: Som) {
        val g = ganho
        Depuracao.registrar { "${camera.nome}: tocar ${som.rotulo} com volume ${(g * 100).toInt()}%" }
        agir("som", "${som.rotulo} tocando em ${camera.nome}") { fala.tocar(som.alaw, g) }
    }

    fun falarTexto(texto: String) {
        if (texto.isBlank()) return
        val g = ganho
        agir("texto", "Falando em ${camera.nome}") {
            val alaw = plataforma.vozParaAlaw(texto)
            fala.tocar(alaw, g)
        }
    }

    fun pararSom() {
        fala.parar()
        modelo.avisar("Som parado")
    }

    /**
     * Começa a fala ao vivo (microfone do aparelho para o alto-falante da câmera). Chamada ao apertar
     * "segurar para falar": se o botão for solto antes de a câmera aceitar, a fala é desfeita assim que começa.
     */
    fun iniciarFalaAoVivo() {
        if (querFalar || _falandoAoVivo.value || encerrada) return
        querFalar = true
        if ("fala-ao-vivo" in _ocupadas.value) return
        _ocupadas.update { it + "fala-ao-vivo" }
        escopo.launch {
            try {
                if (!plataforma.pedirMicrofone()) {
                    querFalar = false
                    modelo.avisar("O app não tem acesso ao microfone: permita nas configurações do aparelho.", Aviso.Tipo.ERRO)
                    return@launch
                }
                if (!querFalar || encerrada) return@launch                  // soltou antes de começar
                withContext(Dispatchers.IO) { fala.iniciarAoVivo() }
                if (!querFalar || encerrada) {
                    fala.pararAoVivo()
                    return@launch
                }
                val g = fala
                val mic = plataforma.criarMicrofone { bytes, n -> g.enviarAoVivo(bytes, ganho, n) }
                mic.iniciar()
                microfone = mic
                // meio-duplex: o que a câmera ouve e o que ela fala não podem se misturar
                ouvindoAntesDeFalar = _ouvindo.value
                if (_ouvindo.value) {
                    escuta.parar()
                    _ouvindo.value = false
                }
                _falandoAoVivo.value = true
                Depuracao.registrar { "${camera.nome}: fala ao vivo começou${if (ouvindoAntesDeFalar) " (ouvir silenciado)" else ""}" }
                iniciarMedidor()
            } catch (e: Exception) {
                querFalar = false
                fala.pararAoVivo()
                modelo.avisar(Redact.texto(e.toString()), Aviso.Tipo.ERRO)
            } finally {
                _ocupadas.update { it - "fala-ao-vivo" }
            }
        }
    }

    fun pararFalaAoVivo() {
        querFalar = false
        if (!_falandoAoVivo.value) return
        microfone?.parar()
        microfone = null
        fala.pararAoVivo()
        _falandoAoVivo.value = false
        Depuracao.registrar { "${camera.nome}: fala ao vivo terminou" }
        medidor?.cancel()
        _nivelDoMicrofone.value = 0f
        if (ouvindoAntesDeFalar && !encerrada) alternarOuvir()
        ouvindoAntesDeFalar = false
    }

    private fun iniciarMedidor() {
        medidor?.cancel()
        medidor = escopo.launch {
            while (isActive) {
                delay(80)
                val mic = microfone ?: break
                _nivelDoMicrofone.value = mic.nivel
            }
        }
    }

    // Foto

    fun tirarFoto() {
        val capturar = video.capturador
        if (capturar == null || video.estado.value !is FluxoDeVideo.Estado.AoVivo) {
            modelo.avisar("Ainda não chegou imagem desta câmera.", Aviso.Tipo.ERRO)
            return
        }
        if ("foto" in _ocupadas.value) return
        _ocupadas.update { it + "foto" }
        escopo.launch {
            try {
                val jpeg = capturar(if (camera.duasLentes) LenteExibida.MOVEL else LenteExibida.INTEIRA)
                if (jpeg == null) {
                    modelo.avisar("Não consegui gerar a foto.", Aviso.Tipo.ERRO)
                    return@launch
                }
                val guardada = plataforma.guardarFoto(jpeg, camera.nome)
                modelo.avisar("Foto salva em ${guardada.onde}", Aviso.Tipo.OK)
            } catch (e: Exception) {
                modelo.avisar("Não consegui salvar a foto: ${Redact.texto(e.toString())}", Aviso.Tipo.ERRO)
            } finally {
                _ocupadas.update { it - "foto" }
            }
        }
    }

    companion object {
        fun ganho(volume: Double): Double = (volume / 100).coerceIn(0.05, 1.0)

        fun nome(modo: ModoDaLuz): String = when (modo) {
            ModoDaLuz.AUTOMATICO -> "automática"
            ModoDaLuz.LIGADA -> "ligada"
            ModoDaLuz.DESLIGADA -> "desligada"
        }
    }
}
