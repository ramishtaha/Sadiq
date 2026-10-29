package com.ramish.sadiq.ui.onboarding.steps

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.ramish.sadiq.R
import com.ramish.sadiq.contract.SettingsTarget
import com.ramish.sadiq.ui.onboarding.OnboardingViewModel
import com.ramish.sadiq.ui.onboarding.OemStepScaffold

/** O5b: Restricted setting. */
@Composable
fun RestrictedStep(vm: OnboardingViewModel) {
    OemStepScaffold(
        icon = Icons.Filled.Block,
        headline = stringResource(R.string.onboarding_restricted_headline),
        body = stringResource(R.string.onboarding_restricted_body),
        actionLabel = stringResource(R.string.onboarding_restricted_action),
        onAction = { vm.openFix(SettingsTarget.APP_INFO) },
        whyLabel = stringResource(R.string.onboarding_restricted_why_action),
        whyText = stringResource(R.string.onboarding_restricted_why_text),
        // A phone that will not allow the service at all must still be able to finish setup: the
        // Health check reports the truth afterwards, and the Sky card says Sadiq is asleep.
        secondaryLabel = stringResource(R.string.onboarding_skip_action),
        onSecondary = { vm.skipCurrent() }
    )
}
