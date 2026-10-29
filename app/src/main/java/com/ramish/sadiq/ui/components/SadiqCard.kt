package com.ramish.sadiq.ui.components

// CONTRACT-GAP: ui/theme (WP-01) exposes no LocalThemeFlavor, while SemanticColors.success /
// warning / failure take a ThemeFlavor. currentThemeFlavor() is the smallest local helper: it
// reads the page colour SadiqTheme installs and maps it back to the palette. Replace it with a
// theme-provided CompositionLocal once one exists.

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ramish.sadiq.ui.theme.DARK_BACKGROUND
import com.ramish.sadiq.ui.theme.SEPIA_BACKGROUND
import com.ramish.sadiq.ui.theme.SadiqTheme
import com.ramish.sadiq.ui.theme.Spacing
import com.ramish.sadiq.ui.theme.ThemeFlavor

/**
 * Which of the three palettes is active. SadiqTheme installs exact page colours, so matching the
 * background is unambiguous and survives both `ThemeMode.SYSTEM` and the forced-dark intercept.
 */
@Composable
internal fun currentThemeFlavor(): ThemeFlavor = when (MaterialTheme.colorScheme.background) {
    Color(DARK_BACKGROUND) -> ThemeFlavor.DARK
    Color(SEPIA_BACKGROUND) -> ThemeFlavor.SEPIA
    else -> ThemeFlavor.LIGHT
}

/** True when the dark palette is active; the one place the card hairline is allowed to appear. */
@Composable
internal fun isDarkPalette(): Boolean = currentThemeFlavor() == ThemeFlavor.DARK

/**
 * The house card: a raised `surface` panel with 20 dp corners and 20 dp of inner padding.
 *
 * In the dark palette a 1 dp `outlineVariant` hairline is drawn as well. Dark surfaces are close in
 * luminance to the page, and on an OLED phone the 2 dp shadow disappears entirely, so the hairline
 * is what makes a card a card. Pass [border] to replace it (a selected [SelectableCard] does).
 *
 * @param contentPadding inner padding; screens that host a full-bleed canvas pass `PaddingValues(0.dp)`.
 * @param onClick when non-null the whole card becomes one touch target with a ripple.
 */
@Composable
fun SadiqCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(Spacing.xl),
    onClick: (() -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = MaterialTheme.shapes.large
    val resolvedBorder = border ?: if (isDarkPalette()) {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    } else {
        null
    }
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            color = containerColor,
            contentColor = contentColor,
            tonalElevation = 0.dp,
            shadowElevation = 2.dp,
            border = resolvedBorder
        ) {
            Column(modifier = Modifier.padding(contentPadding), content = content)
        }
    } else {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            color = containerColor,
            contentColor = contentColor,
            tonalElevation = 0.dp,
            shadowElevation = 2.dp,
            border = resolvedBorder
        ) {
            Column(modifier = Modifier.padding(contentPadding), content = content)
        }
    }
}

/**
 * The shared preview host for the kit. Every component preview needs the real theme and the real
 * page colour; keeping that here stops fourteen copies of the same scaffold.
 */
@Composable
internal fun ComponentPreview(
    flavor: ThemeFlavor,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    SadiqTheme(flavor = flavor) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(modifier = modifier.fillMaxWidth().padding(Spacing.xl), content = content)
        }
    }
}

@Preview(name = "SadiqCard · Light", widthDp = 360, showBackground = true)
@Composable
private fun SadiqCardLightPreview() {
    ComponentPreview(ThemeFlavor.LIGHT) {
        SadiqCard {
            Text(text = "Guarding 5 apps", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "Shared across all your guarded apps",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Preview(name = "SadiqCard · Dark", widthDp = 360, showBackground = true)
@Composable
private fun SadiqCardDarkPreview() {
    ComponentPreview(ThemeFlavor.DARK) {
        SadiqCard {
            Text(text = "Guarding 5 apps", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "Shared across all your guarded apps",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Preview(name = "SadiqCard · Sepia", widthDp = 360, showBackground = true)
@Composable
private fun SadiqCardSepiaPreview() {
    ComponentPreview(ThemeFlavor.SEPIA) {
        SadiqCard {
            Text(text = "Guarding 5 apps", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "Shared across all your guarded apps",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
