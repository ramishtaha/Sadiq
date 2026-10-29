package com.ramish.sadiq.ui.focus

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.ramish.sadiq.R
import com.ramish.sadiq.core.sound.SoundKind
import com.ramish.sadiq.ui.components.ChoiceChips
import com.ramish.sadiq.ui.components.ConfirmSheet
import com.ramish.sadiq.ui.components.PrimaryPillButton
import com.ramish.sadiq.ui.components.SadiqCard
import com.ramish.sadiq.ui.components.SadiqTopBar
import com.ramish.sadiq.ui.components.SectionHeader
import com.ramish.sadiq.ui.components.TonalPillButton
import com.ramish.sadiq.ui.theme.Spacing

/**
 * The Focus tab. One screen, three states: choose a length, sit in the session, see what it lit.
 *
 * It never scolds. Ending early is a legitimate choice and simply earns what was earned; there is no
 * streak to break and nothing to make up.
 */
@Composable
fun FocusScreen(
    nav: NavHostController,
    viewModel: FocusViewModel = hiltViewModel()
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        SadiqTopBar(title = stringResource(R.string.focus_title))
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg)
        ) {
            when (ui.phase) {
                FocusPhase.SETUP -> SetupContent(ui = ui, onMin = viewModel::selectMin, onSound = viewModel::selectSound, onStart = viewModel::startFocus, onPreview = viewModel::previewSound)
                FocusPhase.RUNNING -> RunningContent(ui = ui, onEnd = viewModel::endSession, onToggleSound = viewModel::toggleSound)
                FocusPhase.DONE -> DoneContent(ui = ui, onBack = { viewModel.backToSky(); nav.popBackStack() })
            }
        }
    }

    if (ui.confirmSheetVisible) {
        ConfirmSheet(
            title = stringResource(R.string.focus_end_confirm_title),
            body = stringResource(R.string.focus_end_confirm_body),
            confirmLabel = stringResource(R.string.focus_end_confirm),
            dismissLabel = stringResource(R.string.focus_end_confirm_keep),
            onConfirm = viewModel::confirmEndSession,
            onDismiss = viewModel::cancelEndSession
        )
    }
}

@Composable
private fun SetupContent(
    ui: FocusUiState,
    onMin: (Int) -> Unit,
    onSound: (SoundKind) -> Unit,
    onStart: () -> Unit,
    onPreview: (SoundKind) -> Unit
) {
    val context = LocalContext.current
    Text(
        text = stringResource(R.string.focus_setup_headline),
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onBackground
    )
    Text(
        text = stringResource(R.string.focus_setup_subtitle),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    SadiqCard {
        SectionHeader(text = stringResource(R.string.focus_length_header))
        ChoiceChips(
            options = listOf(5, 15, 25, 45, 60, 90),
            selected = ui.selectedMin,
            onSelect = onMin,
            label = { minutes -> context.getString(R.string.focus_minutes, minutes) }
        )
        Text(
            text = stringResource(R.string.focus_projection, ui.projectedStars),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.sm)
        )
    }

    val soundOptions = SoundKind.entries
    val soundLabels = mapOf(
        SoundKind.NONE to stringResource(R.string.focus_sound_none),
        SoundKind.RAIN to stringResource(R.string.focus_sound_rain),
        SoundKind.BROWN to stringResource(R.string.focus_sound_brown),
        SoundKind.NIGHT_PAD to stringResource(R.string.focus_sound_night_pad)
    )
    SadiqCard {
        SectionHeader(text = stringResource(R.string.focus_sound_header))
        ChoiceChips(
            options = soundOptions,
            selected = ui.selectedSound,
            onSelect = { kind ->
                onSound(kind)
                if (kind != SoundKind.NONE) onPreview(kind)
            },
            label = { soundLabels.getValue(it) }
        )
    }

    PrimaryPillButton(
        text = stringResource(R.string.focus_start),
        onClick = onStart,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun RunningContent(ui: FocusUiState, onEnd: () -> Unit, onToggleSound: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Text(
            text = formatRemaining(ui.remainingMs),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = stringResource(R.string.focus_running_subtitle, ui.earnedSoFar),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (ui.currentSound != SoundKind.NONE) {
            TonalPillButton(
                text = if (ui.soundPlaying == SoundKind.NONE) stringResource(R.string.focus_sound_play) else stringResource(R.string.focus_sound_stop),
                onClick = onToggleSound
            )
        }
        TonalPillButton(
            text = stringResource(R.string.focus_end),
            onClick = onEnd,
            modifier = Modifier.padding(top = Spacing.lg)
        )
    }
}

@Composable
private fun DoneContent(ui: FocusUiState, onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Text(
            text = stringResource(R.string.focus_done_headline, ui.selectedMin),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )
        Text(
            text = stringResource(R.string.focus_done_subtitle, ui.lastResultStars),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        PrimaryPillButton(
            text = stringResource(R.string.focus_back_to_sky),
            onClick = onBack,
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.lg)
        )
    }
}

/** mm:ss for the running timer. */
private fun formatRemaining(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L)
    val minutes = total / 60
    val seconds = total % 60
    return "%d:%02d".format(minutes, seconds)
}
