package com.vexotun.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Neutral Obsidian dark palette: avoids cliché blue-tinted AI dashboard colors.
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF4DB6AC),
    onPrimary = Color(0xFF07211E),
    primaryContainer = Color(0xFF163835),
    onPrimaryContainer = Color(0xFFB2DFDB),
    secondary = Color(0xFF80CBC4),
    onSecondary = Color(0xFF0A2421),
    secondaryContainer = Color(0xFF1F2429),
    onSecondaryContainer = Color(0xFFD5DCE2),
    background = Color(0xFF111315),
    onBackground = Color(0xFFECEFF1),
    surface = Color(0xFF111315),
    onSurface = Color(0xFFECEFF1),
    surfaceVariant = Color(0xFF1B1E22),
    onSurfaceVariant = Color(0xFF9AA2AC),
    outline = Color(0xFF2B3036),
    outlineVariant = Color(0xFF21252B),
    error = Color(0xFFEF6C6C),
    onError = Color(0xFF3B0909)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF00796B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDDF3F0),
    onPrimaryContainer = Color(0xFF003831),
    secondary = Color(0xFF00897B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8ECEF),
    onSecondaryContainer = Color(0xFF1E2429),
    background = Color(0xFFF6F7F9),
    onBackground = Color(0xFF14171A),
    surface = Color(0xFFF6F7F9),
    onSurface = Color(0xFF14171A),
    surfaceVariant = Color(0xFFFFFFFF),
    onSurfaceVariant = Color(0xFF5F6872),
    outline = Color(0xFFD5DCE2),
    outlineVariant = Color(0xFFE7ECF0),
    error = Color(0xFFD32F2F),
    onError = Color(0xFFFFFFFF)
)

@Composable
fun VexotunTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        content = content
    )
}
