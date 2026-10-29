package com.ramish.sadiq.ui.onboarding.steps

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.ramish.sadiq.R
import com.ramish.sadiq.contract.SettingsTarget
import com.ramish.sadiq.ui.onboarding.OnboardingViewModel
import com.ramish.sadiq.ui.onboarding.OemStepScaffold

/** O8: Battery optimization permission. */
@Composable
fun BatteryOptimizerStep(vm: OnboardingViewModel) {
    OemStepScaffold(
        icon = Icons.Filled.BatteryChargingFull,
        headline = stringResource(R.string.onboarding_battery_headline),
        body = stringResource(R.string.onboarding_battery_body),
        actionLabel = stringResource(R.string.onboarding_battery_action),
        onAction = { vm.openFix(SettingsTarget.BATTERY_OPT_REQUEST) }
    )
}
