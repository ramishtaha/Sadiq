package com.ramish.sadiq.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

/** The three palettes Sadiq ships. [ThemeFlavor] is resolved from ThemeMode + the OS setting. */
enum class ThemeFlavor { LIGHT, DARK, SEPIA }

private val LightColors = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightTertiary,
    onTertiary = LightOnTertiary,
    tertiaryContainer = LightTertiaryContainer,
    onTertiaryContainer = LightOnTertiaryContainer,
    error = LightError,
    onError = LightOnError,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant
)

private val DarkColors = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkTertiary,
    onTertiary = DarkOnTertiary,
    tertiaryContainer = DarkTertiaryContainer,
    onTertiaryContainer = DarkOnTertiaryContainer,
    error = DarkError,
    onError = DarkOnError,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant
)

private val SepiaColors = lightColorScheme(
    primary = SepiaPrimary,
    onPrimary = SepiaOnPrimary,
    primaryContainer = SepiaPrimaryContainer,
    onPrimaryContainer = SepiaOnPrimaryContainer,
    secondary = SepiaSecondary,
    onSecondary = SepiaOnSecondary,
    secondaryContainer = SepiaSecondaryContainer,
    onSecondaryContainer = SepiaOnSecondaryContainer,
    tertiary = SepiaTertiary,
    onTertiary = SepiaOnTertiary,
    tertiaryContainer = SepiaTertiaryContainer,
    onTertiaryContainer = SepiaOnTertiaryContainer,
    error = SepiaError,
    onError = SepiaOnError,
    errorContainer = SepiaErrorContainer,
    onErrorContainer = SepiaOnErrorContainer,
    background = SepiaBackground,
    onBackground = SepiaOnBackground,
    surface = SepiaSurface,
    onSurface = SepiaOnSurface,
    surfaceVariant = SepiaSurfaceVariant,
    onSurfaceVariant = SepiaOnSurfaceVariant,
    outline = SepiaOutline,
    outlineVariant = SepiaOutlineVariant
)

/**
 * The one theme entry point. Dynamic colour is deliberately absent: Sadiq's identity is the warm
 * sand and the deep teal, and a Material-You repaint would erase it.
 */
@Composable
fun SadiqTheme(
    flavor: ThemeFlavor = ThemeFlavor.LIGHT,
    reduceMotion: Boolean = false,
    content: @Composable () -> Unit
) {
    val scheme = when (flavor) {
        ThemeFlavor.LIGHT -> LightColors
        ThemeFlavor.DARK -> DarkColors
        ThemeFlavor.SEPIA -> SepiaColors
    }
    CompositionLocalProvider(LocalReduceMotion provides reduceMotion) {
        MaterialTheme(
            colorScheme = scheme,
            typography = SadiqTypography,
            shapes = SadiqShapes,
            content = content
        )
    }
}

/** The page colour as an Android int, for the window background behind Compose. */
fun pageColor(flavor: ThemeFlavor): Color = when (flavor) {
    ThemeFlavor.LIGHT -> LightBackground
    ThemeFlavor.DARK -> DarkBackground
    ThemeFlavor.SEPIA -> SepiaBackground
}
