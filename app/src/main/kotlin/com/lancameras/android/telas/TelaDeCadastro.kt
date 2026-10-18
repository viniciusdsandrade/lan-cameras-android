package com.lancameras.android.telas

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lancameras.android.sessao.ModeloDoApp

/**
 * A primeira tela, enquanto não há câmeras: procura sozinha ao abrir (no Android 17 é isso que faz
 * o sistema perguntar pela rede local) e oferece o endereço manual.
 */
@Composable
fun TelaDeCadastro(modelo: ModeloDoApp) {
    val procurando by modelo.procurando.collectAsStateWithLifecycle()
    var endereco by rememberSaveable { mutableStateOf("") }
    var jaProcurou by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!jaProcurou) {
            jaProcurou = true
            modelo.procurarCameras()
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Câmeras de Casa") }) }) { recuo ->
        Column(
            Modifier.fillMaxSize().padding(recuo).verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Icon(Icons.Default.Videocam, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(56.dp))
            Text("Nenhuma câmera cadastrada", style = MaterialTheme.typography.titleLarge)
            Text(
                "O app procura as câmeras no Wi-Fi da sua casa e pega delas mesmas o que precisa para entrar: você não digita usuário nem senha. O celular precisa estar no mesmo Wi-Fi que elas.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BotaoDeProcurar(procurando) { modelo.procurarCameras() }
            if (Build.VERSION.SDK_INT >= 37) {
                Dica("Se o celular perguntar se o app pode acessar a rede local (dispositivos próximos), toque em Permitir. Se negou, libere em Configurações > Apps > Câmeras de Casa > Permissões.")
            }
            HorizontalDivider()
            Text("Não achou? Digite o endereço da câmera:", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            CampoDeEndereco(endereco, { endereco = it }, procurando) { modelo.adicionarPorEndereco(endereco) }
        }
    }
}

@Composable
private fun BotaoDeProcurar(procurando: Boolean, acao: () -> Unit) {
    Button(onClick = acao, enabled = !procurando, modifier = Modifier.fillMaxWidth().height(52.dp)) {
        if (procurando) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            Spacer(Modifier.width(10.dp))
            Text("Procurando…")
        } else {
            Icon(Icons.Default.Search, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Procurar câmeras na rede")
        }
    }
}

@Composable
private fun CampoDeEndereco(endereco: String, aoMudar: (String) -> Unit, procurando: Boolean, adicionar: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = endereco,
            onValueChange = aoMudar,
            label = { Text("Endereço IP da câmera") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = adicionar, enabled = endereco.isNotBlank() && !procurando) { Text("Adicionar") }
    }
}

/** As câmeras guardadas: procurar de novo, adicionar por endereço, renomear e mudar a ordem. */
@Composable
fun TelaDeCameras(modelo: ModeloDoApp, fechar: () -> Unit) {
    val cameras by modelo.cameras.collectAsStateWithLifecycle()
    val procurando by modelo.procurando.collectAsStateWithLifecycle()
    var endereco by rememberSaveable { mutableStateOf("") }
    var renomeando by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Câmeras") },
                actions = { TextButton(onClick = fechar) { Text("OK") } },
            )
        },
    ) { recuo ->
        LazyColumn(Modifier.fillMaxSize().padding(recuo), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Text(
                    "Guardadas neste celular",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            itemsIndexed(cameras, key = { _, c -> c.id }) { i, c ->
                ListItem(
                    headlineContent = { Text(c.nome) },
                    supportingContent = { Text(c.modelo ?: "câmera") },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { renomeando = c.id }) { Icon(Icons.Default.Edit, contentDescription = "Renomear ${c.nome}") }
                            IconButton(onClick = { modelo.reordenar(mover(cameras.map { it.id }, i, i - 1)) }, enabled = i > 0) {
                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Subir ${c.nome}")
                            }
                            IconButton(onClick = { modelo.reordenar(mover(cameras.map { it.id }, i, i + 1)) }, enabled = i < cameras.size - 1) {
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Descer ${c.nome}")
                            }
                        }
                    },
                )
            }
            item {
                Dica("Toque no lápis para renomear; as setas mudam a ordem da visão geral.")
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Text(
                    "Procurar",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    BotaoDeProcurar(procurando) { modelo.procurarCameras() }
                    CampoDeEndereco(endereco, { endereco = it }, procurando) { modelo.adicionarPorEndereco(endereco) }
                }
            }
        }
    }

    renomeando?.let { id ->
        val camera = cameras.firstOrNull { it.id == id }
        if (camera == null) {
            renomeando = null
        } else {
            DialogoDeNome(
                titulo = "Renomear a câmera",
                inicial = camera.nome,
                rotulo = "Nome",
                confirmar = "Salvar",
                aoConfirmar = { modelo.renomear(camera, it); renomeando = null },
                aoCancelar = { renomeando = null },
            )
        }
    }
}

/** Uma caixa com um campo de texto: renomear a câmera, salvar uma posição. */
@Composable
fun DialogoDeNome(
    titulo: String,
    inicial: String,
    rotulo: String,
    confirmar: String,
    mensagem: String? = null,
    aoConfirmar: (String) -> Unit,
    aoCancelar: () -> Unit,
) {
    var texto by rememberSaveable { mutableStateOf(inicial) }
    AlertDialog(
        onDismissRequest = aoCancelar,
        title = { Text(titulo) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (mensagem != null) Text(mensagem, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(value = texto, onValueChange = { texto = it }, label = { Text(rotulo) }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { aoConfirmar(texto) }, enabled = texto.isNotBlank()) { Text(confirmar) } },
        dismissButton = { TextButton(onClick = aoCancelar) { Text("Cancelar") } },
    )
}

internal fun mover(ids: List<String>, de: Int, para: Int): List<String> {
    if (para !in ids.indices) return ids
    val lista = ids.toMutableList()
    lista.add(para, lista.removeAt(de))
    return lista
}
