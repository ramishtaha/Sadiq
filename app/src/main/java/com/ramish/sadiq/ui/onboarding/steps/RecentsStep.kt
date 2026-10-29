package com.ramish.sadiq.ui.onboarding.steps

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.ramish.sadiq.R
import com.ramish.sadiq.ui.onboarding.OnboardingViewModel
import com.ramish.sadiq.ui.onboarding.OemStepScaffold
import com.ramish.sadiq.ui.onboarding.OnboardingPlan

/** O11: HyperOS lock in Recents. */
@Composable
fun RecentsStep(vm: OnboardingViewModel) {
    OemStepScaffold(
        icon = Icons.Filled.Lock,
        headline = stringResource(R.string.onboarding_recents_headline),
        body = stringResource(R.string.onboarding_recents_body),
        actionLabel = stringResource(R.string.onboarding_recents_action),
        // There is no settings page for the Recents gesture, so the button is the whole answer:
        // the user confirms it and the wizard moves on.
        onAction = { vm.confirmStep(OnboardingPlan.StepId.RECENTS) }
    )
}
