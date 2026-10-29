package com.ramish.sadiq.ui.onboarding.steps

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.ramish.sadiq.R
import com.ramish.sadiq.contract.SettingsTarget
import com.ramish.sadiq.ui.onboarding.OnboardingViewModel
import com.ramish.sadiq.ui.onboarding.OemStepScaffold
import com.ramish.sadiq.ui.onboarding.OnboardingPlan

/** O10: HyperOS Autostart permission. */
@Composable
fun AutostartStep(vm: OnboardingViewModel) {
    OemStepScaffold(
        icon = Icons.Filled.PowerSettingsNew,
        headline = stringResource(R.string.onboarding_autostart_headline),
        body = stringResource(R.string.onboarding_autostart_body),
        actionLabel = stringResource(R.string.onboarding_autostart_action),
        onAction = { vm.openFix(SettingsTarget.MIUI_AUTOSTART) },
        whyLabel = stringResource(R.string.onboarding_autostart_confirm_action),
        whyText = null,
        content = { vm.selfConfirm(OnboardingPlan.StepId.AUTOSTART) }
    )
}
