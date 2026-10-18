package com.lancameras.android.video

import com.lancameras.nucleo.video.Decodificador
import com.lancameras.nucleo.video.ParametrosDeVideo
import com.lancameras.nucleo.video.QuadroDeVideo
import com.lancameras.nucleo.video.TamanhoDoVideo

/**
 * Reparte os quadros de um fluxo entre as telas que o mostram (a lente móvel e a fixa usam o mesmo
 * fluxo), cada uma com o seu decodificador de hardware e a sua superfície.
 *
 * Guarda os quadros desde o último quadro de referência: uma tela que chega depois (abrir a câmera,
 * girar o aparelho) começa a mostrar imagem na hora, sem esperar o próximo quadro de referência.
 * Sobrevive às reconexões do fluxo: cada sessão RTSP recebe um [novaSessao], e as saídas ficam.
 */
class DistribuidorDeVideo {
    /** Uma tela ligada: [desligar] solta o decodificador dela. */
    inner class Saida internal constructor(internal val decodificador: Decodificador) {
        fun desligar() {
            synchronized(trava) { saidas.remove(this) }
            decodificador.encerrar()
        }
    }

    private val trava = Any()
    private val saidas = mutableListOf<Saida>()
    private var parametros: ParametrosDeVideo? = null
    private val grupo = ArrayDeque<QuadroDeVideo>()
    private var aoExibir: ((TamanhoDoVideo) -> Unit)? = null

    /** O decodificador de uma sessão RTSP: encaminha para as saídas. */
    fun novaSessao(aoExibir: (TamanhoDoVideo) -> Unit): Decodificador = object : Decodificador {
        private val distribuidor = this@DistribuidorDeVideo

        override fun configurar(parametros: ParametrosDeVideo) {
            val alvos = synchronized(trava) {
                distribuidor.parametros = parametros
                distribuidor.aoExibir = aoExibir
                grupo.clear()
                saidas.map { it.decodificador }
            }
            for (d in alvos) d.configurar(parametros)
        }

        override fun decodificar(quadro: QuadroDeVideo): Boolean {
            val (alvos, tamanho) = synchronized(trava) {
                if (quadro.chave) grupo.clear()
                if (grupo.size < MAXIMO_DO_GRUPO) grupo.addLast(quadro)
                saidas.map { it.decodificador } to (parametros?.tamanho ?: TamanhoDoVideo(0, 0))
            }
            // cada saída se recupera sozinha (espera o próximo quadro de referência): uma falha
            // numa tela não reinicia o fluxo para as outras
            for (d in alvos) d.decodificar(quadro)
            aoExibir(tamanho)
            return true
        }

        override fun encerrar() {
            synchronized(trava) {
                if (distribuidor.aoExibir === aoExibir) distribuidor.aoExibir = null
                grupo.clear()
            }
        }
    }

    /** Liga uma tela: recebe os parâmetros e os quadros desde o último de referência, e os próximos. */
    fun ligar(decodificador: Decodificador): Saida {
        val saida = Saida(decodificador)
        val (p, pendentes) = synchronized(trava) {
            saidas += saida
            parametros to grupo.toList()
        }
        if (p != null) {
            decodificador.configurar(p)
            for (q in pendentes) decodificador.decodificar(q)
        }
        return saida
    }

    val tamanho: TamanhoDoVideo? get() = synchronized(trava) { parametros?.tamanho }

    val saidasLigadas: Int get() = synchronized(trava) { saidas.size }

    private companion object {
        /** Quadros guardados desde o último de referência (as câmeras mandam um a cada poucos segundos). */
        const val MAXIMO_DO_GRUPO = 150
    }
}
