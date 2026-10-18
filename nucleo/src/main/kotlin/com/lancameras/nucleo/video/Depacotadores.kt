package com.lancameras.nucleo.video

import com.lancameras.nucleo.rtsp.PacoteRtp
import java.io.ByteArrayOutputStream

/** Todos os NALs de um quadro de vídeo, sem prefixo de início. */
class UnidadeDeAcesso(
    val nals: List<ByteArray>,
    /** relógio de 90 kHz do RTP, sem sinal */
    val timestamp: Long,
    /** Contém um quadro de referência (IDR no H.264; IDR, CRA ou BLA no H.265). */
    val chave: Boolean,
)

/**
 * Junta os NALs de um mesmo timestamp e fecha o quadro no bit marcador ou quando o timestamp
 * muda. As subclasses desfazem o empacotamento de cada codec.
 */
abstract class Depacotador {
    protected val nals = mutableListOf<ByteArray>()
    protected var fragmento: ByteArrayOutputStream? = null
    private var timestampAtual = 0L
    private var ultimaSequencia = -1

    protected abstract fun tipoDeNal(nal: ByteArray): Int
    protected abstract fun ehChave(tipo: Int): Boolean
    protected abstract fun processar(c: ByteArray)

    /** Entrega um pacote RTP; devolve os quadros que ele completou (0 ou 1, em geral). */
    fun empurrar(p: PacoteRtp): List<UnidadeDeAcesso> {
        val saida = ArrayList<UnidadeDeAcesso>(1)
        // Em TCP não há perda; se houver salto, um fragmento pela metade não presta.
        if (ultimaSequencia >= 0 && p.sequencia != (ultimaSequencia + 1) and 0xFFFF) fragmento = null
        ultimaSequencia = p.sequencia
        if (nals.isNotEmpty() && p.timestamp != timestampAtual) fechar()?.let { saida += it }
        timestampAtual = p.timestamp
        processar(p.carga)
        if (p.marcador) fechar()?.let { saida += it }
        return saida
    }

    private fun fechar(): UnidadeDeAcesso? {
        if (nals.isEmpty()) return null
        val chave = nals.any { it.isNotEmpty() && ehChave(tipoDeNal(it)) }
        val au = UnidadeDeAcesso(nals.toList(), timestampAtual, chave)
        nals.clear()
        return au
    }

    /** NALs agregados: [tamanho 16 bits][NAL] repetidos, a partir de [deslocamento]. */
    protected fun separarAgregado(c: ByteArray, deslocamento: Int) {
        var i = deslocamento
        while (i + 2 <= c.size) {
            val tam = (c[i].toInt() and 0xFF) shl 8 or (c[i + 1].toInt() and 0xFF)
            i += 2
            if (tam <= 0 || i + tam > c.size) return
            nals += c.copyOfRange(i, i + tam)
            i += tam
        }
    }

    /** Começo, meio ou fim de um NAL fragmentado; [cabecalho] só vale no começo. */
    protected fun fragmentar(comeco: Boolean, fim: Boolean, cabecalho: ByteArray, c: ByteArray, deslocamento: Int) {
        if (comeco) {
            fragmento = ByteArrayOutputStream(maxOf(1024, c.size * 8)).apply { write(cabecalho) }
        }
        val f = fragmento ?: return
        f.write(c, deslocamento, c.size - deslocamento)
        if (fim) {
            nals += f.toByteArray()
            fragmento = null
        }
    }

    companion object {
        fun para(codec: CodecDeVideo): Depacotador = when (codec) {
            CodecDeVideo.H264 -> DepacotadorH264()
            CodecDeVideo.H265 -> DepacotadorH265()
        }
    }
}

/** H.264 sobre RTP (RFC 6184): NAL único, STAP-A e FU-A. */
class DepacotadorH264 : Depacotador() {
    override fun tipoDeNal(nal: ByteArray) = nal[0].toInt() and 0x1F
    override fun ehChave(tipo: Int) = tipo == 5

    override fun processar(c: ByteArray) {
        if (c.isEmpty()) return
        when (c[0].toInt() and 0x1F) {
            in 1..23 -> nals += c
            24 -> separarAgregado(c, 1)                                // STAP-A
            28 -> {                                                    // FU-A
                if (c.size < 2) return
                val indicador = c[0].toInt() and 0xFF
                val cab = c[1].toInt() and 0xFF
                val original = byteArrayOf(((indicador and 0xE0) or (cab and 0x1F)).toByte())
                fragmentar(cab and 0x80 != 0, cab and 0x40 != 0, original, c, 2)
            }
        }
    }
}

/** H.265 sobre RTP (RFC 7798): NAL único, AP (48) e FU (49). */
class DepacotadorH265 : Depacotador() {
    override fun tipoDeNal(nal: ByteArray) = (nal[0].toInt() shr 1) and 0x3F
    override fun ehChave(tipo: Int) = tipo in 16..21

    override fun processar(c: ByteArray) {
        if (c.size < 2) return
        when ((c[0].toInt() shr 1) and 0x3F) {
            in 0..47 -> nals += c
            48 -> separarAgregado(c, 2)                                // AP
            49 -> {                                                    // FU
                if (c.size < 3) return
                val cabFu = c[2].toInt() and 0xFF
                // cabeçalho do NAL de volta: o tipo real no lugar de 49, camada e TID mantidos
                val original = byteArrayOf((((c[0].toInt() and 0xFF) and 0x81) or ((cabFu and 0x3F) shl 1)).toByte(), c[1])
                fragmentar(cabFu and 0x80 != 0, cabFu and 0x40 != 0, original, c, 3)
            }
            // PACI e reservados: ignorados
        }
    }
}
