package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = TubeRed,
    onPrimary = Color.White,
    primaryContainer = TubeRedDark,
    onPrimaryContainer = Color.White,
    secondary = ShieldEmerald,
    onSecondary = TubeObsidian,
    secondaryContainer = ShieldEmeraldContainer,
    onSecondaryContainer = ShieldEmerald,
    background = TubeObsidian,
    onBackground = TubeTextPrimary,
    surface = TubeSurface,
    onSurface = TubeTextPrimary,
    surfaceVariant = TubeSurfaceElevated,
    onSurfaceVariant = TubeTextMuted,
    outline = TubeOutline,
)

private val LightColorScheme = lightColorScheme(
    primary = TubeRedDark,
    onPrimary = Color.White,
    primaryContainer = TubeRed,
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
fun TubeViewTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
