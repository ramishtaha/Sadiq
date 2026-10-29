package com.ramish.sadiq.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ramish.sadiq.ui.theme.Spacing
import com.ramish.sadiq.ui.theme.ThemeFlavor

/** Preview-only copy for the empty sky; the real string lives in WP-18's resources. */
private const val EmptySkyBody =
    "Each star is a moment you did what you meant. Say no to a feed once, " +
        "and the first one appears here."

/**
 * The one thing on an empty screen: a 64 dp glyph, a firm line, one grey line, and at most one
 * action. The column is capped at 320 dp so the text keeps a readable measure on a wide phone,
 * and it centres itself inside whatever the screen gives it.
 *
 * The glyph is decorative: the title and body carry the message, so it has no content description.
 *
 * @param iconTint exists because the database-error state uses the `error` colour on the same
 *   geometry; every other empty state leaves the default `primary`.
 */
@Composable
fun HeroEmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .widthIn(max = 320.dp)
            .fillMaxWidth()
            .padding(horizontal = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = iconTint
        )
        Spacer(Modifier.height(Spacing.xl))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(Spacing.xxl))
            PrimaryPillButton(text = actionLabel, onClick = onAction)
        }
    }
}

@Preview(name = "HeroEmptyState · Light", widthDp = 360, showBackground = true)
@Composable
private fun HeroEmptyStateLightPreview() {
    ComponentPreview(ThemeFlavor.LIGHT) {
        HeroEmptyState(
            icon = Icons.Filled.Star,
            title = "Your sky is waiting",
            body = EmptySkyBody,
            actionLabel = "Start focus",
            onAction = {}
        )
    }
}

@Preview(name = "HeroEmptyState · Dark", widthDp = 360, showBackground = true)
@Composable
private fun HeroEmptyStateDarkPreview() {
    ComponentPreview(ThemeFlavor.DARK) {
        HeroEmptyState(
            icon = Icons.Filled.Star,
            title = "Your sky is waiting",
            body = EmptySkyBody,
            actionLabel = "Start focus",
            onAction = {}
        )
    }
}

@Preview(name = "HeroEmptyState · Sepia", widthDp = 360, showBackground = true)
@Composable
private fun HeroEmptyStateSepiaPreview() {
    ComponentPreview(ThemeFlavor.SEPIA) {
        HeroEmptyState(
            icon = Icons.Filled.Star,
            title = "Your sky is waiting",
            body = EmptySkyBody,
            actionLabel = "Start focus",
            onAction = {}
        )
    }
}
