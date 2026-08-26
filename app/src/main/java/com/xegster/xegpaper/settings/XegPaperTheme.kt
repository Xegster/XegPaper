package com.xegster.xegpaper.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val XegPaperColors = darkColorScheme(
    primary = Color(0xFF4CC9F0),
    onPrimary = Color(0xFF00202B),
    secondary = Color(0xFFF72585),
    onSecondary = Color(0xFF2B0014),
    background = Color(0xFF0B0F1A),
    onBackground = Color(0xFFE7ECF5),
    surface = Color(0xFF121828),
    onSurface = Color(0xFFE7ECF5),
    surfaceVariant = Color(0xFF1B2337),
    onSurfaceVariant = Color(0xFFAAB4C8),
    outline = Color(0xFF3A465F),
)

/** The app is a dark canvas by nature, so it stays dark regardless of system theme. */
@Composable
fun XegPaperTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = XegPaperColors, content = content)
}
