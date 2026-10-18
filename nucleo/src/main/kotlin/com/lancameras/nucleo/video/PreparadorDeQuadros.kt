package com.lancameras.nucleo.video

/** VPS (só no H.265), SPS e PPS de um fluxo: o que o decodificador precisa para começar. */
class ParametrosDeVideo(val codec: CodecDeVideo, val vps: ByteArray?, val sps: ByteArray, val pps: ByteArray) {
    val tamanho: TamanhoDoVideo? by lazy { TamanhoDoVideo.doSps(codec, sps) }

    /** Os buffers de configuração do MediaCodec ("csd-0" e, no H.264, "csd-1"), em Annex B. */
    val csd: List<ByteArray>
        get() = when (codec) {
            CodecDeVideo.H264 -> listOf(annexB(listOf(sps)), annexB(listOf(pps)))
            CodecDeVideo.H265 -> listOf(annexB(listOfNotNull(vps, sps, pps)))
        }
}

/** Um quadro pronto para o decodificador: os NALs de imagem em Annex B. */
class QuadroDeVideo(val dados: ByteArray, val tempoUs: Long, val chave: Boolean)

/**
 * Prepara as unidades de acesso para o decodificador de hardware.
 *
 * Guarda VPS/SPS/PPS (do SDP e do próprio vídeo), só libera quadros depois que eles chegaram e a
 * partir de um quadro de referência, e monta cada quadro em Annex B (prefixo 00 00 00 01), que é o
 * que o MediaCodec espera. Se os parâmetros mudarem (a câmera trocou a resolução), [versao] muda e
 * o decodificador tem de ser configurado de novo. Usado sempre pela mesma thread.
 */
class PreparadorDeQuadros(val codec: CodecDeVideo) {
    var parametros: ParametrosDeVideo? = null
        private set

    /** Muda sempre que os parâmetros mudam. */
    var versao = 0
        private set

    var descartados = 0
        private set

    private var vps: ByteArray? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var aguardandoChave = true
    private var ultimoTimestamp = -1L
    private var decorrido = 0L                                  // em unidades de 90 kHz

    /** Parâmetros que vieram no SDP (`sprop-*`), úteis se a câmera não os repetir no vídeo. */
    fun semear(nals: List<ByteArray>) {
        var mudou = false
        for (n in nals) if (guardar(n)) mudou = true
        if (mudou) atualizar()
    }

    fun preparar(au: UnidadeDeAcesso): QuadroDeVideo? {
        var mudou = false
        for (n in au.nals) if (guardar(n)) mudou = true
        if (mudou) atualizar()

        val imagem = au.nals.filter { codec.ehImagem(it) }
        if (imagem.isEmpty()) return null
        if (parametros == null) {
            descartados++
            return null
        }
        if (aguardandoChave) {
            if (!au.chave) {
                descartados++
                return null
            }
            aguardandoChave = false
        }
        return QuadroDeVideo(annexB(imagem), micros(au.timestamp), au.chave)
    }

    /** Depois de uma falha do decodificador: só volta num quadro de referência. */
    fun aguardarChave() {
        aguardandoChave = true
    }

    /** Guarda VPS/SPS/PPS; devolve true se algum mudou. */
    private fun guardar(nal: ByteArray): Boolean {
        if (nal.isEmpty()) return false
        val t = codec.tipo(nal)
        when {
            (codec == CodecDeVideo.H264 && t == 7) || (codec == CodecDeVideo.H265 && t == 33) ->
                if (!nal.contentEquals(sps)) { sps = nal; return true }
            (codec == CodecDeVideo.H264 && t == 8) || (codec == CodecDeVideo.H265 && t == 34) ->
                if (!nal.contentEquals(pps)) { pps = nal; return true }
            codec == CodecDeVideo.H265 && t == 32 ->
                if (!nal.contentEquals(vps)) { vps = nal; return true }
        }
        return false
    }

    private fun atualizar() {
        aguardandoChave = true
        val s = sps ?: return
        val p = pps ?: return
        if (codec == CodecDeVideo.H265 && vps == null) return
        parametros = ParametrosDeVideo(codec, vps, s, p)
        versao++
    }

    /** O relógio de 90 kHz do RTP dá a volta em 32 bits; o tempo do quadro não pode voltar junto. */
    private fun micros(timestamp: Long): Long {
        if (ultimoTimestamp >= 0) {
            var delta = (timestamp - ultimoTimestamp) and 0xFFFFFFFFL
            if (delta >= 0x80000000L) delta -= 0x100000000L
            decorrido += delta
        }
        ultimoTimestamp = timestamp
        return maxOf(0L, decorrido) * 1000 / 90
    }
}

private val prefixoDeInicio = byteArrayOf(0, 0, 0, 1)

/** NALs com o prefixo de início de 4 bytes na frente de cada um. */
fun annexB(nals: List<ByteArray>): ByteArray {
    val saida = ByteArray(nals.sumOf { it.size + 4 })
    var i = 0
    for (n in nals) {
        prefixoDeInicio.copyInto(saida, i)
        n.copyInto(saida, i + 4)
        i += n.size + 4
    }
    return saida
}
