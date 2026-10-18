package com.lancameras.nucleo.video

enum class CodecDeVideo(val nome: String, /** tipo MIME do MediaCodec */ val mime: String) {
    H264("H264", "video/avc"),
    H265("H265", "video/hevc");

    /** Tipo do NAL a partir do primeiro byte (e do segundo, no H.265). */
    fun tipo(nal: ByteArray): Int {
        val b0 = nal[0].toInt() and 0xFF
        return if (this == H264) b0 and 0x1F else (b0 shr 1) and 0x3F
    }

    /** Fatias de imagem (VCL): o resto são parâmetros, SEI e afins. */
    fun ehImagem(nal: ByteArray): Boolean {
        if (nal.isEmpty()) return false
        val t = tipo(nal)
        return if (this == H264) t in 1..5 else t <= 21
    }

    companion object {
        /** A partir do nome no `a=rtpmap` do SDP. */
        fun doSdp(nome: String?): CodecDeVideo? = when (nome?.uppercase()) {
            "H264" -> H264
            "H265", "HEVC" -> H265
            else -> null
        }
    }
}
