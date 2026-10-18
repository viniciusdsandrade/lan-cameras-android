package com.lancameras.nucleo.rtsp

import com.lancameras.nucleo.apoio.Amostras
import com.lancameras.nucleo.apoio.Empacotador
import com.lancameras.nucleo.apoio.bytes
import com.lancameras.nucleo.video.CodecDeVideo
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SdpERtpTest {
    /** SDP no formato que as câmeras XiongMai mandam (inclusive o espaço depois de "H265/90000"). */
    private fun sdpDeTeste(h265: Boolean): String {
        val nals = Amostras.nals(h265)
        fun b64(tipo: Int) = Base64.getEncoder().encodeToString(nals.first { Amostras.tipo(it, h265) == tipo })
        val video = if (h265) {
            "m=video 0 RTP/AVP 98\r\nc=IN IP4 192.0.2.10\r\na=rtpmap:98 H265/90000 \r\n" +
                "a=fmtp:98 profile-id=010101;sprop-pps=${b64(34)};sprop-sps=${b64(33)};sprop-vps=${b64(32)};\r\n" +
                "a=framerate:25\r\na=control:trackID=3\r\n"
        } else {
            "m=video 0 RTP/AVP 96\r\nc=IN IP4 192.0.2.10\r\na=rtpmap:96 H264/90000 \r\n" +
                "a=fmtp:96 profile-level-id=4d0033; packetization-mode=1; sprop-parameter-sets=${b64(7)},${b64(8)}\r\n" +
                "a=framerate:25\r\na=control:trackID=3\r\n"
        }
        return "v=0\r\no=- 1 1 IN IP4 192.0.2.10\r\ns=RTSP Session\r\nt=0 0\r\na=control:*\r\n" + video +
            "m=audio 0 RTP/AVP 8\r\nc=IN IP4 192.0.2.10\r\na=rtpmap:8 PCMA/8000\r\na=control:trackID=4\r\n"
    }

    @Test
    fun sdpDeH265() {
        val sdp = Sdp.interpretar(sdpDeTeste(h265 = true))
        assertEquals(2, sdp.midias.size)
        val v = sdp.midias[0]
        assertEquals("video", v.tipo)
        assertEquals("H265", v.codificacao)
        assertEquals(98, v.tipoDeCarga)
        assertEquals("trackID=3", v.controle)
        assertEquals(90000, v.taxas[98])
        assertEquals(3, v.conjuntosDeParametros.size)
        assertEquals(setOf(32, 33, 34), v.conjuntosDeParametros.map { Amostras.tipo(it, true) }.toSet())
        val a = sdp.midias[1]
        assertEquals("audio", a.tipo)
        assertEquals("PCMA", a.codificacao)
        assertEquals("trackID=4", a.controle)
    }

    @Test
    fun sdpDeH264() {
        val v = Sdp.interpretar(sdpDeTeste(h265 = false)).midias[0]
        assertEquals("H264", v.codificacao)
        assertEquals("1", v.parametros["packetization-mode"])
        assertEquals(listOf(7, 8), v.conjuntosDeParametros.map { Amostras.tipo(it, false) })
    }

    @Test
    fun codecDeVideoAPartirDoSdp() {
        assertEquals(CodecDeVideo.H264, CodecDeVideo.doSdp("H264"))
        assertEquals(CodecDeVideo.H265, CodecDeVideo.doSdp("h265"))
        assertEquals(CodecDeVideo.H265, CodecDeVideo.doSdp("HEVC"))
        assertNull(CodecDeVideo.doSdp("MJPEG"))
        assertNull(CodecDeVideo.doSdp(null))
    }

    @Test
    fun rtpComCabecalhoSimples() {
        val p = PacoteRtp.de(Empacotador.cabecalho(513, 0x01020304, marcador = true, pt = 98) + bytes(1, 2, 3))
        assertNotNull(p)
        p!!
        assertEquals(98, p.tipoDeCarga)
        assertTrue(p.marcador)
        assertEquals(513, p.sequencia)
        assertEquals(0x01020304L, p.timestamp)
        assertArrayEquals(bytes(1, 2, 3), p.carga)
    }

    @Test
    fun rtpComTimestampAcimaDe31Bits() {
        val p = PacoteRtp.de(Empacotador.cabecalho(1, 0xFFFFFFF0L, marcador = false, pt = 96) + bytes(1))!!
        assertEquals(0xFFFFFFF0L, p.timestamp)
    }

    @Test
    fun rtpComExtensaoECsrcEPreenchimento() {
        val b = Empacotador.cabecalho(1, 2, marcador = false, pt = 96)
        b[0] = (0x80 or 0x20 or 0x10 or 0x02).toByte()               // preenchimento, extensão e 2 CSRC
        val pacote = b +
            bytes(0, 0, 0, 1, 0, 0, 0, 2) +                          // 2 CSRC
            bytes(0xBE, 0xDE, 0, 1, 9, 9, 9, 9) +                    // extensão de 1 palavra
            bytes(7, 8, 9) +                                         // carga
            bytes(0, 0, 3)                                           // 3 bytes de preenchimento (o último diz quantos)
        assertArrayEquals(bytes(7, 8, 9), PacoteRtp.de(pacote)!!.carga)
    }

    @Test
    fun rtpMalformadoEhRejeitado() {
        assertNull(PacoteRtp.de(bytes(0x80, 96, 0)))                 // curto demais
        assertNull(PacoteRtp.de(ByteArray(20)))                      // versão 0
    }

    @Test
    fun canalEntrelacadoDoTransport() {
        assertEquals(2, ClienteRtsp.canalEntrelacado("RTP/AVP/TCP;unicast;interleaved=2-3;mode=PLAY"))
        assertNull(ClienteRtsp.canalEntrelacado("RTP/AVP;unicast;client_port=5000-5001"))
    }

    @Test
    fun urlInvalidaNaoCriaCliente() {
        assertNull(ClienteRtsp.criar("http://localhost/x"))
        assertNull(ClienteRtsp.criar("isso nao e url"))
        assertNull(ClienteRtsp.criar("rtsp://camera:porta/x"))
        assertNotNull(ClienteRtsp.criar("rtsp://camera.example:8554/x"))
    }
}
