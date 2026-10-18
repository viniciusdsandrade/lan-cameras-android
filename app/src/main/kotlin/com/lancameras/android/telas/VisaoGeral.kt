package com.lancameras.android.telas

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lancameras.android.sessao.ModeloDoApp
import com.lancameras.android.sessao.SessaoDeVideo
import com.lancameras.android.video.LenteExibida
import com.lancameras.nucleo.modelo.CameraConfig

/** As câmeras lado a lado, cada uma com a lente móvel no fluxo leve. Tocar abre a câmera. */
@Composable
fun VisaoGeral(modelo: ModeloDoApp, abrirCameras: () -> Unit, abrirAjuda: () -> Unit) {
    val cameras by modelo.cameras.collectAsStateWithLifecycle()
    val sessoes by modelo.sessoesGerais.collectAsStateWithLifecycle()
    val paisagem = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    // uma coluna em retrato; em paisagem, até três lado a lado
    val colunas = if (paisagem) cameras.size.coerceIn(1, 3) else 1

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Câmeras") },
                navigationIcon = { IconButton(onClick = abrirAjuda) { Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = "Ajuda") } },
                actions = {
                    IconButton(onClick = { modelo.pararTodas() }) { Icon(Icons.Default.StopCircle, contentDescription = "Parar o movimento de todas as câmeras") }
                    IconButton(onClick = abrirCameras) { Icon(Icons.Default.Tune, contentDescription = "Câmeras: procurar, renomear e ordenar") }
                },
            )
        },
    ) { recuo ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(colunas),
            modifier = Modifier.fillMaxSize().padding(recuo),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(cameras, key = { it.id }) { camera ->
                CartaoDaCamera(camera, sessoes[camera.id]) { modelo.selecionar(ModeloDoApp.Selecao.Camera(camera.id)) }
            }
        }
    }
}

@Composable
private fun CartaoDaCamera(camera: CameraConfig, sessao: SessaoDeVideo?, abrir: () -> Unit) {
    val forma = RoundedCornerShape(10.dp)
    val clique = Modifier
        .fillMaxWidth()
        .clip(forma)
        .clickable(role = Role.Button, onClickLabel = "Abrir ${camera.nome}", onClick = abrir)
    if (sessao != null) {
        LenteComRotulo(
            rotulo = camera.nome,
            video = sessao,
            lente = if (camera.duasLentes) LenteExibida.MOVEL else LenteExibida.INTEIRA,
            modifier = clique,
        )
    } else {
        Box(clique.aspectRatio(16f / 9f).background(Color.Black).semantics { contentDescription = "${camera.nome}, pausado" }, contentAlignment = Alignment.Center) {
            Text("pausado", color = Color.White.copy(alpha = 0.7f))
            Etiqueta(Modifier.align(Alignment.TopStart).padding(8.dp)) { Text(camera.nome) }
        }
    }
}
