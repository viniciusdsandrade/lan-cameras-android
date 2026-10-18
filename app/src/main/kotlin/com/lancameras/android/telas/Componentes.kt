package com.lancameras.android.telas

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lancameras.android.apoio.Aviso
import com.lancameras.android.apoio.Direcao
import com.lancameras.android.sessao.EstadoDeZoom
import com.lancameras.android.sessao.ModeloDoApp
import com.lancameras.android.sessao.SessaoDaCamera
import com.lancameras.android.sessao.SessaoDeVideo
import com.lancameras.android.video.LenteExibida
import com.lancameras.android.video.VistaDaLente
import com.lancameras.nucleo.video.FluxoDeVideo
import java.util.Locale

fun FluxoDeVideo.Estado.cor(): Color = when (this) {
    is FluxoDeVideo.Estado.AoVivo -> Color(0xFF4CD964)
    FluxoDeVideo.Estado.Conectando -> Color(0xFFFF9F0A)
    is FluxoDeVideo.Estado.Erro -> Color(0xFFFF453A)
    FluxoDeVideo.Estado.Parado -> Color.Gray
}

/** Texto que cobre o vídeo enquanto não há imagem; null quando está ao vivo. */
fun FluxoDeVideo.Estado.mensagem(): String? = when (this) {
    is FluxoDeVideo.Estado.AoVivo -> null
    FluxoDeVideo.Estado.Conectando -> "conectando…"
    FluxoDeVideo.Estado.Parado -> "parado"
    is FluxoDeVideo.Estado.Erro -> "sem imagem: $mensagem"
}

fun formatarZoom(fator: Double): String = String.format(Locale.ROOT, "%.1f×", fator)

/** Uma lente de uma câmera na tela, com o nome e o estado da conexão por cima. */
@Composable
fun LenteComRotulo(
    rotulo: String,
    video: SessaoDeVideo,
    lente: LenteExibida,
    modifier: Modifier = Modifier,
    zoom: EstadoDeZoom? = null,
    zoomHabilitado: Boolean = false,
    cantos: Dp = 10.dp,
    aoTocar: (() -> Unit)? = null,
    /** Na tela cheia o nome vai junto dos controles, não por cima do vídeo. */
    mostrarRotulo: Boolean = true,
    fonteDeFoto: Boolean = false,
) {
    val estado by video.estado.collectAsStateWithLifecycle()
    val z = if (zoomHabilitado && zoom != null) zoom.zoom.collectAsStateWithLifecycle().value else null
    Box(
        modifier
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(cantos))
            .background(Color.Black)
            .semantics { contentDescription = "$rotulo, ${estado.mensagem() ?: "ao vivo"}" },
    ) {
        VistaDaLente(video, lente, Modifier.fillMaxSize(), zoom, zoomHabilitado, fonteDeFoto, aoTocar)
        estado.mensagem()?.let { m ->
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
                Text(m, color = Color.White.copy(alpha = 0.85f), textAlign = TextAlign.Center, modifier = Modifier.padding(14.dp))
            }
        }
        if (mostrarRotulo) {
            Etiqueta(Modifier.align(Alignment.TopStart).padding(8.dp)) {
                Box(Modifier.size(8.dp).background(estado.cor(), CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(rotulo, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
        }
        if (z != null && z.ampliado) {
            Etiqueta(Modifier.align(Alignment.BottomEnd).padding(8.dp)) {
                Text(formatarZoom(z.fator), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** Uma cápsula escura por cima do vídeo (nome, estado, zoom, Wi-Fi). */
@Composable
fun Etiqueta(modifier: Modifier = Modifier, conteudo: @Composable RowScope.() -> Unit) {
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Row(
            modifier.background(Color.Black.copy(alpha = 0.6f), CircleShape).padding(horizontal = 9.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = conteudo,
        )
    }
}

/**
 * Botão que age ENQUANTO está pressionado (setas de movimento, "segure para falar"): chama
 * [aoPressionar] ao encostar e [aoSoltar] ao soltar. Se o gesto for cancelado (ligação, a tela
 * saindo), o soltar vem do mesmo jeito: a câmera nunca fica se mexendo sozinha.
 */
@Composable
fun BotaoDePressao(
    descricao: String,
    ativo: Boolean,
    aoPressionar: () -> Unit,
    aoSoltar: () -> Unit,
    modifier: Modifier = Modifier,
    conteudo: @Composable (aceso: Boolean) -> Unit,
) {
    var pressionado by remember { mutableStateOf(false) }
    val haptico = LocalHapticFeedback.current
    val pressionar by rememberUpdatedState(aoPressionar)
    val soltar by rememberUpdatedState(aoSoltar)
    DisposableEffect(Unit) { onDispose { if (pressionado) soltar() } }
    Box(
        modifier
            .semantics {
                contentDescription = descricao
                role = Role.Button
                stateDescription = if (ativo || pressionado) "ativo" else "solto"
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    pressionado = true
                    haptico.performHapticFeedback(HapticFeedbackType.LongPress)
                    pressionar()
                    try {
                        do {
                            val evento = awaitPointerEvent()
                            evento.changes.forEach { it.consume() }
                        } while (evento.changes.any { it.pressed })
                    } finally {
                        pressionado = false
                        soltar()
                    }
                }
            },
    ) {
        conteudo(ativo || pressionado)
    }
}

/** Uma seta do controle de movimento: quadrada, grande o bastante para o polegar. */
@Composable
fun SetaDeMovimento(direcao: Direcao, sessao: SessaoDaCamera, lado: Dp = 64.dp, translucido: Boolean = false) {
    val ativas by sessao.direcoesAtivas.collectAsStateWithLifecycle()
    val icone: ImageVector = when (direcao) {
        Direcao.CIMA -> Icons.Default.KeyboardArrowUp
        Direcao.BAIXO -> Icons.Default.KeyboardArrowDown
        Direcao.ESQUERDA -> Icons.AutoMirrored.Filled.KeyboardArrowLeft
        Direcao.DIREITA -> Icons.AutoMirrored.Filled.KeyboardArrowRight
    }
    BotaoDePressao(
        descricao = "Mover para ${direcao.nome}",
        ativo = direcao in ativas,
        aoPressionar = { sessao.segurar(direcao) },
        aoSoltar = { sessao.soltar(direcao) },
        modifier = Modifier.testTag("seta-${direcao.nome}"),
    ) { aceso ->
        val fundo = when {
            aceso -> MaterialTheme.colorScheme.primary
            translucido -> Color.White.copy(alpha = 0.18f)
            else -> MaterialTheme.colorScheme.surfaceVariant
        }
        Box(
            Modifier.size(lado).background(fundo, RoundedCornerShape(14.dp)).border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icone, contentDescription = null, tint = if (aceso) MaterialTheme.colorScheme.onPrimary else Color.White, modifier = Modifier.size(lado * 0.5f))
        }
    }
}

/** As quatro setas com o botão de parar no meio. */
@Composable
fun ControleDeMovimento(sessao: SessaoDaCamera, lado: Dp = 64.dp, translucido: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.size(lado))
            SetaDeMovimento(Direcao.CIMA, sessao, lado, translucido)
            Spacer(Modifier.size(lado))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SetaDeMovimento(Direcao.ESQUERDA, sessao, lado, translucido)
            Box(
                Modifier
                    .size(lado)
                    .background(if (translucido) Color.White.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(14.dp))
                    .clickable(role = Role.Button, onClickLabel = "Parar a câmera") { sessao.pararMovimento() }
                    .semantics { contentDescription = "Parar a câmera" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Stop, contentDescription = null, tint = Color.White, modifier = Modifier.size(lado * 0.4f))
            }
            SetaDeMovimento(Direcao.DIREITA, sessao, lado, translucido)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.size(lado))
            SetaDeMovimento(Direcao.BAIXO, sessao, lado, translucido)
            Spacer(Modifier.size(lado))
        }
    }
}

/** Os avisos curtos no pé da tela (feito, erro, dica). */
@Composable
fun AvisoView(modelo: ModeloDoApp, modifier: Modifier = Modifier) {
    val aviso by modelo.aviso.collectAsStateWithLifecycle()
    var ultimo by remember { mutableStateOf<Aviso?>(null) }
    if (aviso != null) ultimo = aviso
    AnimatedVisibility(
        visible = aviso != null,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier,
    ) {
        val a = ultimo ?: return@AnimatedVisibility
        val cor = when (a.tipo) {
            Aviso.Tipo.INFO -> MaterialTheme.colorScheme.primary
            Aviso.Tipo.OK -> Color(0xFF4CD964)
            Aviso.Tipo.ERRO -> MaterialTheme.colorScheme.error
        }
        val icone = when (a.tipo) {
            Aviso.Tipo.INFO -> Icons.Default.Info
            Aviso.Tipo.OK -> Icons.Default.CheckCircle
            Aviso.Tipo.ERRO -> Icons.Default.Warning
        }
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .border(1.dp, cor.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                .clickable { modelo.fecharAviso() }
                .semantics { contentDescription = a.texto }
                .testTag("aviso"),
        ) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(icone, contentDescription = null, tint = cor)
                Text(a.texto, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Um grupo de controles com título, no estilo dos Ajustes. */
@Composable
fun Secao(titulo: String, icone: ImageVector, conteudo: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icone, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Text(titulo, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = conteudo)
        }
    }
}

/** Texto de apoio abaixo de um controle. */
@Composable
fun Dica(texto: String) {
    Text(texto, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Um toque simples em qualquer lugar da área, sem roubar os outros gestos. */
fun Modifier.aoTocar(acao: () -> Unit): Modifier = pointerInput(acao) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        acao()
    }
}
