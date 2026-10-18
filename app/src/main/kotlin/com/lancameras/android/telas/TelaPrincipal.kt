package com.lancameras.android.telas

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lancameras.android.sessao.ModeloDoApp

private enum class Sobreposicao { CAMERAS, AJUDA }

/**
 * A raiz do app: o cadastro enquanto não há câmeras; depois, a visão geral com a câmera aberta por
 * cima (a tela segue a seleção do modelo, que decide quais sessões ficam abertas).
 */
@Composable
fun TelaPrincipal(modelo: ModeloDoApp) {
    val cameras by modelo.cameras.collectAsStateWithLifecycle()
    val selecao by modelo.selecao.collectAsStateWithLifecycle()
    var sobre by remember { mutableStateOf<Sobreposicao?>(null) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            when {
                cameras.isEmpty() -> TelaDeCadastro(modelo)
                selecao is ModeloDoApp.Selecao.Camera -> {
                    BackHandler { modelo.selecionar(ModeloDoApp.Selecao.Geral) }
                    VisaoDaCamera(modelo, (selecao as ModeloDoApp.Selecao.Camera).id)
                }
                else -> VisaoGeral(modelo, abrirCameras = { sobre = Sobreposicao.CAMERAS }, abrirAjuda = { sobre = Sobreposicao.AJUDA })
            }
            sobre?.let { s ->
                BackHandler { sobre = null }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    when (s) {
                        Sobreposicao.CAMERAS -> TelaDeCameras(modelo) { sobre = null }
                        Sobreposicao.AJUDA -> Ajuda { sobre = null }
                    }
                }
            }
            AvisoView(modelo, Modifier.align(Alignment.BottomCenter))
        }
    }
}
