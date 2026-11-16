package com.farmbot.assistant.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Bg = Color(0xFF0B0E14)
val Surface1 = Color(0xFF141A24)
val Surface2 = Color(0xFF1C2432)
val Cyan = Color(0xFF4DE1E1)
val Purple = Color(0xFFA78BFA)
val TextPrimary = Color(0xFFE9EEF7)
val TextSecondary = Color(0xFF8C9AB0)
val TextTertiary = Color(0xFF5C6880)
val Line = Color(0xFF26303F)
val Warn = Color(0xFFFFB020)
val Danger = Color(0xFFFF6B6B)

private val FarmBotColors = darkColorScheme(
    primary = Cyan,
    onPrimary = Color(0xFF04262B),
    secondary = Purple,
    onSecondary = Color(0xFF17102B),
    background = Bg,
    onBackground = TextPrimary,
    surface = Surface1,
    onSurface = TextPrimary,
    surfaceVariant = Surface2,
    onSurfaceVariant = TextSecondary,
    outline = Line,
    error = Danger,
)

@Composable
fun FarmBotTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = FarmBotColors, content = content)
}
