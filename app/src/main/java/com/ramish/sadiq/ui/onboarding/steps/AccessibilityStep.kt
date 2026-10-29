package com.ramish.sadiq.ui.onboarding.steps

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ramish.sadiq.R
import com.ramish.sadiq.contract.SettingsTarget
import com.ramish.sadiq.ui.onboarding.OnboardingViewModel
import com.ramish.sadiq.ui.onboarding.OemStepScaffold

/** O5: Accessibility permission. */
@Composable
fun AccessibilityStep(vm: OnboardingViewModel) {
    val state by vm.uiState.collectAsStateWithLifecycle()

    OemStepScaffold(
        icon = Icons.Filled.Accessibility,
        headline = stringResource(R.string.onboarding_accessibility_headline),
        body = stringResource(R.string.onboarding_accessibility_body),
        actionLabel = stringResource(R.string.onboarding_accessibility_action),
        onAction = { vm.openFix(SettingsTarget.ACCESSIBILITY) },
        whyLabel = stringResource(R.string.onboarding_accessibility_why_action),
        whyText = stringResource(R.string.onboarding_accessibility_why_text)
    )
}
