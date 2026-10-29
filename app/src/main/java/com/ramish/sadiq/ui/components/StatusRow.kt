package com.ramish.sadiq.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ramish.sadiq.ui.theme.SemanticColors
import com.ramish.sadiq.ui.theme.Spacing
import com.ramish.sadiq.ui.theme.ThemeFlavor

/** The three states a health row can be in. Colour is a second signal; the subtitle is the first. */
enum class StatusTone { SUCCESS, WARNING, UNKNOWN }

/**
 * One line of permission truth: a coloured 24 dp glyph, what is being checked, what was found, and
 * a "Fix" action when there is something to do. The row is 64 dp tall so a thumb can find it and
 * the two lines of text do not touch.
 *
 * `UNKNOWN` uses `outline`, never red: an unanswered check is not a failure.
 *
 * The glyph is decorative (`contentDescription = null`): the subtitle already states the condition
 * in words, so a description here would make TalkBack read the state twice.
 */
@Composable
fun StatusRow(
    title: String,
    subtitle: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    actionLabel: String = "Fix",
    onAction: (() -> Unit)? = null
) {
    val flavor = currentThemeFlavor()
    val tint = when (tone) {
        StatusTone.SUCCESS -> SemanticColors.success(flavor)
        StatusTone.WARNING -> SemanticColors.warning(flavor)
        StatusTone.UNKNOWN -> MaterialTheme.colorScheme.outline
    }
    val glyph = icon ?: when (tone) {
        StatusTone.SUCCESS -> Icons.Filled.CheckCircle
        StatusTone.WARNING -> Icons.Outlined.WarningAmber
        StatusTone.UNKNOWN -> Icons.AutoMirrored.Outlined.HelpOutline
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = glyph,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = tint
        )
        Spacer(Modifier.width(Spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (onAction != null) {
            Spacer(Modifier.width(Spacing.sm))
            TextPillButton(text = actionLabel, onClick = onAction)
        }
    }
}

@Preview(name = "StatusRow · Light", widthDp = 360, showBackground = true)
@Composable
private fun StatusRowLightPreview() {
    ComponentPreview(ThemeFlavor.LIGHT) {
        StatusRow(title = "Accessibility", subtitle = "On", tone = StatusTone.SUCCESS)
        StatusRow(
            title = "Usage access",
            subtitle = "Off",
            tone = StatusTone.WARNING,
            actionLabel = "Fix",
            onAction = {}
        )
        StatusRow(title = "Live updates", subtitle = "Not checked yet", tone = StatusTone.UNKNOWN)
    }
}

@Preview(name = "StatusRow · Dark", widthDp = 360, showBackground = true)
@Composable
private fun StatusRowDarkPreview() {
    ComponentPreview(ThemeFlavor.DARK) {
        StatusRow(title = "Accessibility", subtitle = "On", tone = StatusTone.SUCCESS)
        StatusRow(
            title = "Usage access",
            subtitle = "Off",
            tone = StatusTone.WARNING,
            actionLabel = "Fix",
            onAction = {}
        )
        StatusRow(title = "Live updates", subtitle = "Not checked yet", tone = StatusTone.UNKNOWN)
    }
}

@Preview(name = "StatusRow · Sepia", widthDp = 360, showBackground = true)
@Composable
private fun StatusRowSepiaPreview() {
    ComponentPreview(ThemeFlavor.SEPIA) {
        StatusRow(title = "Accessibility", subtitle = "On", tone = StatusTone.SUCCESS)
        StatusRow(
            title = "Usage access",
            subtitle = "Off",
            tone = StatusTone.WARNING,
            actionLabel = "Fix",
            onAction = {}
        )
        StatusRow(title = "Live updates", subtitle = "Not checked yet", tone = StatusTone.UNKNOWN)
    }
}
