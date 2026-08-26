package com.jay.video.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Bg = Color(0xFF0A0E15)
val Bg2 = Color(0xFF101623)
val Panel = Color(0xFF141C2B)
val Primary = Color(0xFFE50914)
val Primary2 = Color(0xFFFF4D3D)
val Text1 = Color(0xFFE9EDF5)
val Text2 = Color(0xFF9AA7BD)
val Text3 = Color(0xFF5D6A80)
val BorderC = Color(0xFF1E2941)

private val JayColors = darkColorScheme(
    primary = Primary,
    onPrimary = Color.White,
    secondary = Primary2,
    onSecondary = Color.White,
    background = Bg,
    onBackground = Text1,
    surface = Bg2,
    onSurface = Text1,
    surfaceVariant = Panel,
    onSurfaceVariant = Text2,
    error = Color(0xFFFF5D5D),
    outline = BorderC,
)

@Composable
fun JayTheme(content: @Composable () -> Unit) {
    // 本应用固定深色影视风格（与网站一致）
    isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = JayColors,
        content = content,
    )
}
