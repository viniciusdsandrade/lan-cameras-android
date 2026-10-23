package com.lancameras.android.sessao

import com.lancameras.android.apoio.Aviso
import com.lancameras.android.apoio.Depuracao
import com.lancameras.android.apoio.Preferencias
import com.lancameras.nucleo.Redact
import com.lancameras.nucleo.controle.MovimentoPtz
import com.lancameras.nucleo.modelo.ArmazenamentoDeCameras
import com.lancameras.nucleo.modelo.Cadastro
import com.lancameras.nucleo.modelo.CameraConfig
import com.lancameras.nucleo.modelo.StreamDeVideo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Normalizer

/**
 * O estado do app inteiro: câmeras guardadas, o que está na tela, as sessões abertas nas câmeras,
 * avisos e preferências. Tudo na thread principal; a rede vai para `Dispatchers.IO`.
 *
 * [argumentos]: `--abrir NOME`, `--qualidade leve|hd`, `--zoom N` abrem direto num estado (testes).
 */
class ModeloDoApp(
    val armazenamento: ArmazenamentoDeCameras,
    val plataforma: Plataforma,
    private val argumentos: List<String> = emptyList(),
    val escopo: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    /** No Android o app nasce pausado e o ciclo de vida o retoma quando a tela aparece. */
    comecarPausado: Boolean = false,
    /** Como pegar de novo os dados de uma câmera que recusou a senha ou parou de responder (os testes trocam). Bloqueia. */
    private val reidentificar: (CameraConfig) -> CameraConfig = { c ->
        Cadastro.reidentificar(c) { serie -> Cadastro.procurarNaRede(4.0, serie) { plataforma.seguraMulticast() } }
    },
) {
    sealed interface Selecao {
        data object Geral : Selecao
        data class Camera(val id: String) : Selecao
    }

    private val _cameras = MutableStateFlow<List<CameraConfig>>(emptyList())
    val cameras: StateFlow<List<CameraConfig>> = _cameras
    private val _selecao = MutableStateFlow<Selecao>(Selecao.Geral)
    val selecao: StateFlow<Selecao> = _selecao
    /** Visão geral: um fluxo leve (secundário) por câmera. */
    private val _sessoesGerais = MutableStateFlow<Map<String, SessaoDeVideo>>(emptyMap())
    val sessoesGerais: StateFlow<Map<String, SessaoDeVideo>> = _sessoesGerais
    /** Tela de uma câmera: vídeo, estado e controles. */
    private val _sessaoDaCamera = MutableStateFlow<SessaoDaCamera?>(null)
    val sessaoDaCamera: StateFlow<SessaoDaCamera?> = _sessaoDaCamera
    private val _aviso = MutableStateFlow<Aviso?>(null)
    val aviso: StateFlow<Aviso?> = _aviso
    private val _procurando = MutableStateFlow(false)
    val procurando: StateFlow<Boolean> = _procurando

    val preferencias: Preferencias get() = plataforma.preferencias

    /** Sessões soltas porque ninguém está vendo (app em segundo plano, tela apagada). */
    var pausado = comecarPausado
        private set
    private var pausaAgendada: Job? = null
    /** Zoom com que a próxima tela de câmera abre (testes). */
    var zoomInicial: Double? = null
    /** Câmeras cujas credenciais estão sendo pegas de novo (uma vez por câmera de cada vez). */
    private val renovando = mutableSetOf<String>()

    init {
        recarregarCameras()
        aplicarArgumentos()
        ajustarSessoes()
    }

    // Câmeras

    fun recarregarCameras() {
        _cameras.value = armazenamento.carregar()
    }

    fun camera(id: String): CameraConfig? = _cameras.value.firstOrNull { it.id == id }

    fun renomear(camera: CameraConfig, nome: String) {
        val limpo = nome.trim()
        if (limpo.isEmpty()) return
        armazenamento.atualizar(camera.id) { it.copy(nome = limpo) }
        recarregarCameras()
    }

    /** Põe as câmeras na ordem dada (a da visão geral). */
    fun reordenar(ids: List<String>) {
        for ((i, id) in ids.withIndex()) armazenamento.atualizar(id) { it.copy(ordem = i) }
        recarregarCameras()
    }

    // Seleção e sessões

    fun selecionar(nova: Selecao) {
        if (nova == _selecao.value) return
        _selecao.value = nova
        ajustarSessoes()
    }

    /**
     * No máximo 3 sessões RTSP abertas (a visão geral usa uma por câmera; a tela de uma câmera, uma
     * só): ao trocar de tela, o que não vale mais é fechado antes.
     */
    private fun ajustarSessoes() {
        if (pausado) return
        when (val s = _selecao.value) {
            Selecao.Geral -> {
                _sessaoDaCamera.value?.encerrar()
                _sessaoDaCamera.value = null
                val atuais = _sessoesGerais.value.toMutableMap()
                for (c in _cameras.value) if (c.id !in atuais) {
                    atuais[c.id] = SessaoDeVideo(c, StreamDeVideo.SECUNDARIO).also {
                        acompanhar(it)
                        it.iniciar()
                    }
                }
                _sessoesGerais.value = atuais
            }
            is Selecao.Camera -> {
                for (v in _sessoesGerais.value.values) v.parar()
                _sessoesGerais.value = emptyMap()
                val c = camera(s.id)
                if (c == null) {
                    _selecao.value = Selecao.Geral
                    ajustarSessoes()
                    return
                }
                if (_sessaoDaCamera.value?.camera?.id != c.id) {
                    _sessaoDaCamera.value?.encerrar()
                    val nova = SessaoDaCamera(c, this)
                    (zoomInicial ?: argumentoNumerico("--zoom"))?.let { nova.zoom.definir(it) }
                    _sessaoDaCamera.value = nova
                }
            }
        }
    }

    /** Libera todas as sessões (ninguém está vendo) sem perder a seleção. */
    fun pausar() {
        pausaAgendada?.cancel()
        if (pausado) return
        Depuracao.registrar { "pausar: soltando as sessões" }
        pausado = true
        for (v in _sessoesGerais.value.values) v.parar()
        _sessoesGerais.value = emptyMap()
        _sessaoDaCamera.value?.encerrar()
        _sessaoDaCamera.value = null
    }

    fun retomar() {
        pausaAgendada?.cancel()
        if (!pausado) return
        Depuracao.registrar { "retomar: reabrindo as sessões" }
        pausado = false
        ajustarSessoes()
    }

    fun pausarDepois(segundos: Double) {
        pausaAgendada?.cancel()
        pausaAgendada = escopo.launch {
            delay((segundos * 1000).toLong())
            pausar()
        }
    }

    fun retomarDepois(segundos: Double) {
        pausaAgendada?.cancel()
        pausaAgendada = escopo.launch {
            if (segundos > 0) delay((segundos * 1000).toLong())
            retomar()
        }
    }

    fun recomecarSessaoDaCamera() {
        if (_selecao.value !is Selecao.Camera) return
        _sessaoDaCamera.value?.encerrar()
        _sessaoDaCamera.value = null
        ajustarSessoes()
    }

    // Cadastro

    /** Acha as câmeras da rede e guarda as novas (as que já existem mantêm nome e ordem). */
    fun procurarCameras() {
        if (_procurando.value) return
        _procurando.value = true
        escopo.launch {
            try {
                if (!plataforma.pedirRedeLocal()) {
                    avisar("O app não tem acesso à rede local: permita o acesso a dispositivos próximos nas configurações.", Aviso.Tipo.ERRO)
                    return@launch
                }
                avisar("Procurando câmeras na rede…")
                val r = runCatching {
                    withContext(Dispatchers.IO) { Cadastro.procurarNaRede(4.0) { plataforma.seguraMulticast() } }
                }
                r.fold(
                    onSuccess = { guardar(it.cameras, it.falhas) },
                    onFailure = { avisar("Não consegui procurar: ${Redact.texto(it.toString())}", Aviso.Tipo.ERRO) },
                )
            } finally {
                _procurando.value = false
            }
        }
    }

    fun adicionarPorEndereco(texto: String) {
        val host = Cadastro.enderecoValido(texto)
        if (host == null) {
            avisar("Esse endereço não parece válido. Use o endereço IP da câmera: quatro números separados por pontos.", Aviso.Tipo.ERRO)
            return
        }
        if (_procurando.value) return
        _procurando.value = true
        escopo.launch {
            try {
                if (!plataforma.pedirRedeLocal()) {
                    avisar("O app não tem acesso à rede local: permita o acesso a dispositivos próximos nas configurações.", Aviso.Tipo.ERRO)
                    return@launch
                }
                val r = runCatching { withContext(Dispatchers.IO) { Cadastro.identificar(host, nome = "Câmera") } }
                r.fold(
                    onSuccess = { guardar(listOf(it), emptyList()) },
                    onFailure = { avisar("Não achei uma câmera nesse endereço: ${Redact.texto(it.toString())}", Aviso.Tipo.ERRO) },
                )
            } finally {
                _procurando.value = false
            }
        }
    }

    private fun guardar(novas: List<CameraConfig>, falhas: List<String>) {
        if (novas.isEmpty()) {
            avisar(
                if (falhas.isEmpty()) "Nenhuma câmera respondeu. Confira se ${plataforma.esteAparelho} está no mesmo Wi-Fi que elas."
                else "Achei aparelhos, mas não consegui identificá-los.",
                Aviso.Tipo.ERRO,
            )
            return
        }
        try {
            armazenamento.mesclar(novas)
        } catch (e: Exception) {
            avisar("Não consegui guardar as câmeras: ${Redact.texto(e.message ?: e.toString())}", Aviso.Tipo.ERRO)
            return
        }
        recarregarCameras()
        reiniciarSessoes()
        avisar(if (novas.size == 1) "Câmera guardada." else "Guardei ${novas.size} câmeras.", Aviso.Tipo.OK)
    }

    /** Fecha e reabre os fluxos (depois de o endereço de uma câmera mudar, por exemplo). */
    fun reiniciarSessoes() {
        for (v in _sessoesGerais.value.values) v.parar()
        _sessoesGerais.value = emptyMap()
        _sessaoDaCamera.value?.encerrar()
        _sessaoDaCamera.value = null
        ajustarSessoes()
    }

    fun pararTodas() {
        val todas = _cameras.value
        escopo.launch {
            val erros = withContext(Dispatchers.IO) { MovimentoPtz.pararTodas(todas) }
            if (erros.isEmpty()) avisar("Todas as câmeras paradas", Aviso.Tipo.OK)
            else avisar(erros.joinToString("; ") { Redact.texto(it) }, Aviso.Tipo.ERRO)
        }
    }

    // Senha ou endereço trocados

    /** Por que os dados guardados de uma câmera deixaram de servir. */
    sealed interface MotivoDaRenovacao {
        /** Ela recusou o usuário e a senha: o app do fabricante (iCSee, XMEye) pode ter trocado a senha. */
        data object SenhaRecusada : MotivoDaRenovacao

        /**
         * Ela não responde no endereço guardado: o roteador pode ter dado outro a ela, e o dela a outro
         * aparelho. O aviso se repete enquanto ela não voltar; só o primeiro vai para a tela.
         */
        data class SemResposta(val primeiraVez: Boolean) : MotivoDaRenovacao
    }

    /** Acompanha o vídeo de uma câmera: se ela recusar a senha ou parar de responder, renova os dados dela. */
    fun acompanhar(sessao: SessaoDeVideo) {
        val c = sessao.camera
        sessao.aoRecusarCredenciais = { escopo.launch { renovarDados(c, MotivoDaRenovacao.SenhaRecusada) } }
        sessao.aoNaoResponder = { primeiraVez -> escopo.launch { renovarDados(c, MotivoDaRenovacao.SemResposta(primeiraVez)) } }
    }

    /**
     * O app do fabricante pode trocar a senha de uma câmera, e o roteador, o endereço: pega os atuais
     * com a própria câmera (ou procurando na rede pelo número de série), guarda e reabre só as sessões
     * dela. Se ela devolver o mesmo que já estava guardado, não reabre nada: insistir com uma senha
     * recusada pode fazer a câmera bloquear a conta.
     */
    fun renovarDados(camera: CameraConfig, motivo: MotivoDaRenovacao) {
        if (!renovando.add(camera.id)) return
        val senha = motivo == MotivoDaRenovacao.SenhaRecusada
        // Quem segue sem responder já foi avisado: daí em diante só o que mudar aparece na tela.
        val naTela = motivo != MotivoDaRenovacao.SemResposta(primeiraVez = false)
        fun informar(texto: String, tipo: Aviso.Tipo = Aviso.Tipo.INFO) {
            if (naTela) avisar(texto, tipo) else Depuracao.registrar { texto }
        }
        informar(
            if (senha) "${camera.nome} não aceitou a senha guardada: pegando a atual com a própria câmera…"
            else "${camera.nome} não responde no endereço guardado: procurando na rede…",
        )
        escopo.launch {
            val r = runCatching { withContext(Dispatchers.IO) { reidentificar(camera) } }
            renovando.remove(camera.id)
            val nova = r.getOrElse {
                val porque = Redact.texto(it.toString())
                informar(
                    if (senha) "${camera.nome} não aceitou a senha guardada e não consegui pegar a atual: $porque"
                    else "${camera.nome} não responde e não consegui achá-la: $porque",
                    Aviso.Tipo.ERRO,
                )
                return@launch
            }
            if (nova.host == camera.host && nova.usuario == camera.usuario && nova.hash == camera.hash) {
                informar(
                    if (senha) "${camera.nome} recusou a senha que ela mesma informa. Tente de novo daqui a alguns minutos."
                    else "${camera.nome} responde no endereço guardado, mas não manda imagem. Continuo tentando.",
                    Aviso.Tipo.ERRO,
                )
                return@launch
            }
            try {
                armazenamento.atualizar(camera.id) { it.copy(host = nova.host, usuario = nova.usuario, hash = nova.hash) }
            } catch (e: Exception) {
                avisar("Não consegui guardar os dados novos de ${camera.nome}: ${Redact.texto(e.message ?: e.toString())}", Aviso.Tipo.ERRO)
                return@launch
            }
            recarregarCameras()
            reabrirSessoes(camera.id)
            avisar(
                if (nova.host == camera.host) "${camera.nome} tinha trocado de senha: já peguei a nova."
                else "${camera.nome} tinha mudado de endereço na rede: já atualizei.",
                Aviso.Tipo.OK,
            )
        }
    }

    /** Fecha e reabre só as sessões de uma câmera (os dados dela mudaram); as outras seguem como estão. */
    private fun reabrirSessoes(id: String) {
        _sessoesGerais.value[id]?.let { velha ->
            velha.parar()
            _sessoesGerais.value = _sessoesGerais.value - id
        }
        if (_sessaoDaCamera.value?.camera?.id == id) {
            _sessaoDaCamera.value?.encerrar()
            _sessaoDaCamera.value = null
        }
        ajustarSessoes()
    }

    // Avisos

    fun avisar(texto: String, tipo: Aviso.Tipo = Aviso.Tipo.INFO) {
        Depuracao.registrar { "aviso ($tipo): $texto" }
        val novo = Aviso(texto, tipo)
        _aviso.value = novo
        escopo.launch {
            delay(if (tipo == Aviso.Tipo.ERRO) 6000 else 3500)
            if (_aviso.value?.id == novo.id) _aviso.value = null
        }
    }

    fun fecharAviso() {
        _aviso.value = null
    }

    // Argumentos (para abrir direto num estado, útil em testes)

    private fun argumento(nome: String): String? {
        val i = argumentos.indexOf(nome)
        return if (i >= 0 && i + 1 < argumentos.size) argumentos[i + 1] else null
    }

    private fun argumentoNumerico(nome: String): Double? = argumento(nome)?.toDoubleOrNull()

    private fun aplicarArgumentos() {
        argumento("--qualidade")?.let { preferencias.definirQualidadeHD(it != "leve") }
        argumento("--abrir")?.let { n -> cameraPeloNome(n)?.let { _selecao.value = Selecao.Camera(it.id) } }
    }

    /** A câmera com este nome, sem ligar para acento nem caixa. */
    fun cameraPeloNome(nome: String): CameraConfig? = _cameras.value.firstOrNull { simplificar(it.nome) == simplificar(nome) }

    companion object {
        /** Sem acento nem caixa, para comparar nomes. */
        fun simplificar(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
    }
}
