package com.lancameras.nucleo.video

import com.lancameras.nucleo.Redact
import com.lancameras.nucleo.rede.ErroDeRede
import com.lancameras.nucleo.rtsp.ClienteRtsp
import com.lancameras.nucleo.rtsp.ErroRtsp
import com.lancameras.nucleo.rtsp.PacoteRtp
import com.lancameras.nucleo.rtsp.Sdp
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Quem transforma os quadros em imagem: no app, o MediaCodec; nos testes, um falso. */
interface Decodificador {
    /** Antes do primeiro quadro e sempre que os parâmetros mudam. */
    fun configurar(parametros: ParametrosDeVideo)

    /** Devolve false se o quadro não entrou: o fluxo volta a esperar um quadro de referência. */
    fun decodificar(quadro: QuadroDeVideo): Boolean

    /** Solta o decodificador; depois disso ele não chama mais o `aoExibir`. */
    fun encerrar()
}

/**
 * Vídeo ao vivo de um fluxo RTSP de uma câmera: conecta, depacota e entrega os quadros ao
 * [Decodificador]; se a conexão cair, tenta de novo com espera crescente (até 10 s).
 *
 * Cada instância ocupa UMA sessão RTSP na câmera (elas aguentam poucas); [parar] libera a sessão.
 * O decodificador avisa pelo `aoExibir` cada quadro que virou imagem. Os ouvintes são chamados de
 * threads internas.
 */
class FluxoDeVideo(
    private val url: String,
    private val incluirAudio: Boolean = false,
    /** segundos sem nenhum quadro de vídeo que contam como câmera travada (reconecta) */
    private val limiteSemVideo: Double = 8.0,
    private val criarDecodificador: (codec: CodecDeVideo, aoExibir: (TamanhoDoVideo) -> Unit) -> Decodificador,
) {
    sealed interface Estado {
        data object Parado : Estado
        data object Conectando : Estado
        data class AoVivo(val codec: String, val largura: Int, val altura: Int) : Estado
        data class Erro(val mensagem: String) : Estado
    }

    /** Uma chamada de [iniciar]: se [parar] e [iniciar] vierem em sequência, a thread velha não atrapalha a nova. */
    private class Execucao {
        @Volatile var ativa = true
        @Volatile var cliente: ClienteRtsp? = null
        val acordar = Semaphore(0)
    }

    private val trava = Any()
    private var atual: Execucao? = null
    private val quadros = AtomicInteger()

    @Volatile var estado: Estado = Estado.Parado
        private set

    val quadrosRecebidos: Int get() = quadros.get()

    @Volatile var aoMudarEstado: ((Estado) -> Unit)? = null
    @Volatile var aoReceberAudio: ((PacoteRtp) -> Unit)? = null

    fun iniciar() {
        val e = synchronized(trava) {
            if (atual != null) return
            Execucao().also { atual = it }
        }
        thread(name = "fluxo-de-video", isDaemon = true) { laco(e) }
    }

    fun parar() {
        val e = synchronized(trava) {
            val e = atual ?: return
            atual = null
            e.ativa = false
            e
        }
        e.cliente?.parar()
        e.acordar.release()
        mudar(null, Estado.Parado)
    }

    /**
     * Só a execução atual muda o estado ([e] nulo: o próprio [parar]). O ouvinte é chamado dentro
     * da trava, para os estados chegarem na ordem: ele não pode bloquear nem chamar o fluxo.
     */
    private fun mudar(e: Execucao?, novo: Estado) {
        synchronized(trava) {
            if (e != null && atual !== e) return
            if (estado == novo) return
            estado = novo
            aoMudarEstado?.invoke(novo)
        }
    }

    private fun laco(e: Execucao) {
        var espera = 1.0
        while (e.ativa) {
            mudar(e, Estado.Conectando)
            val teveQuadros = executarUmaSessao(e)
            if (!e.ativa) break
            espera = if (teveQuadros) 1.0 else minOf(espera * 2, 10.0)
            e.acordar.tryAcquire((espera * 1000).toLong(), TimeUnit.MILLISECONDS)
        }
    }

    /** Roda uma conexão até ela terminar. Devolve se chegou ao menos um quadro. */
    private fun executarUmaSessao(e: Execucao): Boolean {
        val cli = ClienteRtsp.criar(url)
        if (cli == null) {
            mudar(e, Estado.Erro("o endereço da câmera é inválido"))
            return false
        }
        cli.limiteSemVideo = limiteSemVideo
        e.cliente = cli
        if (!e.ativa) cli.parar()                               // parar() chegou antes do cliente existir
        quadros.set(0)
        val sessao = Sessao(e)
        try {
            cli.executar(
                incluirAudio = incluirAudio,
                aoFicarPronto = { sessao.preparar(it) },
                aoReceberVideo = { sessao.receber(it) },
                aoReceberAudio = { aoReceberAudio?.invoke(it) },
            )
        } catch (x: Exception) {
            if (e.ativa) mudar(e, Estado.Erro(resumo(x)))
        } finally {
            sessao.encerrar()
            e.cliente = null
        }
        return quadros.get() > 0
    }

    /** Depacotador, preparador e decodificador de uma conexão. Só a thread do RTSP os usa. */
    private inner class Sessao(private val e: Execucao) {
        private var depacotador: Depacotador? = null
        private var preparador: PreparadorDeQuadros? = null
        private var decodificador: Decodificador? = null
        private var versaoConfigurada = 0

        fun preparar(sdp: Sdp) {
            val video = sdp.midias.firstOrNull { it.tipo == "video" } ?: throw ErroRtsp("a câmera não anunciou vídeo")
            val codec = CodecDeVideo.doSdp(video.codificacao)
                ?: throw ErroRtsp("codec de vídeo não suportado: ${video.codificacao ?: "desconhecido"}")
            depacotador = Depacotador.para(codec)
            preparador = PreparadorDeQuadros(codec).also { it.semear(video.conjuntosDeParametros) }
            decodificador = criarDecodificador(codec) { t ->
                quadros.incrementAndGet()
                mudar(e, Estado.AoVivo(codec.nome, t.largura, t.altura))
            }
        }

        fun receber(p: PacoteRtp) {
            val dep = depacotador ?: return
            val prep = preparador ?: return
            val dec = decodificador ?: return
            for (au in dep.empurrar(p)) {
                val q = prep.preparar(au) ?: continue
                val parametros = prep.parametros ?: continue
                if (prep.versao != versaoConfigurada) {
                    dec.configurar(parametros)
                    versaoConfigurada = prep.versao
                }
                if (!dec.decodificar(q)) prep.aguardarChave()
            }
        }

        fun encerrar() {
            decodificador?.encerrar()
            decodificador = null
            preparador = null
            depacotador = null
        }
    }

    private companion object {
        /** Texto de erro para a tela, sem endereço nem credencial. */
        fun resumo(x: Throwable): String = when (x) {
            is ErroRtsp -> x.message ?: "erro RTSP"
            is ErroDeRede -> Redact.texto(x.toString())
            else -> Redact.texto(x.message ?: x.javaClass.simpleName)
        }
    }
}
