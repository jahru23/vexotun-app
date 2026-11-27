package com.vexotun.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Refined VexoTun dark palette tuned to Portal Teal (#2EC4B6) over deep slate (#11161B).
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF2EC4B6),
    onPrimary = Color(0xFF081620),
    primaryContainer = Color(0xFF103D42),
    onPrimaryContainer = Color(0xFFB2DFDB),
    secondary = Color(0xFF80CBC4),
    onSecondary = Color(0xFF081620),
    secondaryContainer = Color(0xFF1F2A34),
    onSecondaryContainer = Color(0xFFD5DCE2),
    background = Color(0xFF11161B),
    onBackground = Color(0xFFF1F5F9),
    surface = Color(0xFF11161B),
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF182028),
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF2E3B49),
    outlineVariant = Color(0xFF26323E),
    error = Color(0xFFEF5350),
    onError = Color(0xFF3B0909)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF0D9488),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD8F3EF),
    onPrimaryContainer = Color(0xFF003831),
    secondary = Color(0xFF00796B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE3EEF4),
    onSecondaryContainer = Color(0xFF1E293B),
    background = Color(0xFFF4F7FA),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFF4F7FA),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFFFFFFF),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFCBD5E1),
    outlineVariant = Color(0xFFE2E8F0),
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
