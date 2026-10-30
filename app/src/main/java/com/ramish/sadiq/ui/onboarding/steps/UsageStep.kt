package com.ramish.sadiq.ui.onboarding.steps

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
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

/** O12: Usage access permission. */
@Composable
fun UsageStep(vm: OnboardingViewModel) {
    val state by vm.uiState.collectAsStateWithLifecycle()

    OemStepScaffold(
        icon = Icons.Filled.BarChart,
        headline = stringResource(R.string.onboarding_usage_headline),
        body = stringResource(R.string.onboarding_usage_body),
        actionLabel = stringResource(R.string.onboarding_usage_action),
        onAction = { vm.openFix(SettingsTarget.USAGE_ACCESS) },
        whyLabel = stringResource(R.string.onboarding_usage_why_action),
        whyText = stringResource(R.string.onboarding_usage_why_text),
        // Usage access is optional: the Week screen is worse without it, but the wizard is not
        // allowed to hold a user who will not grant it.
        secondaryLabel = stringResource(R.string.onboarding_usage_skip_action),
        onSecondary = { vm.skipCurrent() },
        // A5-13: HyperOS keeps usage access under Privacy protection, two taps from the list the
        // action button opens, and nothing said so.
        content = {
            OemPathNote(
                step = OnboardingPlan.StepId.USAGE,
                hyperOsMajor = state.perms.hyperOsMajor,
            )
        }
    )
}
