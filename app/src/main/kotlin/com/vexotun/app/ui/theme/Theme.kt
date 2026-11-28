package com.vexotun.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = SignalAmber,
    onPrimary = SignalAmberInk,
    primaryContainer = SignalAmberContainerDark,
    onPrimaryContainer = SignalAmberOnContainerDark,
    secondary = GraphiteMutedDark,
    onSecondary = GraphiteBgDark,
    secondaryContainer = Color(0xFF23262C),
    onSecondaryContainer = GraphiteTextDark,
    background = GraphiteBgDark,
    onBackground = GraphiteTextDark,
    surface = GraphiteBgDark,
    onSurface = GraphiteTextDark,
    surfaceVariant = GraphiteSurfaceDark,
    onSurfaceVariant = GraphiteMutedDark,
    outline = GraphiteOutlineDark,
    outlineVariant = GraphiteHairlineDark,
    error = ErrorRedDark,
    onError = OnErrorRedDark,
    errorContainer = StatusErrorBgDark,
    onErrorContainer = StatusErrorFgDark
)

private val LightColorScheme = lightColorScheme(
    primary = DeepAmber,
    onPrimary = DeepAmberInk,
    primaryContainer = DeepAmberContainerLight,
    onPrimaryContainer = DeepAmberOnContainerLight,
    secondary = PaperMutedLight,
    onSecondary = PaperSurfaceLight,
    secondaryContainer = Color(0xFFEBE8DF),
    onSecondaryContainer = PaperTextLight,
    background = PaperBgLight,
    onBackground = PaperTextLight,
    surface = PaperBgLight,
    onSurface = PaperTextLight,
    surfaceVariant = PaperSurfaceLight,
    onSurfaceVariant = PaperMutedLight,
    outline = PaperOutlineLight,
    outlineVariant = PaperHairlineLight,
    error = ErrorRedLight,
    onError = OnErrorRedLight,
    errorContainer = StatusErrorBgLight,
    onErrorContainer = StatusErrorFgLight
)

val LocalIsDarkTheme = staticCompositionLocalOf { true }

@Composable
fun VexotunTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalIsDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
            typography = Typography,
            content = content
        )
    }
}
