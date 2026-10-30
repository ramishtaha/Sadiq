package com.ramish.sadiq.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ramish.sadiq.ui.theme.Spacing
import com.ramish.sadiq.ui.theme.ThemeFlavor

/**
 * Which ink a [TextPillButton] uses. `ACCENT` is the 2 % tertiary accent, reserved for free scroll.
 *
 * `COST` is the discouraged door: `error` content with no container, the second sanctioned non-error red
 * after "Delete all history" (§A.5). It is a red line, never a filled red container, and never a red page.
 */
enum class TextPillVariant { PRIMARY, ACCENT, COST }

/**
 * The one commit button on a screen: a 56 dp primary pill, full width by default.
 *
 * The height is a minimum, not a fixed size, so a label at font scale 1.3 grows instead of clipping.
 * Disabled uses the muted onSurface pair rather than a faded primary, which on the warm page reads
 * as a different, weaker brand colour.
 *
 * @param icon optional 20 dp leading glyph (the star on a leave choice).
 * @param containerColor overridden only for a destructive confirm ([ConfirmSheet]).
 */
@Composable
fun PrimaryPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    iconContentDescription: String? = null,
    fillWidth: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary
) {
    val scheme = MaterialTheme.colorScheme
    PillSurface(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        fillWidth = fillWidth,
        minHeight = 56.dp,
        containerColor = if (enabled) containerColor else scheme.onSurface.copy(alpha = 0.12f),
        contentColor = if (enabled) contentColor else scheme.onSurface.copy(alpha = 0.38f),
        border = null
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = iconContentDescription,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(Spacing.sm))
        }
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * The secondary pill: same geometry as [PrimaryPillButton] on a `surfaceVariant` tonal fill. Used
 * for the second answer on a surface and for expanding chip rows.
 */
@Composable
fun TonalPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    iconContentDescription: String? = null,
    fillWidth: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentColor: Color = MaterialTheme.colorScheme.onSurface
) {
    val scheme = MaterialTheme.colorScheme
    PillSurface(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        fillWidth = fillWidth,
        minHeight = 56.dp,
        containerColor = if (enabled) containerColor else scheme.onSurface.copy(alpha = 0.12f),
        contentColor = if (enabled) contentColor else scheme.onSurface.copy(alpha = 0.38f),
        border = null
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = iconContentDescription,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(Spacing.sm))
        }
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * A text-only action: 48 dp of touch target with no container, so it can sit inside a row without
 * adding visual weight. [TextPillVariant.ACCENT] uses `tertiary`, which Sadiq spends only on free
 * scroll; [TextPillVariant.COST] uses `error` and is the last thing read on a surface — the hollow star
 * says "this one won't light" in the app's own language rather than with an emoji (§A.5).
 *
 * @param leadingIcon an optional 18 dp glyph before the label, the "Open anyway" star.
 * @param leadingIconContentDescription the glyph's description; null keeps it decorative.
 */
@Composable
fun TextPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    variant: TextPillVariant = TextPillVariant.PRIMARY,
    leadingIcon: ImageVector? = null,
    leadingIconContentDescription: String? = null,
    fillWidth: Boolean = false
) {
    val scheme = MaterialTheme.colorScheme
    val ink = when (variant) {
        TextPillVariant.PRIMARY -> scheme.primary
        TextPillVariant.ACCENT -> scheme.tertiary
        TextPillVariant.COST -> scheme.error
    }
    PillSurface(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        fillWidth = fillWidth,
        minHeight = 48.dp,
        containerColor = Color.Transparent,
        contentColor = if (enabled) ink else scheme.onSurface.copy(alpha = 0.38f),
        border = null
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = leadingIconContentDescription,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(Spacing.sm))
        }
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * One pressable pill. Surface's own clickable overload keeps the ripple above the fill and the
 * shape clipping consistent across all three variants.
 */
@Composable
private fun PillSurface(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    fillWidth: Boolean,
    minHeight: Dp,
    containerColor: Color,
    contentColor: Color,
    border: BorderStroke?,
    content: @Composable RowScope.() -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .heightIn(min = minHeight),
        enabled = enabled,
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = border
    ) {
        Row(
            modifier = Modifier
                .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
                .padding(horizontal = Spacing.xl),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}

@Preview(name = "PrimaryPillButton · Light", widthDp = 360, showBackground = true)
@Composable
private fun PrimaryPillButtonLightPreview() {
    ComponentPreview(ThemeFlavor.LIGHT) {
        PrimaryPillButton(
            text = "No, take me home",
            onClick = {},
            icon = Icons.Filled.Star,
            iconContentDescription = "Lights a star"
        )
    }
}

@Preview(name = "PrimaryPillButton · Dark", widthDp = 360, showBackground = true)
@Composable
private fun PrimaryPillButtonDarkPreview() {
    ComponentPreview(ThemeFlavor.DARK) {
        PrimaryPillButton(
            text = "No, take me home",
            onClick = {},
            icon = Icons.Filled.Star,
            iconContentDescription = "Lights a star"
        )
    }
}

@Preview(name = "PrimaryPillButton · Sepia", widthDp = 360, showBackground = true)
@Composable
private fun PrimaryPillButtonSepiaPreview() {
    ComponentPreview(ThemeFlavor.SEPIA) {
        PrimaryPillButton(
            text = "No, take me home",
            onClick = {},
            icon = Icons.Filled.Star,
            iconContentDescription = "Lights a star"
        )
    }
}

@Preview(name = "TonalPillButton · Light", widthDp = 360, showBackground = true)
@Composable
private fun TonalPillButtonLightPreview() {
    ComponentPreview(ThemeFlavor.LIGHT) {
        TonalPillButton(text = "Yes, I need it", onClick = {})
    }
}

@Preview(name = "TonalPillButton · Dark", widthDp = 360, showBackground = true)
@Composable
private fun TonalPillButtonDarkPreview() {
    ComponentPreview(ThemeFlavor.DARK) {
        TonalPillButton(text = "Yes, I need it", onClick = {})
    }
}

@Preview(name = "TonalPillButton · Sepia", widthDp = 360, showBackground = true)
@Composable
private fun TonalPillButtonSepiaPreview() {
    ComponentPreview(ThemeFlavor.SEPIA) {
        TonalPillButton(text = "Yes, I need it", onClick = {})
    }
}

@Preview(name = "TextPillButton · Light", widthDp = 360, showBackground = true)
@Composable
private fun TextPillButtonLightPreview() {
    ComponentPreview(ThemeFlavor.LIGHT) {
        TextPillButton(text = "Why?", onClick = {})
        TextPillButton(text = "Use free scroll · 8 min", onClick = {}, variant = TextPillVariant.ACCENT)
        TextPillButton(
            text = "Open anyway",
            onClick = {},
            variant = TextPillVariant.COST,
            leadingIcon = Icons.Outlined.StarBorder,
        )
    }
}

@Preview(name = "TextPillButton · Dark", widthDp = 360, showBackground = true)
@Composable
private fun TextPillButtonDarkPreview() {
    ComponentPreview(ThemeFlavor.DARK) {
        TextPillButton(text = "Why?", onClick = {})
        TextPillButton(text = "Use free scroll · 8 min", onClick = {}, variant = TextPillVariant.ACCENT)
        TextPillButton(
            text = "Open anyway",
            onClick = {},
            variant = TextPillVariant.COST,
            leadingIcon = Icons.Outlined.StarBorder,
        )
    }
}

@Preview(name = "TextPillButton · Sepia", widthDp = 360, showBackground = true)
@Composable
private fun TextPillButtonSepiaPreview() {
    ComponentPreview(ThemeFlavor.SEPIA) {
        TextPillButton(text = "Why?", onClick = {})
        TextPillButton(text = "Use free scroll · 8 min", onClick = {}, variant = TextPillVariant.ACCENT)
        TextPillButton(
            text = "Open anyway",
            onClick = {},
            variant = TextPillVariant.COST,
            leadingIcon = Icons.Outlined.StarBorder,
        )
    }
}
