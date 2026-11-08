package com.adbnoandroid

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Paleta escura, estilo terminal
val TerminalBg = Color(0xFF0D1117)
val SurfaceDark = Color(0xFF161B22)
val ConsoleBg = Color(0xFF010409)
val TerminalGreen = Color(0xFF3FB950)
val TerminalRed = Color(0xFFF85149)
val TerminalAmber = Color(0xFFD29922)
val TerminalText = Color(0xFFC9D1D9)
val TerminalDim = Color(0xFF8B949E)

@Composable
fun AdbTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = TerminalGreen,
            onPrimary = Color.Black,
            secondary = TerminalDim,
            onSecondary = Color.Black,
            background = TerminalBg,
            onBackground = TerminalText,
            surface = SurfaceDark,
            onSurface = TerminalText,
            surfaceVariant = SurfaceDark,
            onSurfaceVariant = TerminalDim,
            error = TerminalRed,
            onError = Color.White
        ),
        content = content
    )
}
