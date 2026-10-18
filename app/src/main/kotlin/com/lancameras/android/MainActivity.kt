package com.lancameras.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.lancameras.android.apoio.Depuracao
import com.lancameras.android.sessao.ModeloDoApp
import com.lancameras.android.telas.TelaPrincipal
import com.lancameras.android.telas.TemaCameras
import kotlinx.coroutines.CompletableDeferred

class MainActivity : ComponentActivity() {
    private val app get() = application as CamerasApp
    private var pedidoPendente: CompletableDeferred<Boolean>? = null
    private val pedirPermissao = registerForActivityResult(ActivityResultContracts.RequestPermission()) { concedida ->
        pedidoPendente?.complete(concedida)
        pedidoPendente = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        app.plataforma.pedidorDePermissao = { permissao ->
            pedidoPendente?.cancel()
            val d = CompletableDeferred<Boolean>()
            pedidoPendente = d
            pedirPermissao.launch(permissao)
            d.await()
        }
        if (savedInstanceState == null) aplicarExtras(app.modelo)
        setContent { TemaCameras { TelaPrincipal(app.modelo) } }
    }

    /** O app já estava aberto e foi chamado de novo com extras (testes). */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        aplicarExtras(app.modelo)
    }

    /** O app perdeu o foco (ligação, outro app por cima): nada pode ficar se mexendo ou falando sozinho. */
    override fun onPause() {
        super.onPause()
        app.modelo.sessaoDaCamera.value?.soltarControles()
    }

    override fun onDestroy() {
        if (isFinishing) app.plataforma.pedidorDePermissao = null
        super.onDestroy()
    }

    /** Extras do `am start` (`--es abrir NOME`, `--es qualidade leve|hd`, `--es zoom N`): abrir direto num estado, para testes. */
    private fun aplicarExtras(modelo: ModeloDoApp) {
        Depuracao.registrar { "extras: abrir=${intent.getStringExtra("abrir")} qualidade=${intent.getStringExtra("qualidade")} zoom=${intent.getStringExtra("zoom")}" }
        intent.getStringExtra("qualidade")?.let { modelo.preferencias.definirQualidadeHD(it != "leve") }
        intent.getStringExtra("zoom")?.toDoubleOrNull()?.let { modelo.zoomInicial = it }
        intent.getStringExtra("abrir")?.let { nome -> modelo.cameraPeloNome(nome)?.let { modelo.selecionar(ModeloDoApp.Selecao.Camera(it.id)) } }
    }
}
