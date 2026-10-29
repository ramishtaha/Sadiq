package com.ramish.sadiq.ui.onboarding.steps

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.ramish.sadiq.R
import com.ramish.sadiq.contract.SettingsTarget
import com.ramish.sadiq.ui.onboarding.OnboardingViewModel
import com.ramish.sadiq.ui.onboarding.OemStepScaffold
import com.ramish.sadiq.ui.onboarding.OnboardingPlan

/** O9: HyperOS battery saver. */
@Composable
fun BatterySaverStep(vm: OnboardingViewModel) {
    OemStepScaffold(
        icon = Icons.Filled.BatterySaver,
        headline = stringResource(R.string.onboarding_batterysaver_headline),
        body = stringResource(R.string.onboarding_batterysaver_body),
        actionLabel = stringResource(R.string.onboarding_batterysaver_action),
        onAction = { vm.openFix(SettingsTarget.MIUI_BATTERY_SAVER) },
        whyLabel = stringResource(R.string.onboarding_batterysaver_confirm_action),
        whyText = null, // This is a confirm button, not a "why" text
        // The content slot is used here for the self-confirm action triggered by tapping the whyLabel.
        // The whyLabel acts as a secondary action button in this context.
        content = { vm.selfConfirm(OnboardingPlan.StepId.BATTERY_SAVER) } // self-confirm
    )
}
