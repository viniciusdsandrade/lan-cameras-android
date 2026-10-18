package com.lancameras.nucleo.rtsp

import com.lancameras.nucleo.Redact
import com.lancameras.nucleo.rede.SoqueteTcp

/** Falha do RTSP; a mensagem passa por [Redact]: a URL das câmeras carrega usuário e senha. */
class ErroRtsp(descricao: String) : Exception(Redact.texto(descricao)) {
    override fun toString(): String = message ?: "erro RTSP"
}

/**
 * Cliente RTSP com RTP entrelaçado no próprio TCP (RFC 2326), feito para as câmeras XiongMai.
 *
 * Particularidades dessas câmeras, vistas nas respostas reais:
 * - o RTP pode começar a chegar ANTES da resposta ao PLAY, então quadros e respostas são
 *   separados por um único leitor, nunca em sequência;
 * - cada SETUP devolve um `Session` diferente; vale o do último;
 * - a trilha é aceita em `URL + "/" + a=control`, e o `Content-Base` vem sem a parte `?real_stream`.
 *
 * [executar] bloqueia a thread que o chama até [parar] ou até uma falha.
 */
class ClienteRtsp private constructor(private val url: String, private val host: String, private val porta: Int) {
    class Resposta(val status: Int, val cabecalhos: Map<String, String>, val corpo: String) {
        val cseq: Int? get() = cabecalhos["cseq"]?.trim()?.toIntOrNull()
    }

    private sealed class Mensagem {
        class Quadro(val canal: Int, val dados: ByteArray) : Mensagem()
        class Resp(val resposta: Resposta) : Mensagem()
    }

    @Volatile private var pararPedido = false

    // Estado da sessão: só a thread de executar mexe.
    private var buffer = ByteArray(1 shl 16)
    private var inicio = 0
    private var fim = 0
    private var cseq = 0
    private var sessao: String? = null
    private var canalVideo = -1
    private var canalAudio = -1
    private var aoReceberVideo: ((PacoteRtp) -> Unit)? = null
    private var aoReceberAudio: ((PacoteRtp) -> Unit)? = null
    private var ultimoVideo = System.nanoTime()

    /** Segundos sem vídeo que contam como câmera travada. */
    var limiteSemVideo: Double = 8.0

    companion object {
        /** Aceita só `rtsp://host[:porta]/…`. */
        fun criar(url: String): ClienteRtsp? {
            if (!url.lowercase().startsWith("rtsp://")) return null
            val resto = url.substring(7)
            val autoridade = resto.substringBefore('/').substringAfterLast('@')
            if (autoridade.isEmpty() || autoridade.any { it.isWhitespace() }) return null
            val host = autoridade.substringBeforeLast(':', autoridade)
            val porta = if (autoridade.contains(':')) autoridade.substringAfterLast(':').toIntOrNull() ?: return null else 554
            if (host.isEmpty() || porta !in 1..65535) return null
            return ClienteRtsp(url, host, porta)
        }

        fun canalEntrelacado(transporte: String): Int? {
            for (parte in transporte.split(";")) {
                val p = parte.trim()
                if (p.startsWith("interleaved=")) return p.removePrefix("interleaved=").split("-").first().toIntOrNull()
            }
            return null
        }
    }

    fun parar() {
        pararPedido = true
    }

    // MARK: Sessão

    fun executar(
        incluirAudio: Boolean,
        aoFicarPronto: (Sdp) -> Unit,
        aoReceberVideo: (PacoteRtp) -> Unit,
        aoReceberAudio: (PacoteRtp) -> Unit = {},
    ) {
        this.aoReceberVideo = aoReceberVideo
        this.aoReceberAudio = aoReceberAudio
        val soquete = SoqueteTcp()
        soquete.conectar(host, porta, 5.0)
        try {
            pedir("OPTIONS", url, soquete = soquete)                  // algumas não respondem 200: tudo bem
            val descricao = pedir("DESCRIBE", url, listOf("Accept" to "application/sdp"), soquete)
            if (descricao.status != 200) throw erroDeStatus("DESCRIBE", descricao.status)
            val sdp = Sdp.interpretar(descricao.corpo)
            if (sdp.midias.none { it.tipo == "video" }) throw ErroRtsp("a câmera não anunciou vídeo")
            aoFicarPronto(sdp)

            val base = descricao.cabecalhos["content-base"]
            var canal = 0
            for (midia in sdp.midias.filter { it.tipo == "video" || (incluirAudio && it.tipo == "audio") }) {
                val atribuido = configurarTrilha(midia, canal, base, soquete)
                if (midia.tipo == "video") canalVideo = atribuido else canalAudio = atribuido
                canal += 2
            }

            val play = pedir("PLAY", url, listOf("Range" to "npt=0.000-"), soquete)
            if (play.status != 200) throw erroDeStatus("PLAY", play.status)

            ultimoVideo = System.nanoTime()
            var ultimoKeepalive = System.nanoTime()
            while (!pararPedido) {
                val m = lerMensagem(0.25, soquete)
                if (m is Mensagem.Quadro) despachar(m.canal, m.dados)
                val agora = System.nanoTime()
                if ((agora - ultimoVideo) / 1e9 > limiteSemVideo) throw ErroRtsp("a câmera parou de enviar vídeo")
                if ((agora - ultimoKeepalive) / 1e9 >= 20) {
                    enviar("OPTIONS", url, soquete = soquete)          // a resposta é descartada no laço
                    ultimoKeepalive = agora
                }
            }
            // Parada pedida: libera a sessão na câmera sem esperar resposta (há quadros no caminho).
            try { enviar("TEARDOWN", url, soquete = soquete) } catch (_: Exception) {}
        } finally {
            soquete.fechar()
        }
    }

    private fun configurarTrilha(midia: Sdp.Midia, canal: Int, base: String?, soquete: SoqueteTcp): Int {
        val candidatas = mutableListOf(urlDaTrilha(midia.controle, url + if (url.endsWith("/")) "" else "/"))
        if (base != null) {
            val alt = urlDaTrilha(midia.controle, base)
            if (alt != candidatas[0]) candidatas += alt
        }
        var ultimoStatus = 0
        for (alvo in candidatas) {
            val r = pedir("SETUP", alvo, listOf("Transport" to "RTP/AVP/TCP;unicast;interleaved=$canal-${canal + 1}"), soquete)
            ultimoStatus = r.status
            if (r.status != 200) continue
            r.cabecalhos["session"]?.let { sessao = it.split(";").first().trim() }
            return r.cabecalhos["transport"]?.let { canalEntrelacado(it) } ?: canal
        }
        throw erroDeStatus("SETUP", ultimoStatus)
    }

    private fun urlDaTrilha(controle: String?, base: String): String {
        if (controle.isNullOrEmpty() || controle == "*") return url
        if (controle.lowercase().startsWith("rtsp://")) return controle
        return base + controle
    }

    private fun erroDeStatus(metodo: String, status: Int): ErroRtsp = when (status) {
        401, 403 -> ErroRtsp("$metodo: a câmera recusou o usuário ou a senha ($status)")
        404 -> ErroRtsp("$metodo: a câmera não achou o fluxo pedido (404)")
        453, 503 -> ErroRtsp("$metodo: a câmera está sem sessões livres ($status)")
        else -> ErroRtsp("$metodo: resposta inesperada da câmera ($status)")
    }

    // MARK: Pedidos e leitura

    private fun montar(metodo: String, url: String, cabecalhos: List<Pair<String, String>>): Pair<Int, ByteArray> {
        cseq += 1
        val req = StringBuilder("$metodo $url RTSP/1.0\r\nCSeq: $cseq\r\nUser-Agent: CamerasDeCasa\r\n")
        sessao?.let { req.append("Session: $it\r\n") }
        for ((k, v) in cabecalhos) req.append("$k: $v\r\n")
        req.append("\r\n")
        return cseq to req.toString().toByteArray(Charsets.UTF_8)
    }

    private fun enviar(metodo: String, url: String, cabecalhos: List<Pair<String, String>> = emptyList(), soquete: SoqueteTcp) {
        soquete.escrever(montar(metodo, url, cabecalhos).second)
    }

    private fun pedir(
        metodo: String,
        url: String,
        cabecalhos: List<Pair<String, String>> = emptyList(),
        soquete: SoqueteTcp,
        tempo: Double = 8.0,
    ): Resposta {
        val (meu, bytes) = montar(metodo, url, cabecalhos)
        soquete.escrever(bytes)
        val limite = System.nanoTime() + (tempo * 1e9).toLong()
        while (true) {
            if (pararPedido) throw ErroRtsp("interrompido")
            val restante = (limite - System.nanoTime()) / 1e9
            if (restante <= 0) throw ErroRtsp("$metodo: a câmera não respondeu em ${tempo.toInt()} s")
            when (val m = lerMensagem(minOf(0.25, restante), soquete) ?: continue) {
                is Mensagem.Quadro -> despachar(m.canal, m.dados)
                is Mensagem.Resp -> if (m.resposta.cseq == meu) return m.resposta
            }
        }
    }

    private fun despachar(canal: Int, dados: ByteArray) {
        if (canal == canalVideo) {
            val p = PacoteRtp.de(dados) ?: return
            ultimoVideo = System.nanoTime()
            aoReceberVideo?.invoke(p)
        } else if (canal == canalAudio) {
            PacoteRtp.de(dados)?.let { aoReceberAudio?.invoke(it) }
        }   // canais ímpares são RTCP: ignorados
    }

    private fun lerMensagem(tempo: Double, soquete: SoqueteTcp): Mensagem? {
        while (true) {
            extrairMensagem()?.let { return it }
            val novos = soquete.ler(65536, tempo) ?: return null
            acrescentar(novos)
        }
    }

    private fun acrescentar(novos: ByteArray) {
        if (fim + novos.size > buffer.size) {
            // desloca o que falta ler para o começo e cresce, se for preciso
            val pendente = fim - inicio
            val novo = if (pendente + novos.size > buffer.size) ByteArray(maxOf(buffer.size * 2, pendente + novos.size)) else buffer
            System.arraycopy(buffer, inicio, novo, 0, pendente)
            buffer = novo
            inicio = 0
            fim = pendente
        }
        System.arraycopy(novos, 0, buffer, fim, novos.size)
        fim += novos.size
    }

    private fun consumir(n: Int) {
        inicio += n
        if (inicio >= fim) {
            inicio = 0
            fim = 0
        }
    }

    private fun u(i: Int) = buffer[i].toInt() and 0xFF

    /** Tira uma mensagem completa do buffer, ou devolve null se ainda faltam bytes. */
    private fun extrairMensagem(): Mensagem? {
        // linhas em branco soltas entre mensagens
        while (inicio < fim && (u(inicio) == 13 || u(inicio) == 10)) consumir(1)
        val disponivel = fim - inicio
        if (disponivel <= 0) return null

        if (u(inicio) == 0x24) {                                       // '$': quadro RTP/RTCP
            if (disponivel < 4) return null
            val tam = u(inicio + 2) shl 8 or u(inicio + 3)
            if (disponivel < 4 + tam) return null
            val canal = u(inicio + 1)
            val dados = buffer.copyOfRange(inicio + 4, inicio + 4 + tam)
            consumir(4 + tam)
            return Mensagem.Quadro(canal, dados)
        }

        if (u(inicio) != 0x52) throw ErroRtsp("dados inesperados no fluxo da câmera")   // 'R' de "RTSP/1.0"
        val fimCab = fimDoCabecalho()
        if (fimCab == null) {
            if (disponivel > 32768) throw ErroRtsp("resposta da câmera sem fim de cabeçalho")
            return null
        }
        val linhas = String(buffer, inicio, fimCab - inicio, Charsets.UTF_8).split("\r\n").toMutableList()
        val partes = linhas.removeAt(0).split(" ", limit = 3)
        val status = partes.getOrNull(1)?.toIntOrNull()
        if (partes.size < 2 || !partes[0].startsWith("RTSP/") || status == null) throw ErroRtsp("resposta RTSP inválida")
        val cab = HashMap<String, String>()
        for (l in linhas) {
            val i = l.indexOf(':')
            if (i < 0) continue
            cab[l.substring(0, i).lowercase()] = l.substring(i + 1).trim()
        }
        val tamCorpo = cab["content-length"]?.trim()?.toIntOrNull() ?: 0
        val total = (fimCab + 4 - inicio) + tamCorpo
        if (disponivel < total) return null
        val corpo = String(buffer, fimCab + 4, tamCorpo, Charsets.UTF_8)
        consumir(total)
        return Mensagem.Resp(Resposta(status, cab, corpo))
    }

    private fun fimDoCabecalho(): Int? {
        var i = inicio
        while (i + 3 < fim) {
            if (u(i) == 13 && u(i + 1) == 10 && u(i + 2) == 13 && u(i + 3) == 10) return i
            i++
        }
        return null
    }
}
