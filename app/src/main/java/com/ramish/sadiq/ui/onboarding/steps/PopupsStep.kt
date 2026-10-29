package com.ramish.sadiq.ui.onboarding.steps

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.ramish.sadiq.R
import com.ramish.sadiq.contract.SettingsTarget
import com.ramish.sadiq.ui.onboarding.OnboardingViewModel
import com.ramish.sadiq.ui.onboarding.OemStepScaffold

/** O7: HyperOS pop-up permission. */
@Composable
fun PopupsStep(vm: OnboardingViewModel) {
    OemStepScaffold(
        icon = Icons.Filled.PictureInPictureAlt,
        headline = stringResource(R.string.onboarding_popups_headline),
        body = stringResource(R.string.onboarding_popups_body),
        actionLabel = stringResource(R.string.onboarding_popups_action),
        onAction = { vm.openFix(SettingsTarget.MIUI_OTHER_PERMISSIONS) },
        // On a HyperOS build this op can read UNKNOWN forever. The step explains the fallback; it
        // must not become the place a user is stuck.
        secondaryLabel = stringResource(R.string.onboarding_skip_action),
        onSecondary = { vm.skipCurrent() }
    )
}
