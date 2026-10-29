package com.ramish.sadiq.ui.strict

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.ramish.sadiq.R
import com.ramish.sadiq.ui.components.SadiqTopBar
import com.ramish.sadiq.ui.theme.Spacing

/**
 * "Think it over" as its own screen.
 *
 * The Guard tab already carries the section inline; this screen exists for the paths that cannot
 * draw a slot — a deep link, and the Ramadan screen's row about the month's longer wait. It is the
 * same two composables the slot uses, so the two can never disagree about the switch or the queue.
 *
 * @param nav the host's controller, for the back arrow.
 * @param viewModel the section's state and intents.
 */
@Composable
fun StrictSettingsScreen(nav: NavController, viewModel: StrictSettingsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        SadiqTopBar(
            title = stringResource(R.string.strict_screen_title),
            onBack = { nav.popBackStack() }
        )

        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.xl)
        ) {
            StrictSettingsSection(
                state = state,
                onToggle = viewModel::setEnabled,
                onDelayMin = viewModel::setDelayMin
            )
            PendingChangesSection(
                pending = state.pending,
                zoneId = state.zoneId,
                onCancel = viewModel::cancel
            )
            Spacer(Modifier.height(Spacing.xxl))
        }
    }
}
