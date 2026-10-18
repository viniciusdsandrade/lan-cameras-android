package com.lancameras.android.telas

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Vídeo em fundo escuro, como num visor: o app é sempre escuro. */
@Composable
fun TemaCameras(conteudo: @Composable () -> Unit) {
    val esquema = darkColorScheme(
        primary = Color(0xFF5AC8FA),
        onPrimary = Color(0xFF00202E),
        primaryContainer = Color(0xFF0B4A66),
        onPrimaryContainer = Color(0xFFCDEBFF),
        secondary = Color(0xFF9AD0F5),
        background = Color(0xFF0A0F1A),
        onBackground = Color(0xFFE6EAF2),
        surface = Color(0xFF111827),
        onSurface = Color(0xFFE6EAF2),
        surfaceVariant = Color(0xFF1C2535),
        onSurfaceVariant = Color(0xFFA9B4C6),
        surfaceContainer = Color(0xFF161E2E),
        surfaceContainerHigh = Color(0xFF1C2535),
        error = Color(0xFFFF6B6B),
        onError = Color(0xFF2A0000),
    )
    MaterialTheme(colorScheme = esquema, content = conteudo)
}
