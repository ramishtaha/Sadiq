package com.ramish.sadiq.ui.onboarding.steps

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
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

/** O10: HyperOS Autostart permission. */
@Composable
fun AutostartStep(vm: OnboardingViewModel) {
    val state by vm.uiState.collectAsStateWithLifecycle()

    OemStepScaffold(
        icon = Icons.Filled.PowerSettingsNew,
        headline = stringResource(R.string.onboarding_autostart_headline),
        body = stringResource(R.string.onboarding_autostart_body),
        actionLabel = stringResource(R.string.onboarding_autostart_action),
        onAction = { vm.openFix(SettingsTarget.MIUI_AUTOSTART) },
        // HyperOS reports nothing here, so the user's word is the answer, and the word moves the
        // wizard on: a confirm that only wrote a preference used to leave the step on screen.
        secondaryLabel = stringResource(R.string.onboarding_autostart_confirm_action),
        onSecondary = { vm.confirmStep(OnboardingPlan.StepId.AUTOSTART) },
        content = {
            OemPathNote(
                step = OnboardingPlan.StepId.AUTOSTART,
                hyperOsMajor = state.perms.hyperOsMajor,
            )
        }
    )
}
