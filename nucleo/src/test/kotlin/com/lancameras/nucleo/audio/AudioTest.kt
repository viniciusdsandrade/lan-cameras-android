package com.lancameras.nucleo.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class AudioTest {
    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    // A-law

    @Test
    fun codificacaoBateComOAudioopDoPythonParaTodosOsValores() {
        // Valores de referência gerados com audioop.lin2alaw sobre todos os 65536 PCM de 16 bits.
        val todos = ShortArray(65536) { (it - 32768).toShort() }
        assertEquals(listOf("38488f6fd710f468", "6360edc4d38639f9", "6c491595ef93f8eb", "8d62d5e07ca6ce7b").joinToString(""), sha256(ALaw.codificar(todos)))
    }

    @Test
    fun decodificacaoBateComOAudioopDoPython() {
        val pcm = ALaw.decodificar(ByteArray(256) { it.toByte() })
        val bytes = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN).apply { pcm.forEach { putShort(it) } }.array()
        assertEquals(listOf("e04788d110e58ff8", "c70c93b8480190d9", "73e3b67876b6119a", "bbaec766cc75c174").joinToString(""), sha256(bytes))
    }

    @Test
    fun silencioEhD5() {
        assertArrayEquals(byteArrayOf(0xD5.toByte()), ALaw.codificar(shortArrayOf(0)))
        assertEquals(0xD5.toByte(), ALaw.SILENCIO)
    }

    @Test
    fun idaEVoltaTemErroPequenoRelativo() {
        for (v in -32768..32767 step 97) {
            val volta = ALaw.decodificar(ALaw.codificar(shortArrayOf(v.toShort())))[0].toInt()
            assertTrue("$v -> $volta", abs(volta - v) <= maxOf(64, abs(v) / 16))
        }
    }

    @Test
    fun codificarPcmDe16BitsLittleEndian() {
        // 0x0100 (256) e 0xFF00 (-256), em bytes little-endian
        assertArrayEquals(ALaw.codificar(shortArrayOf(256, -256)), ALaw.codificarPcm16(byteArrayOf(0x00, 0x01, 0x00, 0xFF.toByte())))
        assertEquals("byte sobrando é ignorado", 1, ALaw.codificarPcm16(byteArrayOf(0x00, 0x01, 0x7F)).size)
        assertEquals("só o tamanho pedido", 1, ALaw.codificarPcm16(ByteArray(8), tamanho = 2).size)
    }

    @Test
    fun ganhoEscalaOVolume() {
        val original = ALaw.codificar(shortArrayOf(8000, -8000, 100, 0))
        assertSame(original, ALaw.aplicarGanho(original, 1.0))
        val metade = ALaw.decodificar(ALaw.aplicarGanho(original, 0.5))
        assertEquals(4000.0, metade[0].toDouble(), 300.0)
        assertEquals(-4000.0, metade[1].toDouble(), 300.0)
        val estourado = ALaw.decodificar(ALaw.aplicarGanho(original, 100.0))
        assertTrue("satura sem dar a volta", estourado[0] > 30000)
        assertTrue(estourado[1] < -30000)
    }

    // Sons

    @Test
    fun duracaoDeCadaSom() {
        val esperado = mapOf(Som.BIP to 9120, Som.CAMPAINHA to 12000, Som.ALARME to 28800, Som.SIRENE to 48000)
        for (som in Som.entries) assertEquals(som.name, esperado[som], som.alaw.size)
    }

    @Test
    fun sonsTemSinalLimitadoESemEstalosNasPontas() {
        for (som in Som.entries) {
            val pcm = ALaw.decodificar(som.alaw)
            val pico = pcm.maxOf { abs(it.toInt()) }
            assertTrue("$som está quase mudo", pico > 10000)
            assertTrue("$som passa da amplitude de 0,8", pico <= (0.81 * 32768).toInt())
            assertTrue("$som começa com estalo", abs(pcm.first().toInt()) < 400)
            assertTrue("$som termina com estalo", abs(pcm.last().toInt()) < 400)
        }
    }

    @Test
    fun somEhGeradoUmaVezSo() {
        assertSame(Som.SIRENE.alaw, Som.SIRENE.alaw)
    }

    // Voz

    @Test
    fun textoVazioEhRecusadoELongoEhCortado() {
        assertThrows(Voz.Falha::class.java) { Voz.limpar("  \n\t ") }
        assertEquals("a b c", Voz.limpar("  a \n b\t\tc "))
        assertEquals(Voz.MAXIMO_DE_CARACTERES, Voz.limpar("a  b\n".repeat(400)).length)
    }

    @Test
    fun normalizarSobeVolumeSemPassarDeOitoVezes() {
        assertEquals("limite de 8x", 8000, Voz.normalizar(shortArrayOf(1000, -500)).maxOf { abs(it.toInt()) })
        assertEquals("o pico vai para 0,9 da escala", 29490, Voz.normalizar(shortArrayOf(20000, -10000)).maxOf { abs(it.toInt()) })
        assertArrayEquals(shortArrayOf(0, 0), Voz.normalizar(shortArrayOf(0, 0)))
    }

    private fun wav(pcm: ShortArray, taxa: Int, canais: Int = 1, extra: Boolean = false): ByteArray {
        val o = ByteArrayOutputStream()
        fun le32(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
        fun le16(v: Int) = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()
        val dados = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { pcm.forEach { putShort(it) } }.array()
        o.write("RIFF".toByteArray()); o.write(le32(0)); o.write("WAVE".toByteArray())
        o.write("fmt ".toByteArray()); o.write(le32(16)); o.write(le16(1)); o.write(le16(canais)); o.write(le32(taxa))
        o.write(le32(taxa * 2 * canais)); o.write(le16(2 * canais)); o.write(le16(16))
        if (extra) { o.write("LIST".toByteArray()); o.write(le32(3)); o.write(byteArrayOf(1, 2, 3, 0)) }   // pedaço ímpar, com preenchimento
        o.write("data".toByteArray()); o.write(le32(dados.size)); o.write(dados)
        return o.toByteArray()
    }

    @Test
    fun lerWavMisturaCanaisEPulaPedacosDesconhecidos() {
        val (mono, taxa) = Voz.lerWav(wav(shortArrayOf(100, -200, 300), 24000, extra = true))!!
        assertEquals(24000, taxa)
        assertArrayEquals(shortArrayOf(100, -200, 300), mono)
        val (misturado, _) = Voz.lerWav(wav(shortArrayOf(100, 300, -100, -300), 16000, canais = 2))!!
        assertArrayEquals(shortArrayOf(200, -200), misturado)
        assertEquals(null, Voz.lerWav("isto não é wav".toByteArray()))
    }

    @Test
    fun vozDeUmWavDe24kHzViraAlawDe8kHzAudivel() {
        val pcm = ShortArray(24000) { (6000 * sin(2 * PI * 440 * it / 24000.0)).toInt().toShort() }      // 1 s de lá
        val alaw = Voz.alawDoWav(wav(pcm, 24000))
        assertEquals("1 s a 8 kHz", 8000, alaw.size)
        val volta = ALaw.decodificar(alaw)
        assertTrue("normalizado: ${volta.maxOf { abs(it.toInt()) }}", volta.maxOf { abs(it.toInt()) } > 25000)
        assertThrows(Voz.Falha::class.java) { Voz.alawDoWav(wav(ShortArray(0), 24000)) }
    }

    @Test
    fun reamostrarParaCimaEParaBaixoMantemADuracao() {
        val base = ShortArray(800) { (it * 10).toShort() }
        assertEquals(8000 / 100, Voz.reamostrar(ShortArray(1000), 100_000, 8000).size)
        assertEquals(1600, Voz.reamostrar(base, 8000, 16000).size)
        assertEquals(400, Voz.reamostrar(base, 16000, 8000).size)
        assertSame(base, Voz.reamostrar(base, 8000, 8000))
    }
}
