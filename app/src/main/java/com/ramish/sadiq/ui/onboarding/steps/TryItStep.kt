package com.ramish.sadiq.ui.onboarding.steps

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ramish.sadiq.R
import com.ramish.sadiq.contract.AppInfo
import com.ramish.sadiq.ui.onboarding.OnboardingViewModel
import com.ramish.sadiq.ui.onboarding.OemStepScaffold
import com.ramish.sadiq.ui.onboarding.SelfTestResult

/**
 * O13: Try it (self-test).
 *
 * Three states, and every one of them can finish:
 *
 * - **Before the test** the button arms the engine and opens the app, so the user meets the real
 *   question rather than a preview.
 * - **After it worked** the step shows the sentence the plan wrote for it — "It works. Here's your
 *   first star." — and the button finishes the wizard.
 * - **After it did not** the step says so plainly and offers both another attempt and a way out.
 *
 * The old version marked itself failed the instant the question appeared (the engine clears the arm
 * when the TEST surface shows, which is success, not failure) and then disabled its only button,
 * which is why the wizard could not be completed at all.
 */
@Composable
fun TryItStep(vm: OnboardingViewModel) {
    val state by vm.uiState.collectAsStateWithLifecycle()

    val firstSelectedApp = state.apps.firstOrNull { state.selected.contains(it.packageName) }
    val appName = firstSelectedApp?.label ?: stringResource(R.string.onboarding_try_any_app)
    val testResult = state.testResult

    val body = when (testResult) {
        null -> stringResource(R.string.onboarding_try_body, appName)
        is SelfTestResult.Works -> stringResource(R.string.onboarding_try_success)
        is SelfTestResult.Failed -> stringResource(
            R.string.onboarding_try_missed,
            labelOf(state.apps, testResult.packageName) ?: appName
        )
    }

    val actionLabel = when (testResult) {
        null -> stringResource(R.string.onboarding_try_action, appName)
        is SelfTestResult.Works -> stringResource(R.string.onboarding_try_finish_action)
        is SelfTestResult.Failed -> stringResource(R.string.onboarding_try_retry_action)
    }

    val onAction: () -> Unit = when (testResult) {
        is SelfTestResult.Works -> vm::finish
        null, is SelfTestResult.Failed -> vm::runTry
    }

    val actionEnabled = when (testResult) {
        is SelfTestResult.Works -> true
        null, is SelfTestResult.Failed -> firstSelectedApp != null && !state.testing
    }

    OemStepScaffold(
        icon = Icons.Filled.PlayArrow,
        headline = stringResource(R.string.onboarding_try_headline),
        body = body,
        actionLabel = actionLabel,
        onAction = onAction,
        actionEnabled = actionEnabled,
        // A test that did not reach the phone is a fact about this phone, not a reason to keep the
        // user in setup forever. They can finish and find the fix in Settings → Health check.
        secondaryLabel = if (testResult is SelfTestResult.Failed) {
            stringResource(R.string.onboarding_try_finish_anyway_action)
        } else {
            null
        },
        onSecondary = if (testResult is SelfTestResult.Failed) vm::finish else null
    )
}

/** The launcher label for a package in the list the wizard already loaded. */
private fun labelOf(apps: List<AppInfo>, packageName: String): String? =
    apps.firstOrNull { it.packageName == packageName }?.label
