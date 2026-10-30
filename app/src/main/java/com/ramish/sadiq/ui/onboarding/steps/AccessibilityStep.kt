package com.ramish.sadiq.ui.onboarding.steps

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ramish.sadiq.R
import com.ramish.sadiq.contract.SettingsTarget
import com.ramish.sadiq.ui.components.TextPillButton
import com.ramish.sadiq.ui.onboarding.OemPathNote
import com.ramish.sadiq.ui.onboarding.OemPaths
import com.ramish.sadiq.ui.onboarding.OemStepScaffold
import com.ramish.sadiq.ui.onboarding.OnboardingPlan
import com.ramish.sadiq.ui.onboarding.OnboardingViewModel
import com.ramish.sadiq.ui.theme.Spacing

/**
 * O5: Accessibility permission.
 *
 * Two things sit under the body. The exact HyperOS path, because "open Accessibility" is not enough
 * on a phone whose list is called something else. And a link into O5b: this step has no Skip, so a
 * greyed-out switch used to leave no way forward except the tab bar, which is no longer drawn inside
 * the wizard.
 */
@Composable
fun AccessibilityStep(vm: OnboardingViewModel) {
    val state by vm.uiState.collectAsStateWithLifecycle()

    OemStepScaffold(
        icon = Icons.Filled.Accessibility,
        headline = stringResource(R.string.onboarding_accessibility_headline),
        body = stringResource(R.string.onboarding_accessibility_body),
        actionLabel = stringResource(R.string.onboarding_accessibility_action),
        onAction = { vm.openFix(SettingsTarget.ACCESSIBILITY) },
        whyLabel = stringResource(R.string.onboarding_accessibility_why_action),
        whyText = stringResource(R.string.onboarding_accessibility_why_text),
        content = {
            val hyperOsMajor = state.perms.hyperOsMajor
            val hasPath = OemPaths.pathFor(OnboardingPlan.StepId.ACCESSIBILITY, hyperOsMajor) != null
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (hasPath) {
                    OemPathNote(
                        step = OnboardingPlan.StepId.ACCESSIBILITY,
                        hyperOsMajor = hyperOsMajor,
                    )
                    Spacer(Modifier.height(Spacing.sm))
                }
                // The label is O5b's own headline: the user who needs it has just looked at the
                // switch, and the question is the one they are asking.
                TextPillButton(
                    text = stringResource(R.string.onboarding_restricted_headline),
                    onClick = { vm.showRestrictedFix() }
                )
            }
        }
    )
}
