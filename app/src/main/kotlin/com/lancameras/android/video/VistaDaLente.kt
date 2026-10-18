package com.lancameras.android.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lancameras.android.apoio.Depuracao
import com.lancameras.android.sessao.EstadoDeZoom
import com.lancameras.android.sessao.SessaoDeVideo
import com.lancameras.android.sessao.Zoom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Uma lente na tela: uma `TextureView` que recebe o vídeo decodificado pelo hardware, deslocada
 * para a metade certa do quadro (as duas lentes vêm empilhadas), com o zoom por cima.
 *
 * Com o zoom ligado, entende pinça (amplia em volta dos dedos), arrastar com zoom e toque duplo
 * (amplia no ponto tocado ou volta ao normal); o toque simples vai para [aoTocar] (na tela cheia,
 * mostra os controles). Sem zoom nem toque, não pega toque nenhum: o cartão da visão geral é quem
 * recebe o clique.
 */
@Composable
fun VistaDaLente(
    video: SessaoDeVideo,
    lente: LenteExibida,
    modifier: Modifier = Modifier,
    zoom: EstadoDeZoom? = null,
    zoomHabilitado: Boolean = false,
    /** Esta vista é quem tira a foto da câmera (ela mostra o quadro inteiro na textura). */
    fonteDeFoto: Boolean = false,
    aoTocar: (() -> Unit)? = null,
) {
    // um controle por vista (a `TextureView` é uma só): se a sessão de vídeo for trocada por
    // outra (o app voltou do segundo plano), a mesma superfície passa a receber o fluxo novo
    val controle = remember { ControleDaVista() }
    val zoomAtual: Zoom? = if (zoom != null && zoomHabilitado) zoom.zoom.collectAsStateWithLifecycle().value else null
    controle.video = video
    controle.lente = lente
    controle.zoom = zoomAtual
    controle.fonteDeFoto = fonteDeFoto
    DisposableEffect(controle) { onDispose { controle.desligar() } }

    var m = modifier
    if (zoomHabilitado && zoom != null) {
        m = m
            .pointerInput(zoom) {
                detectTransformGestures { centro, arrasto, fator, _ ->
                    val w = size.width.toDouble()
                    val h = size.height.toDouble()
                    if (fator != 1f) zoom.ampliar(fator.toDouble(), centro.x - w / 2, centro.y - h / 2)
                    if (arrasto.x != 0f || arrasto.y != 0f) zoom.deslocar(arrasto.x.toDouble(), arrasto.y.toDouble(), w, h)
                }
            }
            .pointerInput(zoom, aoTocar) {
                detectTapGestures(
                    onDoubleTap = { p ->
                        if (zoom.zoom.value.ampliado) zoom.reiniciar()
                        else zoom.ampliar(2.5, p.x - size.width / 2.0, p.y - size.height / 2.0)
                    },
                    onTap = { aoTocar?.invoke() },
                )
            }
    } else if (aoTocar != null) {
        m = m.pointerInput(aoTocar) {
            awaitEachGesture {
                awaitFirstDown()
                aoTocar()
            }
        }
    }

    AndroidView(
        factory = { ctx -> controle.criar(ctx) },
        modifier = m,
        update = { controle.aplicar() },
    )
}

/** O que liga a `TextureView` ao fluxo: superfície, decodificador, matriz da lente e foto. */
private class ControleDaVista : TextureView.SurfaceTextureListener {
    var video: SessaoDeVideo? = null
        set(novo) {
            if (field === novo) return
            desligarDoFluxo()
            field = novo
            ligarAoFluxo()
        }
    var lente = LenteExibida.MOVEL
    var zoom: Zoom? = null
    var fonteDeFoto = false
        set(v) {
            field = v
            val s = video ?: return
            if (v && saida != null) s.capturador = capturador
            else if (!v && s.capturador === capturador) s.capturador = null
        }
    private var vista: TextureView? = null
    private var superficie: Surface? = null
    private var saida: DistribuidorDeVideo.Saida? = null

    private val capturador: suspend (LenteExibida) -> ByteArray? = { capturar(it) }

    fun criar(ctx: Context): TextureView = TextureView(ctx).also { v ->
        v.isOpaque = true
        v.surfaceTextureListener = this
        v.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> aplicar() }
        vista = v
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        superficie = Surface(st)
        ligarAoFluxo()
        aplicar()
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = aplicar()

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        desligarDoFluxo()
        superficie?.release()
        superficie = null
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    /** Com superfície e fluxo, um decodificador liga um ao outro. */
    private fun ligarAoFluxo() {
        val v = video ?: return
        val s = superficie ?: return
        if (saida != null) return
        val d = DecodificadorMediaCodec(s) { mensagem -> Depuracao.registrar { "${v.camera.nome}: $mensagem" } }
        saida = v.distribuidor.ligar(d)
        if (fonteDeFoto) v.capturador = capturador
    }

    private fun desligarDoFluxo() {
        saida?.desligar()
        saida = null
        video?.let { if (it.capturador === capturador) it.capturador = null }
    }

    /** A vista saiu da tela. */
    fun desligar() {
        desligarDoFluxo()
        superficie?.release()
        superficie = null
    }

    fun aplicar() {
        val v = vista ?: return
        val w = v.width.toFloat()
        val h = v.height.toFloat()
        if (w <= 0f || h <= 0f) return
        val m = MatrizDaLente.calcular(lente, zoom, w, h)
        v.setTransform(Matrix().apply {
            setScale(m.escalaX, m.escalaY)
            postTranslate(m.deslocamentoX, m.deslocamentoY)
        })
    }

    /** O quadro inteiro como a textura o tem (sem a matriz da vista), recortado na lente pedida, em JPEG. */
    private suspend fun capturar(l: LenteExibida): ByteArray? {
        val v = vista ?: return null
        val t = video?.distribuidor?.tamanho
        val inteiro: Bitmap = withContext(Dispatchers.Main) {
            if (t != null && t.largura > 0 && t.altura > 0) v.getBitmap(t.largura, t.altura) else v.bitmap
        } ?: return null
        return withContext(Dispatchers.Default) {
            val metade = inteiro.height / 2
            val recorte = when (l) {
                LenteExibida.MOVEL -> Bitmap.createBitmap(inteiro, 0, metade, inteiro.width, inteiro.height - metade)
                LenteExibida.FIXA -> Bitmap.createBitmap(inteiro, 0, 0, inteiro.width, metade)
                LenteExibida.INTEIRA -> inteiro
            }
            ByteArrayOutputStream().also { recorte.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        }
    }
}
