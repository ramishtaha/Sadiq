package com.ramish.sadiq.ui.onboarding.steps

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ramish.sadiq.R
import com.ramish.sadiq.ui.onboarding.OnboardingViewModel
import com.ramish.sadiq.ui.onboarding.OemStepScaffold
import com.ramish.sadiq.ui.onboarding.SelfTestResult

/** O13: Try it (self-test). */
@Composable
fun TryItStep(vm: OnboardingViewModel) {
    val state by vm.uiState.collectAsStateWithLifecycle()

    val firstSelectedApp = state.apps.firstOrNull { state.selected.contains(it.packageName) }
    val appName = firstSelectedApp?.label ?: stringResource(R.string.onboarding_try_any_app)

    val headline = stringResource(R.string.onboarding_try_headline)
    val testResult = state.testResult
    val body = if (testResult == null) {
        stringResource(R.string.onboarding_try_body, appName)
    } else {
        when (testResult) {
            is SelfTestResult.Works -> stringResource(R.string.onboarding_try_success)
            is SelfTestResult.Failed -> stringResource(R.string.onboarding_try_missed, testResult.packageName)
        }
    }

    OemStepScaffold(
        icon = Icons.Filled.PlayArrow,
        headline = headline,
        body = body,
        actionLabel = stringResource(R.string.onboarding_try_action, appName),
        onAction = { vm.runTry() },
        actionEnabled = firstSelectedApp != null && state.testResult == null
    )
}
