package com.lancameras.android.video

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A escolha do decodificador, com o que um celular de verdade informou: o hardware diz aceitar o HD
 * das câmeras de duas lentes (2304x2592), mas vai só até 2560x1440 e recusa ao configurar.
 */
class OrdemDosDecodificadoresTest {
    private val hardware = OpcaoDeDecodificador("c2.mtk.avc.decoder", hardware = true, aceitaOTamanho = true)
    private val hardwareAntigo = OpcaoDeDecodificador("OMX.MTK.VIDEO.DECODER.AVC", hardware = true, aceitaOTamanho = true)
    private val software = OpcaoDeDecodificador("c2.android.avc.decoder", hardware = false, aceitaOTamanho = true)
    private val semOTamanho = OpcaoDeDecodificador("c2.exemplo.avc.decoder", hardware = true, aceitaOTamanho = false)

    @Test
    fun primeiroOHardwareQueDizAceitarOTamanhoDepoisOSoftware() {
        assertEquals(
            listOf(hardware.nome, hardwareAntigo.nome, software.nome, semOTamanho.nome),
            ordemDeTentativa(listOf(semOTamanho, software, hardware, hardwareAntigo), jaRecusaram = emptySet()),
        )
    }

    @Test
    fun quemJaRecusouEsteVideoFicaParaOFim() {
        // da segunda vez em diante, o software vai direto, sem esperar o hardware recusar de novo
        assertEquals(
            listOf(software.nome, semOTamanho.nome, hardware.nome, hardwareAntigo.nome),
            ordemDeTentativa(listOf(hardware, hardwareAntigo, software, semOTamanho), jaRecusaram = setOf(hardware.nome, hardwareAntigo.nome)),
        )
    }
}
