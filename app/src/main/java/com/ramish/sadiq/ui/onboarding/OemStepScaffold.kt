package com.ramish.sadiq.ui.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ramish.sadiq.R
import com.ramish.sadiq.ui.components.ComponentPreview
import com.ramish.sadiq.ui.components.PrimaryPillButton
import com.ramish.sadiq.ui.components.TextPillButton
import com.ramish.sadiq.ui.components.TonalPillButton
import com.ramish.sadiq.ui.theme.Motion
import com.ramish.sadiq.ui.theme.Spacing
import com.ramish.sadiq.ui.theme.ThemeFlavor

/** Room under the scrolling content for the pinned bar: three pill buttons need 56 dp more. */
private val BOTTOM_INSET = 112.dp

/** The taller bar's inset, for the one step that also offers "Back to the step" (O5b). */
private val BOTTOM_INSET_WITH_TERTIARY = 168.dp

/**
 * The canonical layout of an onboarding step: icon, headline, body, action, optional why.
 *
 * Used by all 14 steps, so the typography, spacing and animation are consistent.
 * The only motion is the `Why?` expanding: it happens only once per wizard, it is quick, and the
 * text is not scrollable, so an animation here does not distract from the main intent.
 *
 * @param icon a 64 dp icon for this step.
 * @param headline the main text.
 * @param body the secondary text.
 * @param actionLabel the primary action's label.
 * @param onAction what the primary action does.
 * @param whyLabel the optional "Why?" action's label.
 * @param whyText the optional text to show when "Why?" is tapped.
 * @param secondaryLabel an optional second action, drawn above the primary. Self-confirm steps use
 *   it for "I set it", and permissions that can be declined use it for "Skip for now".
 * @param onSecondary what that second action does.
 * @param tertiaryLabel an optional third action, drawn above the secondary in the quiet text style.
 *   O5b uses it for "Back to the step", so the way back is on the screen rather than in the system
 *   Back gesture.
 * @param onTertiary what that third action does.
 * @param modifier for padding the whole step.
 * @param actionEnabled whether the primary action is enabled.
 * @param actionIcon an optional icon for the primary action.
 * @param actionIconContentDescription content description for the action icon.
 * @param content extra composables, shown below the body text.
 */
@Composable
fun OemStepScaffold(
    icon: ImageVector,
    headline: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    actionEnabled: Boolean = true,
    actionIcon: ImageVector? = null,
    actionIconContentDescription: String? = null,
    whyLabel: String? = null,
    whyText: String? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    tertiaryLabel: String? = null,
    onTertiary: (() -> Unit)? = null,
    content: @Composable (() -> Unit)? = null,
) {
    var showWhy by remember { mutableStateOf(false) }
    // The action must stay reachable. A scrolling Column cannot give a child weight(), so the button
    // used to be pushed below a long apps list and the wizard looked like a dead end. The content
    // scrolls; the action is pinned to the bottom. A step with a third action (O5b) pins a taller
    // bar, so its content is given more room to clear it.
    val contentBottomInset = if (tertiaryLabel != null) BOTTOM_INSET_WITH_TERTIARY else BOTTOM_INSET
    Box(modifier = modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.xl)
            .padding(bottom = contentBottomInset),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(Spacing.xxl))
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(Spacing.xxl))
        Text(
            text = headline,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(Spacing.md))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (content != null) {
            Spacer(Modifier.height(Spacing.xl))
            content()
        }
        if (whyLabel != null && whyText != null) {
            Spacer(Modifier.height(Spacing.xl))
            TextPillButton(text = whyLabel, onClick = { showWhy = !showWhy })
            AnimatedVisibility(
                visible = showWhy,
                enter = expandVertically(animationSpec = tween(Motion.NORMAL)),
                exit = shrinkVertically(animationSpec = tween(Motion.NORMAL)),
            ) {
                Text(
                    text = whyText,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.xl)
                )
            }
        }
    }

    Column(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .padding(horizontal = Spacing.xl, vertical = Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (tertiaryLabel != null && onTertiary != null) {
            TextPillButton(text = tertiaryLabel, onClick = onTertiary)
            Spacer(Modifier.height(Spacing.sm))
        }
        if (secondaryLabel != null && onSecondary != null) {
            TonalPillButton(text = secondaryLabel, onClick = onSecondary)
            Spacer(Modifier.height(Spacing.sm))
        }
        PrimaryPillButton(
            text = actionLabel,
            onClick = onAction,
            enabled = actionEnabled,
            icon = actionIcon,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    }
}

@Preview(name = "OemStepScaffold · Light", widthDp = 360, showBackground = true)
@Composable
private fun OemStepScaffoldLightPreview() {
    ComponentPreview(ThemeFlavor.LIGHT) {
        OemStepScaffold(
            icon = Icons.Filled.Shield,
            headline = "Let Sadiq see which app is open.",
            body = "That's all it sees. No internet. Nothing leaves this phone.",
            actionLabel = "Turn on Sadiq",
            onAction = {},
            whyLabel = "Why?",
            whyText = "Android has no other way to know when you open a feed. Sadiq can't close apps. It can ask, and take you home."
        )
    }
}
