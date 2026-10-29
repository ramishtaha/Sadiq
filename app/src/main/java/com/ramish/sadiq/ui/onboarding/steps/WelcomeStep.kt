package com.ramish.sadiq.ui.onboarding.steps

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.ramish.sadiq.R
import com.ramish.sadiq.ui.onboarding.OnboardingViewModel
import com.ramish.sadiq.ui.onboarding.OemStepScaffold

/** O1: Welcome. */
@Composable
fun WelcomeStep(vm: OnboardingViewModel) {
    OemStepScaffold(
        icon = Icons.Filled.Info,
        headline = stringResource(R.string.onboarding_welcome_headline),
        body = stringResource(R.string.onboarding_welcome_body),
        actionLabel = stringResource(R.string.onboarding_welcome_action),
        onAction = { vm.next() }
    ) {
        // TODO: Add Arabic name as per spec if needed
    }
}
