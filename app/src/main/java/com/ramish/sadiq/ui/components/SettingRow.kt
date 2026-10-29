package com.ramish.sadiq.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ramish.sadiq.ui.theme.Spacing
import com.ramish.sadiq.ui.theme.ThemeFlavor

/**
 * One settings line: a title, an optional grey line, and a switch or a chevron.
 *
 * A row can do both things at once: "Bedtime · 22:30 → Fajr" toggles with its switch and opens the
 * bedtime screen when the row itself is tapped. When there is no separate destination, the whole
 * row becomes the switch's toggle target, which is the Material pattern that keeps TalkBack from
 * announcing two controls for one setting.
 *
 * The chevron is decorative: "opens a screen" is already announced by the row's click action.
 */
@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    checked: Boolean? = null,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    showChevron: Boolean = false,
    enabled: Boolean = true
) {
    val hasSwitch = checked != null && onCheckedChange != null
    val rowModifier = when {
        onClick != null -> Modifier.clickable(enabled = enabled, onClick = onClick)
        hasSwitch -> Modifier.toggleable(
            value = checked == true,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = { onCheckedChange?.invoke(it) }
        )
        else -> Modifier
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(rowModifier)
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (hasSwitch) {
            Spacer(Modifier.width(Spacing.md))
            Switch(
                checked = checked == true,
                onCheckedChange = if (onClick != null) onCheckedChange else null,
                enabled = enabled
            )
        }
        if (showChevron) {
            Spacer(Modifier.width(Spacing.xs))
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Preview(name = "SettingRow · Light", widthDp = 360, showBackground = true)
@Composable
private fun SettingRowLightPreview() {
    ComponentPreview(ThemeFlavor.LIGHT) {
        SettingRow(
            title = "Bedtime · 22:30 → Fajr",
            subtitle = "Fajr is at 05:21",
            checked = true,
            onCheckedChange = {},
            onClick = {}
        )
        SettingRow(title = "Prayer times", subtitle = "Karachi · Hanafi · Thane", showChevron = true, onClick = {})
        SettingRow(title = "Keep Sadiq running", checked = true, onCheckedChange = {})
    }
}

@Preview(name = "SettingRow · Dark", widthDp = 360, showBackground = true)
@Composable
private fun SettingRowDarkPreview() {
    ComponentPreview(ThemeFlavor.DARK) {
        SettingRow(
            title = "Bedtime · 22:30 → Fajr",
            subtitle = "Fajr is at 05:21",
            checked = true,
            onCheckedChange = {},
            onClick = {}
        )
        SettingRow(title = "Prayer times", subtitle = "Karachi · Hanafi · Thane", showChevron = true, onClick = {})
        SettingRow(title = "Keep Sadiq running", checked = true, onCheckedChange = {})
    }
}

@Preview(name = "SettingRow · Sepia", widthDp = 360, showBackground = true)
@Composable
private fun SettingRowSepiaPreview() {
    ComponentPreview(ThemeFlavor.SEPIA) {
        SettingRow(
            title = "Bedtime · 22:30 → Fajr",
            subtitle = "Fajr is at 05:21",
            checked = true,
            onCheckedChange = {},
            onClick = {}
        )
        SettingRow(title = "Prayer times", subtitle = "Karachi · Hanafi · Thane", showChevron = true, onClick = {})
        SettingRow(title = "Keep Sadiq running", checked = true, onCheckedChange = {})
    }
}
