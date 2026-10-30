package com.ramish.sadiq.ui.onboarding.steps

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ramish.sadiq.R
import com.ramish.sadiq.contract.SettingsTarget
import com.ramish.sadiq.ui.onboarding.OemPathNote
import com.ramish.sadiq.ui.onboarding.OemStepScaffold
import com.ramish.sadiq.ui.onboarding.OnboardingPlan
import com.ramish.sadiq.ui.onboarding.OnboardingViewModel

/** O5b: Restricted setting. */
@Composable
fun RestrictedStep(vm: OnboardingViewModel) {
    val state by vm.uiState.collectAsStateWithLifecycle()

    OemStepScaffold(
        icon = Icons.Filled.Block,
        headline = stringResource(R.string.onboarding_restricted_headline),
        body = stringResource(R.string.onboarding_restricted_body),
        actionLabel = stringResource(R.string.onboarding_restricted_action),
        onAction = { vm.openFix(SettingsTarget.APP_INFO) },
        whyLabel = stringResource(R.string.onboarding_restricted_why_action),
        whyText = stringResource(R.string.onboarding_restricted_why_text),
        // A5-05: the way back to O5, on the screen. The visible choices used to be "Open App info"
        // again or "Skip for now", and Skip gives up accessibility entirely, so a user who had just
        // allowed restricted settings had to guess that the system Back gesture returns him to the
        // switch. Going back is not an answer to O5b, so it leaves the step offered.
        tertiaryLabel = stringResource(R.string.onboarding_restricted_back_action),
        onTertiary = { vm.backToAccessibility() },
        // A phone that will not allow the service at all must still be able to finish setup: the
        // Health check reports the truth afterwards, and the Sky card says Sadiq is asleep.
        secondaryLabel = stringResource(R.string.onboarding_skip_action),
        onSecondary = { vm.skipCurrent() },
        // A5-13: "Allow restricted settings" is behind App info's ⋮ menu, and nothing said so. The
        // note is empty on a build whose version nobody has read the menus on.
        content = {
            OemPathNote(
                step = OnboardingPlan.StepId.RESTRICTED_SETTING,
                hyperOsMajor = state.perms.hyperOsMajor,
            )
        }
    )
}
