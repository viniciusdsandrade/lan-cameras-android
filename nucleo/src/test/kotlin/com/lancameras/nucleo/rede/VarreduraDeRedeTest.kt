package com.lancameras.nucleo.rede

import com.lancameras.nucleo.apoio.abrirEscutaLocal
import com.lancameras.nucleo.apoio.loopback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * A varredura que substitui o multicast: endereços a testar e teste das portas. Endereços da
 * faixa de documentação (192.0.2.x) e 10.x inventados.
 */
class VarreduraDeRedeTest {
    /** Endereços montados por partes: o repositório não leva nada com cara de rede local real. */
    private fun ip(a: Int, b: Int, c: Int, d: Int) = "$a.$b.$c.$d"

    private fun rede(texto: String, prefixo: Int) =
        VarreduraDeRede.Rede("wlan0", VarreduraDeRede.numero(texto)!!, VarreduraDeRede.mascara(prefixo))

    @Test
    fun redeComum24TestaTodosMenosRedeBroadcastEOProprio() {
        val lista = VarreduraDeRede.enderecos(rede("192.0.2.35", 24))
        assertEquals(253, lista.size)
        assertEquals("192.0.2.1", lista.first())
        assertEquals("192.0.2.254", lista.last())
        assertFalse(lista.contains("192.0.2.0"))
        assertFalse(lista.contains("192.0.2.255"))
        assertFalse(lista.contains("192.0.2.35"))
    }

    @Test
    fun redeGrandeFicaComOsEnderecosEmVoltaDoAparelho() {
        val proprio = VarreduraDeRede.numero(ip(10, 1, 2, 3))!!
        val lista = VarreduraDeRede.enderecos(rede(ip(10, 1, 2, 3), 16), limite = 1024)
        assertEquals(1024, lista.size)
        assertFalse(lista.contains(ip(10, 1, 2, 3)))
        assertEquals("sem repetidos", lista.size, lista.toSet().size)
        for (e in lista) {
            val n = VarreduraDeRede.numero(e)!!
            assertEquals("$e fora da rede", proprio shr 16, n shr 16)
            assertTrue("$e longe demais do aparelho", abs(n - proprio) <= 513)
        }
    }

    @Test
    fun aparelhoNoComecoOuNoFimDeUmaRedeGrandeNaoSaiDela() {
        val noComeco = VarreduraDeRede.enderecos(rede(ip(10, 0, 0, 1), 8), limite = 100)
        assertEquals(100, noComeco.size)
        assertEquals(ip(10, 0, 0, 2), noComeco.first())
        val noFim = VarreduraDeRede.enderecos(rede(ip(10, 255, 255, 254), 8), limite = 100)
        assertEquals(100, noFim.size)
        assertEquals(ip(10, 255, 255, 253), noFim.last())
        assertFalse(noFim.contains(ip(10, 255, 255, 255)))
    }

    @Test
    fun redesSemHostsNaoTestamNada() {
        assertEquals(emptyList<String>(), VarreduraDeRede.enderecos(rede("192.0.2.10", 31)))
        assertEquals(emptyList<String>(), VarreduraDeRede.enderecos(rede("192.0.2.10", 32)))
        assertEquals(listOf("192.0.2.10"), VarreduraDeRede.enderecos(rede("192.0.2.9", 30)))
    }

    @Test
    fun soRedesPrivadasEntram() {
        for (e in listOf(ip(10, 0, 0, 1), ip(172, 16, 0, 1), ip(172, 31, 255, 1), ip(192, 168, 0, 1))) {
            assertTrue(e, VarreduraDeRede.privada(VarreduraDeRede.numero(e)!!))
        }
        for (e in listOf(ip(172, 32, 0, 1), ip(8, 8, 8, 8), ip(100, 64, 0, 1), "192.0.2.1")) {
            assertFalse(e, VarreduraDeRede.privada(VarreduraDeRede.numero(e)!!))
        }
    }

    @Test
    fun numeroETextoVaoEVoltam() {
        assertEquals(0xC0000223L, VarreduraDeRede.numero("192.0.2.35"))
        assertEquals("192.0.2.35", VarreduraDeRede.texto(0xC0000223L))
        assertEquals(0xFFFFFF00L, VarreduraDeRede.mascara(24))
        assertEquals(0L, VarreduraDeRede.mascara(0))
        assertEquals(0xFFFFFFFFL, VarreduraDeRede.mascara(32))
        for (ruim in listOf("não é endereço", "999.1.1.1", "1.2.3", "1.2.3.4.5", "01a.2.3.4", "", "1..2.3")) {
            assertNull(ruim, VarreduraDeRede.numero(ruim))
        }
    }

    @Test
    fun achaAPortaQueEscutaENaoAFechada() {
        abrirEscutaLocal().use { escuta ->
            val aberto = VarreduraDeRede.testar(listOf(loopback), escuta.localPort)
            assertEquals(listOf(loopback), aberto.abertos)
            assertEquals(1, aberto.tentados)
        }
        val livre = abrirEscutaLocal().use { it.localPort }          // a porta fica sem ninguém: conexão recusada
        val fechado = VarreduraDeRede.testar(listOf(loopback), livre)
        assertEquals(emptyList<String>(), fechado.abertos)
        assertEquals(1, fechado.tentados)
        assertEquals("recusa não é falta de rota", 0, fechado.semRota)
    }

    @Test
    fun enderecoInvalidoNemETentado() {
        val r = VarreduraDeRede.testar(listOf("não é endereço", "999.1.1.1"), 8899)
        assertEquals(0, r.tentados)
        assertEquals(emptyList<String>(), r.abertos)
    }

    @Test
    fun lotesMaioresQueOParaleloSaoTodosTestados() {
        abrirEscutaLocal().use { escuta ->
            // o mesmo loopback repetido, para exercitar mais de um lote sem tocar em rede nenhuma
            val hosts = List(5) { loopback }
            val r = VarreduraDeRede.testar(hosts, escuta.localPort, paralelo = 2)
            assertEquals(5, r.tentados)
            assertEquals(5, r.abertos.size)
        }
    }

    @Test
    fun redeNegadaAoAppSoQuandoQuaseTudoVoltaSemRota() {
        val r = VarreduraDeRede.Resultado()
        r.tentados = 253
        r.semRota = 253
        assertTrue(VarreduraDeRede.pareceSemPermissao(r))
        r.semRota = 100
        assertFalse("rede comum: a maioria só não responde", VarreduraDeRede.pareceSemPermissao(r))
        r.semRota = 0
        r.negados = 253
        assertTrue("barrado pelo sistema conta igual", VarreduraDeRede.pareceSemPermissao(r))
        r.abertos += "192.0.2.20"
        assertFalse("se alguém atendeu, a rede está liberada", VarreduraDeRede.pareceSemPermissao(r))
    }

    @Test
    fun asRedesDoAparelhoSaoPrivadasESemLoopback() {
        for (r in VarreduraDeRede.redesLocais()) {
            assertTrue(r.interface_, VarreduraDeRede.nomeDeRedeLocal(r.interface_))
            assertTrue(VarreduraDeRede.privada(r.endereco))
            assertNotEquals(127L, r.endereco shr 24)
        }
    }
}
