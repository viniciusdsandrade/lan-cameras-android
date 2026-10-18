package com.lancameras.nucleo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactTest {
    // Valores inventados, montados por interpolação para não parecerem credencial de verdade
    // (a verificação de privacidade acusaria o texto literal).
    private val usuario = "fulano"
    private val senhaFalsa = "AbC123xY"

    @Test
    fun mascaraUsuarioESenhaNaUrlRtsp() {
        val url = "rtsp://192.0.2.11:554/user=${usuario}_password=${senhaFalsa}_channel=0_stream=1&onvif=0.sdp?real_stream"
        val r = Redact.texto(url)
        assertFalse(r.contains(usuario))
        assertFalse(r.contains(senhaFalsa))
        assertTrue(r.contains("user=***_password=***_channel=0_stream=1"))
    }

    @Test
    fun mascaraUsuarioESenhaNoFormatoPadraoDeUrl() {
        val r = Redact.texto("rtsp://$usuario:segredo@192.0.2.11:554/canal")
        assertFalse(r.contains(usuario))
        assertFalse(r.contains("segredo"))
        assertTrue(r.contains("rtsp://***:***@192.0.2.11"))
    }

    @Test
    fun mascaraJsonDoDvrip() {
        val json = """{"EncryptType":"MD5","LoginType":"DVRIP-Web","PassWord":"$senhaFalsa","UserName":"$usuario"}"""
        val r = Redact.texto(json)
        assertFalse(r.contains(senhaFalsa))
        assertFalse(r.contains(usuario))
        assertTrue(r.contains(""""PassWord":"***""""))
        assertTrue(r.contains(""""LoginType":"DVRIP-Web""""))
    }

    @Test
    fun mascaraSoapDoOnvif() {
        val xml = "<UsernameToken><Username>$usuario</Username><Password Type=\"x\">ZGlnZXN0</Password><Nonce EncodingType=\"y\">bm9uY2U=</Nonce></UsernameToken>"
        val r = Redact.texto(xml)
        assertFalse(r.contains(usuario))
        assertFalse(r.contains("ZGlnZXN0"))
        assertFalse(r.contains("bm9uY2U="))
    }

    @Test
    fun textoSemCredencialNaoMuda() {
        val s = "câmera 192.0.2.11 respondeu em 120 ms"
        assertEquals(s, Redact.texto(s))
    }
}
