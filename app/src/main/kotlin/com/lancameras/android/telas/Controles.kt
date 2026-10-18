package com.lancameras.android.telas

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lancameras.android.sessao.EstadoDeZoom
import com.lancameras.android.sessao.ModeloDoApp
import com.lancameras.android.sessao.SessaoDaCamera
import com.lancameras.nucleo.audio.Som
import com.lancameras.nucleo.dvrip.ModoDaLuz
import com.lancameras.nucleo.onvif.PresetDaCamera

/**
 * Os controles de uma câmera em retrato, abaixo do vídeo: movimento, imagem, som, posições salvas,
 * luz e rastreio.
 */
@Composable
fun Controles(modelo: ModeloDoApp, sessao: SessaoDaCamera) {
    val estado by sessao.estado.collectAsStateWithLifecycle()
    val ocupadas by sessao.ocupadas.collectAsStateWithLifecycle()
    val presetsDoApp by sessao.presetsDoApp.collectAsStateWithLifecycle()
    val luzPedida by sessao.luzPedida.collectAsStateWithLifecycle()
    val rastreioPedido by sessao.rastreioPedido.collectAsStateWithLifecycle()
    val zoom by sessao.zoom.zoom.collectAsStateWithLifecycle()
    val ouvindo by sessao.ouvindo.collectAsStateWithLifecycle()
    val falando by sessao.falandoAoVivo.collectAsStateWithLifecycle()
    val nivel by sessao.nivelDoMicrofone.collectAsStateWithLifecycle()
    val velocidade by modelo.preferencias.velocidade.collectAsStateWithLifecycle()
    val qualidadeHD by modelo.preferencias.qualidadeHD.collectAsStateWithLifecycle()
    val mostrarLenteFixa by modelo.preferencias.mostrarLenteFixa.collectAsStateWithLifecycle()
    val volume by modelo.preferencias.volume.collectAsStateWithLifecycle()
    var salvandoPosicao by rememberSaveable { mutableStateOf(false) }
    var apagando by rememberSaveable { mutableStateOf<String?>(null) }
    var texto by rememberSaveable { mutableStateOf("") }
    val foco = LocalFocusManager.current

    // Movimento
    Secao("Mover", Icons.Default.OpenWith) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { ControleDeMovimento(sessao) }
        val velocidades = listOf(0.2 to "Lenta", 0.4 to "Média", 0.7 to "Rápida")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            velocidades.forEachIndexed { i, (v, rotulo) ->
                SegmentedButton(selected = velocidade == v, onClick = { modelo.preferencias.definirVelocidade(v) }, shape = SegmentedButtonDefaults.itemShape(i, velocidades.size)) { Text(rotulo) }
            }
        }
        Dica("Segure uma seta: a câmera se mexe enquanto você segura e para ao soltar.")
        if (estado?.rastreioLigado == true) {
            Text(
                "Rastreio ligado: ela volta sozinha ao ponto de vigia depois de ${estado?.rastreioVoltaEmSegundos ?: "alguns"} s.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFFF9F0A),
            )
        }
    }

    // Imagem
    Secao("Imagem", Icons.Default.Image) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(selected = qualidadeHD, onClick = { if (!qualidadeHD) { modelo.preferencias.definirQualidadeHD(true); modelo.recomecarSessaoDaCamera() } }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("HD") }
            SegmentedButton(selected = !qualidadeHD, onClick = { if (qualidadeHD) { modelo.preferencias.definirQualidadeHD(false); modelo.recomecarSessaoDaCamera() } }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Leve") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = { sessao.zoom.definir(zoom.fator / 1.25) }) { Icon(Icons.Default.ZoomOut, contentDescription = "Diminuir o zoom") }
            Slider(
                value = zoom.fator.toFloat(),
                onValueChange = { sessao.zoom.definir(it.toDouble()) },
                valueRange = EstadoDeZoom.MINIMO.toFloat()..EstadoDeZoom.MAXIMO.toFloat(),
                modifier = Modifier.weight(1f).semantics { contentDescription = "Zoom" },
            )
            IconButton(onClick = { sessao.zoom.definir(zoom.fator * 1.25) }) { Icon(Icons.Default.ZoomIn, contentDescription = "Aumentar o zoom") }
            TextButton(onClick = { sessao.zoom.reiniciar() }, modifier = Modifier.widthIn(min = 56.dp).semantics { contentDescription = "Voltar o zoom ao normal" }) { Text(formatarZoom(zoom.fator)) }
        }
        Dica("O zoom é digital (as câmeras não têm zoom óptico). Pinça ou toque duplo no vídeo também ampliam.")
        if (sessao.camera.duasLentes) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Mostrar a lente fixa", Modifier.weight(1f))
                Switch(checked = mostrarLenteFixa, onCheckedChange = { modelo.preferencias.definirMostrarLenteFixa(it) })
            }
        }
        Button(onClick = { sessao.tirarFoto() }, enabled = "foto" !in ocupadas, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Icon(Icons.Default.PhotoCamera, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Tirar foto")
        }
    }

    // Som
    Secao("Som", Icons.AutoMirrored.Filled.VolumeUp) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(
                onClick = { sessao.alternarOuvir() },
                modifier = Modifier.weight(1f).heightIn(min = 44.dp).semantics { contentDescription = if (ouvindo) "Ouvindo, ligado" else "Ouvir, desligado" },
                colors = if (ouvindo) ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else ButtonDefaults.filledTonalButtonColors(),
            ) {
                Icon(if (ouvindo) Icons.AutoMirrored.Filled.VolumeUp else Icons.Default.VolumeOff, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (ouvindo) "Ouvindo" else "Ouvir")
            }
            BotaoDeFalar(sessao, modifier = Modifier.weight(1f))
        }
        if (falando) {
            LinearProgressIndicator(progress = { nivel }, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.error)
            Dica("Enquanto você fala, o som da câmera fica mudo para não dar eco.")
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = volume.toFloat(),
                onValueChange = { modelo.preferencias.definirVolume((Math.round(it / 5f) * 5).toDouble()) },
                valueRange = 5f..100f,
                steps = 18,
                modifier = Modifier.weight(1f).semantics { contentDescription = "Volume na câmera" },
            )
            Text("${volume.toInt()}%", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.widthIn(min = 44.dp))
        }
        Dica("O alto-falante da câmera é alto: 10% já se ouve bem.")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BotaoDeSom(Som.CAMPAINHA, sessao, ocupadas, Modifier.weight(1f))
                BotaoDeSom(Som.BIP, sessao, ocupadas, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BotaoDeSom(Som.ALARME, sessao, ocupadas, Modifier.weight(1f))
                BotaoDeSom(Som.SIRENE, sessao, ocupadas, Modifier.weight(1f))
            }
        }
        OutlinedButton(
            onClick = { sessao.pararSom() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
            Icon(Icons.Default.Stop, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Parar o som")
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = texto,
                onValueChange = { texto = it },
                label = { Text("O que a câmera deve falar") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { foco.clearFocus(); sessao.falarTexto(texto) }),
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = { foco.clearFocus(); sessao.falarTexto(texto) }, enabled = texto.isNotBlank() && "texto" !in ocupadas) { Text("Falar") }
        }
    }

    // Posições salvas
    Secao("Posições salvas", Icons.Default.Place) {
        val lista = estado?.presets ?: emptyList()
        if (lista.isEmpty()) {
            Text(if (estado == null) "lendo…" else "Nenhuma posição salva.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        for (p in lista) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(p.nome, Modifier.weight(1f), maxLines = 1)
                if (p.token in presetsDoApp) {
                    IconButton(onClick = { apagando = p.token }, enabled = "apagar-${p.token}" !in ocupadas) {
                        Icon(Icons.Default.Delete, contentDescription = "Apagar a posição ${p.nome}", tint = MaterialTheme.colorScheme.error)
                    }
                } else {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = "já vinha na câmera; o app não apaga",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp).size(16.dp),
                    )
                }
                OutlinedButton(onClick = { sessao.irPara(p) }, enabled = "preset-${p.token}" !in ocupadas, modifier = Modifier.semantics { contentDescription = "Ir para a posição ${p.nome}" }) { Text("Ir") }
            }
        }
        OutlinedButton(onClick = { salvandoPosicao = true }, enabled = "salvar-preset" !in ocupadas) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Salvar a posição atual")
        }
        Dica("Só as posições criadas por este app podem ser apagadas aqui.")
    }

    // Luz e rastreio
    Secao("Luz e rastreio", Icons.Default.Lightbulb) {
        val luz = luzPedida ?: estado?.luz
        val modos = listOf(ModoDaLuz.AUTOMATICO to "Automática", ModoDaLuz.LIGADA to "Ligada", ModoDaLuz.DESLIGADA to "Desligada")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            modos.forEachIndexed { i, (m, rotulo) ->
                SegmentedButton(
                    selected = luz == m,
                    onClick = { sessao.definirLuz(m) },
                    enabled = estado?.luz != null && "luz" !in ocupadas,
                    shape = SegmentedButtonDefaults.itemShape(i, modos.size),
                ) { Text(rotulo) }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Rastreio automático", Modifier.weight(1f))
            Switch(
                checked = rastreioPedido ?: estado?.rastreioLigado ?: false,
                onCheckedChange = { sessao.definirRastreio(it) },
                enabled = estado?.rastreioLigado != null && "rastreio" !in ocupadas,
                modifier = Modifier.semantics { contentDescription = "Rastreio automático" },
            )
        }
        Dica("Com o rastreio ligado a câmera segue o que se mexe e depois volta sozinha ao ponto de vigia: o movimento manual \"se desfaz\".")
        for (a in estado?.avisos ?: emptyList()) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFF9F0A), modifier = Modifier.size(16.dp))
                Text(a, style = MaterialTheme.typography.bodySmall, color = Color(0xFFFF9F0A))
            }
        }
    }

    if (salvandoPosicao) {
        DialogoDeNome(
            titulo = "Salvar a posição atual",
            inicial = "",
            rotulo = "Nome (ex.: Vista da rua)",
            confirmar = "Salvar",
            mensagem = "A câmera guarda para onde ela está apontando agora.",
            aoConfirmar = { sessao.salvarPosicao(it); salvandoPosicao = false },
            aoCancelar = { salvandoPosicao = false },
        )
    }
    apagando?.let { token ->
        val p: PresetDaCamera? = estado?.presets?.firstOrNull { it.token == token }
        AlertDialog(
            onDismissRequest = { apagando = null },
            title = { Text("Apagar esta posição?") },
            text = { Text("\"${p?.nome ?: token}\" vai deixar de existir na câmera.") },
            confirmButton = {
                TextButton(onClick = { p?.let { sessao.apagar(it) }; apagando = null }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Apagar") }
            },
            dismissButton = { TextButton(onClick = { apagando = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun BotaoDeSom(som: Som, sessao: SessaoDaCamera, ocupadas: Set<String>, modifier: Modifier) {
    OutlinedButton(
        onClick = { sessao.tocar(som) },
        enabled = "som" !in ocupadas,
        modifier = modifier.heightIn(min = 40.dp).semantics { contentDescription = "Toca ${som.rotulo.lowercase()} no alto-falante da câmera" },
    ) { Text(som.rotulo) }
}

/** "Segure para falar": o microfone do celular vai para o alto-falante da câmera enquanto o dedo estiver no botão. */
@Composable
fun BotaoDeFalar(sessao: SessaoDaCamera, modifier: Modifier = Modifier, compacto: Boolean = false) {
    val falando by sessao.falandoAoVivo.collectAsStateWithLifecycle()
    BotaoDePressao(
        descricao = "Segure para falar pela câmera",
        ativo = falando,
        aoPressionar = { sessao.iniciarFalaAoVivo() },
        aoSoltar = { sessao.pararFalaAoVivo() },
        modifier = modifier,
    ) { aceso ->
        val cor = MaterialTheme.colorScheme.error
        Row(
            Modifier
                .then(if (compacto) Modifier else Modifier.fillMaxWidth())
                .heightIn(min = 44.dp)
                .background(if (aceso) cor else cor.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(if (aceso) Icons.Default.Mic else Icons.Default.MicOff, contentDescription = null, tint = if (aceso) MaterialTheme.colorScheme.onError else cor)
            Spacer(Modifier.width(8.dp))
            Text(
                if (aceso) (if (falando) "Falando…" else "Abrindo…") else if (compacto) "Falar" else "Segure para falar",
                color = if (aceso) MaterialTheme.colorScheme.onError else cor,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}
