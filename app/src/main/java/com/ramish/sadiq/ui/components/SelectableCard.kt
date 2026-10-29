package com.ramish.sadiq.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ramish.sadiq.ui.theme.Spacing
import com.ramish.sadiq.ui.theme.ThemeFlavor

/**
 * A whole-card radio option. The card is a [SadiqCard]; selection fills it with `primaryContainer`
 * and replaces the dark hairline with a 2 dp `primary` edge, so the choice is legible without
 * relying on colour alone.
 *
 * The selectable node wraps the card's content, not its padding, so the ripple lands above the fill
 * and TalkBack reads one node: title, subtitle, selected, radio button.
 */
@Composable
fun SelectableCard(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true
) {
    val scheme = MaterialTheme.colorScheme
    SadiqCard(
        modifier = modifier,
        contentPadding = PaddingValues(0.dp),
        containerColor = if (selected) scheme.primaryContainer else scheme.surface,
        contentColor = if (selected) scheme.onPrimaryContainer else scheme.onSurface,
        border = if (selected) BorderStroke(2.dp, scheme.primary) else null
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(
                    selected = selected,
                    enabled = enabled,
                    role = Role.RadioButton,
                    onClick = onClick
                )
                .padding(Spacing.xl)
        ) {
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (selected) scheme.onPrimaryContainer else scheme.onSurface
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (selected) {
                            scheme.onPrimaryContainer.copy(alpha = 0.8f)
                        } else {
                            scheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
    }
}

@Preview(name = "SelectableCard · Light", widthDp = 360, showBackground = true)
@Composable
private fun SelectableCardLightPreview() {
    ComponentPreview(ThemeFlavor.LIGHT) {
        SelectableCard(
            title = "Steady · 10 min, then 30 min rest",
            subtitle = "Shared across all your guarded apps",
            selected = true,
            onClick = {}
        )
        SelectableCard(
            title = "Gentle · 15 min, then 15 min rest",
            subtitle = "Shared across all your guarded apps",
            selected = false,
            onClick = {}
        )
    }
}

@Preview(name = "SelectableCard · Dark", widthDp = 360, showBackground = true)
@Composable
private fun SelectableCardDarkPreview() {
    ComponentPreview(ThemeFlavor.DARK) {
        SelectableCard(
            title = "Steady · 10 min, then 30 min rest",
            subtitle = "Shared across all your guarded apps",
            selected = true,
            onClick = {}
        )
        SelectableCard(
            title = "Gentle · 15 min, then 15 min rest",
            subtitle = "Shared across all your guarded apps",
            selected = false,
            onClick = {}
        )
    }
}

@Preview(name = "SelectableCard · Sepia", widthDp = 360, showBackground = true)
@Composable
private fun SelectableCardSepiaPreview() {
    ComponentPreview(ThemeFlavor.SEPIA) {
        SelectableCard(
            title = "Steady · 10 min, then 30 min rest",
            subtitle = "Shared across all your guarded apps",
            selected = true,
            onClick = {}
        )
        SelectableCard(
            title = "Gentle · 15 min, then 15 min rest",
            subtitle = "Shared across all your guarded apps",
            selected = false,
            onClick = {}
        )
    }
}
