package com.lancameras.android.telas

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun Ajuda(fechar: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Ajuda") }, actions = { TextButton(onClick = fechar) { Text("OK") } }) }) { recuo ->
        LazyColumn(Modifier.fillMaxSize().padding(recuo), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { Titulo("Como usar") }
            item { Bloco("Mover a câmera", "Segure uma seta: a câmera se mexe enquanto você segura e para ao soltar. O quadrado do meio para na hora. A velocidade (lenta, média, rápida) fica logo abaixo das setas.") }
            item { Bloco("Lentes", "Cada câmera tem duas lentes no mesmo quadro: a fixa (em cima) e a móvel (embaixo). Só a móvel gira. A lente fixa aparece abaixo do vídeo quando você liga \"Mostrar a lente fixa\".") }
            item { Bloco("Zoom", "Pinça ou toque duplo no vídeo ampliam; com zoom, arraste para andar pela imagem; toque duplo de novo volta ao normal. O zoom é digital: as câmeras não têm zoom óptico.") }
            item { Bloco("Tela cheia", "Gire o celular: o vídeo ocupa a tela inteira, com as setas, o zoom e os botões principais por cima. Um toque no vídeo mostra ou esconde os controles.") }
            item { Bloco("Rastreio automático", "Com o rastreio ligado, a câmera segue o movimento e depois volta sozinha ao ponto de vigia. Por isso um movimento manual \"se desfaz\": desligue o rastreio se quiser que ela fique onde você deixou.") }
            item { Bloco("Posições salvas", "\"Salvar a posição atual\" guarda para onde a câmera aponta. O app só apaga as posições que ele mesmo criou; as que já vinham na câmera (com cadeado) ficam intactas.") }
            item { Bloco("Som", "\"Ouvir\" liga o som da câmera. \"Segure para falar\" usa o microfone do celular enquanto o dedo estiver no botão, e a câmera repete no alto-falante dela; enquanto você fala o som da câmera fica mudo para não dar eco. Há também sons prontos e texto falado. O volume começa em 30%: o alto-falante é alto.") }
            item { Bloco("Senha das câmeras", "Você não digita senha: o app pega o usuário e a senha com a própria câmera. Se o app do fabricante (iCSee, XMEye) trocar a senha dela, ou o roteador mudar o endereço, o app percebe e pega os novos sozinho. Se mesmo assim a imagem não voltar, abra Câmeras (o ícone de ajustes no alto) e toque em Procurar câmeras na rede.") }
            item { Titulo("Permissões") }
            item { Bloco("Rede local", "Necessária para achar as câmeras e falar com elas. No Android 17 ela aparece como \"dispositivos próximos\" nas permissões do app.") }
            item { Bloco("Microfone", "Só quando você segura \"Falar\".") }
            item { Bloco("Fotos", "As fotos vão para a galeria, na pasta \"Câmeras de Casa\"; o app não precisa ver as suas outras fotos.") }
            item { Titulo("Privacidade") }
            item {
                Text(
                    "Usuário e senha das câmeras ficam só neste celular, cifrados com uma chave do próprio aparelho e fora do backup. O app não abre nenhuma porta e só fala com as câmeras do seu Wi-Fi; nada vai para a internet. Em segundo plano ele solta as câmeras na hora e as reabre quando você volta.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Titulo(texto: String) {
    Text(texto, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Bloco(titulo: String, texto: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(titulo, style = MaterialTheme.typography.titleMedium)
        Text(texto, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
