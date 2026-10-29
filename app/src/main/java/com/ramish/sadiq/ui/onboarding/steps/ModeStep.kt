package com.ramish.sadiq.ui.onboarding.steps

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Balance
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ramish.sadiq.R
import com.ramish.sadiq.core.model.Mode
import com.ramish.sadiq.ui.components.SelectableCard
import com.ramish.sadiq.ui.onboarding.OnboardingViewModel
import com.ramish.sadiq.ui.onboarding.OemStepScaffold

/** O3: Mode selection. */
@Composable
fun ModeStep(vm: OnboardingViewModel) {
    val state by vm.uiState.collectAsStateWithLifecycle()

    OemStepScaffold(
        icon = Icons.Filled.Balance,
        headline = stringResource(R.string.onboarding_mode_headline),
        body = stringResource(R.string.onboarding_mode_body),
        actionLabel = stringResource(R.string.onboarding_mode_action, modeLabel(state.mode)),
        onAction = { vm.next() }
    ) {
        SelectableCard(
            title = stringResource(R.string.onboarding_mode_gentle_title),
            subtitle = stringResource(R.string.onboarding_mode_gentle_subtitle),
            selected = state.mode == Mode.GENTLE,
            onClick = { vm.setMode(Mode.GENTLE) }
        )
        SelectableCard(
            title = stringResource(R.string.onboarding_mode_steady_title),
            subtitle = stringResource(R.string.onboarding_mode_steady_subtitle),
            selected = state.mode == Mode.BALANCED,
            onClick = { vm.setMode(Mode.BALANCED) }
        )
        SelectableCard(
            title = stringResource(R.string.onboarding_mode_firm_title),
            subtitle = stringResource(R.string.onboarding_mode_firm_subtitle),
            selected = state.mode == Mode.HARDCORE,
            onClick = { vm.setMode(Mode.HARDCORE) }
        )
    }
}

@Composable
private fun modeLabel(mode: Mode): String = when (mode) {
    Mode.GENTLE -> stringResource(R.string.onboarding_mode_gentle_title)
    Mode.BALANCED -> stringResource(R.string.onboarding_mode_steady_title)
    Mode.HARDCORE -> stringResource(R.string.onboarding_mode_firm_title)
}
