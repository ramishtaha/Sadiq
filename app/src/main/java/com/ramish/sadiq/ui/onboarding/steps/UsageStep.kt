package com.ramish.sadiq.ui.onboarding.steps

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.ramish.sadiq.R
import com.ramish.sadiq.contract.SettingsTarget
import com.ramish.sadiq.ui.onboarding.OnboardingViewModel
import com.ramish.sadiq.ui.onboarding.OemStepScaffold

/** O12: Usage access permission. */
@Composable
fun UsageStep(vm: OnboardingViewModel) {
    OemStepScaffold(
        icon = Icons.Filled.BarChart,
        headline = stringResource(R.string.onboarding_usage_headline),
        body = stringResource(R.string.onboarding_usage_body),
        actionLabel = stringResource(R.string.onboarding_usage_action),
        onAction = { vm.openFix(SettingsTarget.USAGE_ACCESS) },
        whyLabel = stringResource(R.string.onboarding_usage_why_action),
        whyText = stringResource(R.string.onboarding_usage_why_text)
    )
}
