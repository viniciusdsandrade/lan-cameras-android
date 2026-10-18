package com.lancameras.android.telas

import android.content.res.Configuration
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lancameras.android.sessao.EstadoDeZoom
import com.lancameras.android.sessao.ModeloDoApp
import com.lancameras.android.sessao.SessaoDaCamera
import com.lancameras.android.video.LenteExibida
import com.lancameras.nucleo.dvrip.ModoDaLuz
import kotlinx.coroutines.delay

/**
 * Uma câmera aberta. Em retrato, o vídeo fica fixo no topo e os controles rolam embaixo; em paisagem,
 * o vídeo ocupa a tela inteira e os controles essenciais ficam por cima.
 */
@Composable
fun VisaoDaCamera(modelo: ModeloDoApp, idDaCamera: String) {
    val sessao by modelo.sessaoDaCamera.collectAsStateWithLifecycle()
    val s = sessao
    if (s == null || s.camera.id != idDaCamera) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator()
                Text("Abrindo a câmera…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }
    // a tela não apaga enquanto alguém está olhando uma câmera
    val vista = LocalView.current
    DisposableEffect(vista) {
        vista.keepScreenOn = true
        onDispose { vista.keepScreenOn = false }
    }
    val paisagem = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    if (paisagem) TelaCheiaDaCamera(modelo, s) else RetratoDaCamera(modelo, s)
}

// Retrato

@Composable
private fun RetratoDaCamera(modelo: ModeloDoApp, sessao: SessaoDaCamera) {
    val estado by sessao.estado.collectAsStateWithLifecycle()
    val mostrarLenteFixa by modelo.preferencias.mostrarLenteFixa.collectAsStateWithLifecycle()
    val semZoom = remember { EstadoDeZoom() }
    var renomeando by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(sessao.camera.nome) },
                navigationIcon = {
                    IconButton(onClick = { modelo.selecionar(ModeloDoApp.Selecao.Geral) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar para todas as câmeras")
                    }
                },
                actions = { IconButton(onClick = { renomeando = true }) { Icon(Icons.Default.Edit, contentDescription = "Renomear a câmera") } },
            )
        },
    ) { recuo ->
        Column(Modifier.fillMaxSize().padding(recuo)) {
            Box {
                LenteComRotulo(
                    rotulo = if (sessao.camera.duasLentes) "Lente móvel" else sessao.camera.nome,
                    video = sessao.video,
                    lente = if (sessao.camera.duasLentes) LenteExibida.MOVEL else LenteExibida.INTEIRA,
                    zoom = sessao.zoom,
                    zoomHabilitado = true,
                    cantos = 0.dp,
                    fonteDeFoto = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                estado?.sinalDoWiFi?.let { sinal ->
                    Etiqueta(Modifier.align(Alignment.TopEnd).padding(8.dp).semantics { contentDescription = "Wi-Fi da câmera: $sinal de 100" }) {
                        Icon(Icons.Default.Wifi, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("$sinal%", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                if (sessao.camera.duasLentes && mostrarLenteFixa) {
                    LenteComRotulo("Lente fixa", sessao.video, LenteExibida.FIXA, zoom = semZoom, modifier = Modifier.fillMaxWidth())
                }
                Controles(modelo, sessao)
            }
        }
    }

    if (renomeando) {
        DialogoDeNome(
            titulo = "Renomear a câmera",
            inicial = sessao.camera.nome,
            rotulo = "Nome",
            confirmar = "Salvar",
            aoConfirmar = { modelo.renomear(sessao.camera, it); renomeando = false },
            aoCancelar = { renomeando = false },
        )
    }
}

// Paisagem (tela cheia)

@Composable
private fun TelaCheiaDaCamera(modelo: ModeloDoApp, sessao: SessaoDaCamera) {
    val estadoDoVideo by sessao.video.estado.collectAsStateWithLifecycle()
    val estado by sessao.estado.collectAsStateWithLifecycle()
    val zoom by sessao.zoom.zoom.collectAsStateWithLifecycle()
    val direcoes by sessao.direcoesAtivas.collectAsStateWithLifecycle()
    val falando by sessao.falandoAoVivo.collectAsStateWithLifecycle()
    val ouvindo by sessao.ouvindo.collectAsStateWithLifecycle()
    val ocupadas by sessao.ocupadas.collectAsStateWithLifecycle()
    val luzPedida by sessao.luzPedida.collectAsStateWithLifecycle()
    val qualidadeHD by modelo.preferencias.qualidadeHD.collectAsStateWithLifecycle()
    var controlesVisiveis by remember { mutableStateOf(true) }
    var ultimoToque by remember { mutableLongStateOf(0L) }
    /** Nada some enquanto a câmera se mexe ou fala: o dedo está num botão. */
    val emUso = direcoes.isNotEmpty() || falando

    // barras do sistema escondidas enquanto o vídeo ocupa a tela
    val activity = LocalActivity.current
    val vista = LocalView.current
    DisposableEffect(activity) {
        val janela = activity?.window
        val controle = janela?.let { WindowInsetsControllerCompat(it, vista) }
        controle?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controle?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controle?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    // esconde os controles depois de 3 s sem toque (nunca com um botão apertado)
    LaunchedEffect(controlesVisiveis, emUso, ultimoToque) {
        if (controlesVisiveis && !emUso) {
            delay(3000)
            controlesVisiveis = false
        }
    }
    fun tocou() {
        ultimoToque = System.nanoTime()
        controlesVisiveis = true
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            LenteComRotulo(
                rotulo = sessao.camera.nome,
                video = sessao.video,
                lente = if (sessao.camera.duasLentes) LenteExibida.MOVEL else LenteExibida.INTEIRA,
                zoom = sessao.zoom,
                zoomHabilitado = true,
                cantos = 0.dp,
                mostrarRotulo = false,
                fonteDeFoto = true,
                aoTocar = {
                    if (controlesVisiveis && !emUso) controlesVisiveis = false else tocou()
                },
                modifier = Modifier,
            )
        }
        AnimatedVisibility(visible = controlesVisiveis, enter = fadeIn(), exit = fadeOut()) {
            Column(
                Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp, vertical = 14.dp).aoTocar { tocou() },
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BotaoRedondo(icone = Icons.AutoMirrored.Filled.ArrowBack, rotulo = "Voltar para todas as câmeras") { modelo.selecionar(ModeloDoApp.Selecao.Geral) }
                    Etiqueta(Modifier.heightIn(min = 44.dp).semantics { contentDescription = "${sessao.camera.nome}, ${estadoDoVideo.mensagem() ?: "ao vivo"}" }) {
                        Box(Modifier.size(8.dp).background(estadoDoVideo.cor(), CircleShape))
                        Spacer(Modifier.width(6.dp))
                        Text(sessao.camera.nome, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    }
                    Spacer(Modifier.weight(1f))
                    BotaoRedondo(texto = if (qualidadeHD) "HD" else "Leve", rotulo = "Qualidade: ${if (qualidadeHD) "HD" else "leve"}") {
                        modelo.preferencias.definirQualidadeHD(!qualidadeHD)
                        modelo.recomecarSessaoDaCamera()
                    }
                    val luz = luzPedida ?: estado?.luz ?: ModoDaLuz.AUTOMATICO
                    BotaoRedondo(
                        texto = "Luz: ${rotuloCurtoDaLuz(luz)}",
                        rotulo = "Luz: ${SessaoDaCamera.nome(luz)}",
                        habilitado = estado?.luz != null && "luz" !in ocupadas,
                    ) { sessao.definirLuz(proximaLuz(luz)) }
                    BotaoRedondo(
                        icone = if (ouvindo) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                        rotulo = if (ouvindo) "Parar de ouvir" else "Ouvir",
                    ) { sessao.alternarOuvir() }
                    BotaoRedondo(icone = Icons.Default.PhotoCamera, rotulo = "Tirar foto") { sessao.tirarFoto() }
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    ControleDeMovimento(sessao, lado = 56.dp, translucido = true)
                    Spacer(Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            BotaoRedondo(icone = Icons.Default.ZoomOut, rotulo = "Diminuir o zoom") { sessao.zoom.definir(zoom.fator / 1.25) }
                            BotaoRedondo(texto = formatarZoom(zoom.fator), rotulo = "Voltar o zoom ao normal") { sessao.zoom.reiniciar() }
                            BotaoRedondo(icone = Icons.Default.ZoomIn, rotulo = "Aumentar o zoom") { sessao.zoom.definir(zoom.fator * 1.25) }
                        }
                        BotaoDeFalar(sessao, compacto = true)
                    }
                }
            }
        }
    }
}

/** Um botão redondo translúcido da tela cheia, com ícone ou texto curto. */
@Composable
private fun BotaoRedondo(icone: ImageVector? = null, texto: String? = null, rotulo: String, habilitado: Boolean = true, acao: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = 44.dp)
            .widthIn(min = 44.dp)
            .background(Color.White.copy(alpha = if (habilitado) 0.18f else 0.08f), CircleShape)
            .clickable(enabled = habilitado, role = Role.Button, onClick = acao)
            .semantics { contentDescription = rotulo; role = Role.Button }
            .padding(horizontal = if (texto == null) 10.dp else 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (icone != null) Icon(icone, contentDescription = null, tint = Color.White.copy(alpha = if (habilitado) 1f else 0.5f))
        else Text(texto ?: "", color = Color.White.copy(alpha = if (habilitado) 1f else 0.5f), style = MaterialTheme.typography.labelLarge)
    }
}

private fun rotuloCurtoDaLuz(m: ModoDaLuz) = when (m) {
    ModoDaLuz.AUTOMATICO -> "auto"
    ModoDaLuz.LIGADA -> "ligada"
    ModoDaLuz.DESLIGADA -> "desl."
}

internal fun proximaLuz(m: ModoDaLuz) = when (m) {
    ModoDaLuz.AUTOMATICO -> ModoDaLuz.LIGADA
    ModoDaLuz.LIGADA -> ModoDaLuz.DESLIGADA
    ModoDaLuz.DESLIGADA -> ModoDaLuz.AUTOMATICO
}
