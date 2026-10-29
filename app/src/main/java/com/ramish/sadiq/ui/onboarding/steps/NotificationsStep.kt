package com.ramish.sadiq.ui.onboarding.steps

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.ramish.sadiq.R
import com.ramish.sadiq.contract.SettingsTarget
import com.ramish.sadiq.ui.onboarding.OnboardingViewModel
import com.ramish.sadiq.ui.onboarding.OemStepScaffold

/** O6: Notifications permission. */
@Composable
fun NotificationsStep(vm: OnboardingViewModel) {
    OemStepScaffold(
        icon = Icons.Filled.Notifications,
        headline = stringResource(R.string.onboarding_notifications_headline),
        body = stringResource(R.string.onboarding_notifications_body),
        actionLabel = stringResource(R.string.onboarding_notifications_action),
        onAction = { vm.openFix(SettingsTarget.NOTIFICATIONS) }
    )
}
