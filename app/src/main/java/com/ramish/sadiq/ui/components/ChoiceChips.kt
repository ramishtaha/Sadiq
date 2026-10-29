package com.ramish.sadiq.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ramish.sadiq.ui.theme.Spacing
import com.ramish.sadiq.ui.theme.ThemeFlavor

/**
 * A wrapping row of single-choice pills: 40 dp tall, 8 dp apart, and never a dropdown.
 *
 * The chips are generic over the option type so a screen can pass minutes, sound kinds, or strings
 * directly instead of mapping them to indices. Selection is announced as a radio button, because
 * exactly one option is always chosen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceChips(
    options: List<T>,
    selected: T?,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: (T) -> String
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        options.forEach { option ->
            ChoiceChip(
                label = label(option),
                selected = option == selected,
                enabled = enabled,
                onClick = { onSelect(option) }
            )
        }
    }
}

/**
 * One pill. Unselected chips carry the 1 dp `outlineVariant` hairline so they read as pressable on
 * the page; the selected chip switches to `primaryContainer` and drops the outline. The transparent
 * border on the selected chip keeps both states the same size, so the row never reflows on a tap.
 */
@Composable
private fun ChoiceChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) scheme.primaryContainer else scheme.surface)
            .border(
                width = 1.dp,
                color = if (selected) Color.Transparent else scheme.outlineVariant,
                shape = CircleShape
            )
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick
            )
            .heightIn(min = 40.dp)
            .padding(horizontal = Spacing.lg),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) scheme.onPrimaryContainer else scheme.onSurface
        )
    }
}

@Preview(name = "ChoiceChips · Light", widthDp = 360, showBackground = true)
@Composable
private fun ChoiceChipsLightPreview() {
    ComponentPreview(ThemeFlavor.LIGHT) {
        ChoiceChips(
            options = listOf(5, 15, 25, 45, 60, 90),
            selected = 25,
            onSelect = {},
            label = { "$it min" }
        )
    }
}

@Preview(name = "ChoiceChips · Dark", widthDp = 360, showBackground = true)
@Composable
private fun ChoiceChipsDarkPreview() {
    ComponentPreview(ThemeFlavor.DARK) {
        ChoiceChips(
            options = listOf(5, 15, 25, 45, 60, 90),
            selected = 25,
            onSelect = {},
            label = { "$it min" }
        )
    }
}

@Preview(name = "ChoiceChips · Sepia", widthDp = 360, showBackground = true)
@Composable
private fun ChoiceChipsSepiaPreview() {
    ComponentPreview(ThemeFlavor.SEPIA) {
        ChoiceChips(
            options = listOf(5, 15, 25, 45, 60, 90),
            selected = 25,
            onSelect = {},
            label = { "$it min" }
        )
    }
}
