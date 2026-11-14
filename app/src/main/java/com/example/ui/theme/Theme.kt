package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = YTubeRed,
    onPrimary = Color.White,
    primaryContainer = YTubeRedDark,
    onPrimaryContainer = Color.White,
    secondary = ShieldEmerald,
    onSecondary = YTubeObsidian,
    secondaryContainer = ShieldEmeraldContainer,
    onSecondaryContainer = ShieldEmerald,
    background = YTubeObsidian,
    onBackground = YTubeTextPrimary,
    surface = YTubeSurface,
    onSurface = YTubeTextPrimary,
    surfaceVariant = YTubeSurfaceElevated,
    onSurfaceVariant = YTubeTextMuted,
    outline = YTubeOutline,
)

private val LightColorScheme = lightColorScheme(
    primary = YTubeRedDark,
    onPrimary = Color.White,
    primaryContainer = YTubeRed,
    onPrimaryContainer = Color.White,
    secondary = ShieldEmerald,
    onSecondary = Color.White,
    background = LightBackground,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurfaceElevated,
    onSurfaceVariant = LightTextMuted,
)

@Composable
fun YTubeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
