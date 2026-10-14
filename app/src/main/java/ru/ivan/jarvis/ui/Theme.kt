package ru.ivan.jarvis.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFF4FD1FF)

private val JarvisColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF00283A),
    primaryContainer = Color(0xFF0E3A52),
    onPrimaryContainer = Color(0xFFC7EEFF),
    secondary = Color(0xFFFFB74D),
    onSecondary = Color(0xFF2E1A00),
    background = Color(0xFF0B1220),
    onBackground = Color(0xFFE3E8F2),
    surface = Color(0xFF0B1220),
    onSurface = Color(0xFFE3E8F2),
    surfaceVariant = Color(0xFF16213A),
    onSurfaceVariant = Color(0xFFA9B4C8),
    surfaceContainer = Color(0xFF111A2E),
    surfaceContainerHigh = Color(0xFF16213A),
    surfaceContainerHighest = Color(0xFF1C2944),
    outline = Color(0xFF3A4A68),
    error = Color(0xFFFF6E6E),
)

@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = JarvisColors, content = content)
}
