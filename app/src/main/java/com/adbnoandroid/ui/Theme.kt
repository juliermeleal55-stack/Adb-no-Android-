package com.adbnoandroid.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Paleta do app — Material 3 com tema claro e escuro.
 *
 * As cores "semânticas" do terminal/status ficam num objeto próprio
 * ([AdbColors]) para a UI usar a mesma cor em ambos os temas e diferenciar
 * com clareza: sucesso (verde), erro (vermelho), aviso (âmbar), info (azul).
 */
object AdbPalette {
    val Green = Color(0xFF2E9E5B)
    val GreenLight = Color(0xFF4ADE80)
    val Red = Color(0xFFD93B3B)
    val RedLight = Color(0xFFF87171)
    val Amber = Color(0xFFD29922)
    val AmberLight = Color(0xFFFBBF24)
    val Blue = Color(0xFF2F81F7)
    val BlueLight = Color(0xFF60A5FA)
    val Purple = Color(0xFF8B5CF6)
}

/** Cores semânticas resolvidas para o tema atual. */
data class AdbColors(
    val success: Color,
    val error: Color,
    val warning: Color,
    val info: Color,
    val accent: Color,
    val consoleBg: Color,
    val consoleText: Color,
    val consoleDim: Color,
    val consolePrompt: Color,
    val consoleError: Color,
    val consoleWarning: Color,
    val mono: FontFamily,
    val isDark: Boolean
)

val LocalAdbColors = staticCompositionLocalOf {
    AdbColors(
        success = AdbPalette.Green,
        error = AdbPalette.Red,
        warning = AdbPalette.Amber,
        info = AdbPalette.Blue,
        accent = AdbPalette.Purple,
        consoleBg = Color(0xFF0D1117),
        consoleText = Color(0xFFC9D1D9),
        consoleDim = Color(0xFF8B949E),
        consolePrompt = Color(0xFF4ADE80),
        consoleError = Color(0xFFF87171),
        consoleWarning = Color(0xFFFBBF24),
        mono = FontFamily.Monospace,
        isDark = true
    )
}

private val DarkScheme = darkColorScheme(
    primary = AdbPalette.GreenLight,
    onPrimary = Color(0xFF04120A),
    primaryContainer = Color(0xFF14361F),
    onPrimaryContainer = Color(0xFFB7F7CB),
    secondary = AdbPalette.BlueLight,
    onSecondary = Color(0xFF04121F),
    secondaryContainer = Color(0xFF12293F),
    onSecondaryContainer = Color(0xFFCBE6FF),
    tertiary = AdbPalette.AmberLight,
    onTertiary = Color(0xFF1F1503),
    background = Color(0xFF0B0F14),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF12181F),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF1B232C),
    onSurfaceVariant = Color(0xFF9BA7B4),
    surfaceContainer = Color(0xFF12181F),
    surfaceContainerHigh = Color(0xFF1A222B),
    surfaceContainerHighest = Color(0xFF212B36),
    outline = Color(0xFF3A4553),
    outlineVariant = Color(0xFF26303A),
    error = AdbPalette.RedLight,
    onError = Color(0xFF1A0505),
    errorContainer = Color(0xFF3B0F0F),
    onErrorContainer = Color(0xFFFFD7D5)
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF12703A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC4F2D5),
    onPrimaryContainer = Color(0xFF00210F),
    secondary = Color(0xFF1A5FB4),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD6E5FF),
    onSecondaryContainer = Color(0xFF001B3D),
    tertiary = Color(0xFF8A6100),
    onTertiary = Color.White,
    background = Color(0xFFF7F9FB),
    onBackground = Color(0xFF1A1C1E),
    surface = Color.White,
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFEDF1F5),
    onSurfaceVariant = Color(0xFF4A5560),
    surfaceContainer = Color(0xFFF1F4F7),
    surfaceContainerHigh = Color(0xFFEAEFF4),
    surfaceContainerHighest = Color(0xFFE2E8EE),
    outline = Color(0xFFB4BEC9),
    outlineVariant = Color(0xFFD5DCE3),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B)
)

private val AppTypography = Typography(
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp)
)

/**
 * Material You de verdade: no Android 12+ as cores vêm do papel de parede
 * ([dynamicDarkColorScheme]/[dynamicLightColorScheme]); abaixo disso o app cai
 * nos esquemas fixos. O console não segue o tema — saída de terminal precisa de
 * contraste próprio para os erros ficarem legíveis.
 */
@Composable
fun AdbTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val temCoresDoPapelDeParede = android.os.Build.VERSION.SDK_INT >= 31
    val scheme = when {
        !temCoresDoPapelDeParede -> if (darkTheme) DarkScheme else LightScheme
        darkTheme -> dynamicDarkColorScheme(LocalContext.current)
        else -> dynamicLightColorScheme(LocalContext.current)
    }
    val colors = if (darkTheme) {
        AdbColors(
            success = AdbPalette.GreenLight,
            error = AdbPalette.RedLight,
            warning = AdbPalette.AmberLight,
            info = AdbPalette.BlueLight,
            accent = Color(0xFFA78BFA),
            consoleBg = Color(0xFF06090D),
            consoleText = Color(0xFFC9D1D9),
            consoleDim = Color(0xFF8B949E),
            consolePrompt = Color(0xFF4ADE80),
            consoleError = Color(0xFFF87171),
            consoleWarning = Color(0xFFFBBF24),
            mono = FontFamily.Monospace,
            isDark = true
        )
    } else {
        AdbColors(
            success = Color(0xFF12703A),
            error = Color(0xFFB3261E),
            warning = Color(0xFF8A6100),
            info = Color(0xFF1A5FB4),
            accent = Color(0xFF6D28D9),
            consoleBg = Color(0xFFF6F8FA),
            consoleText = Color(0xFF1F2328),
            consoleDim = Color(0xFF5B6570),
            consolePrompt = Color(0xFF12703A),
            consoleError = Color(0xFFB3261E),
            consoleWarning = Color(0xFF8A6100),
            mono = FontFamily.Monospace,
            isDark = false
        )
    }
    CompositionLocalProvider(LocalAdbColors provides colors) {
        MaterialTheme(
            colorScheme = scheme,
            typography = AppTypography,
            content = content
        )
    }
}
