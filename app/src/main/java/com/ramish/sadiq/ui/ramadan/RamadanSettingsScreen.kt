package com.ramish.sadiq.ui.ramadan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import com.ramish.sadiq.R
import com.ramish.sadiq.contract.SlotId
import com.ramish.sadiq.contract.UiSlot
import com.ramish.sadiq.core.model.RamadanMode
import com.ramish.sadiq.core.strict.DefaultStrictPolicy
import com.ramish.sadiq.nav.Routes
import com.ramish.sadiq.ui.components.ChoiceChips
import com.ramish.sadiq.ui.components.SadiqCard
import com.ramish.sadiq.ui.components.SadiqTopBar
import com.ramish.sadiq.ui.components.SectionHeader
import com.ramish.sadiq.ui.components.SelectableCard
import com.ramish.sadiq.ui.components.SettingRow
import com.ramish.sadiq.ui.strict.PendingChangesSection
import com.ramish.sadiq.ui.theme.Spacing
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ramadan mode in the Settings screen's section area.
 *
 * One row, because Settings is a list of destinations and not a second copy of the Ramadan screen.
 * The summary names the current answer — Off, Ask me on 29 Sha'ban, or On — so the user can see the
 * state without opening it.
 */
@Singleton
class RamadanSettingsSlot @Inject constructor() : UiSlot {

    override val slot: SlotId = SlotId.SETTINGS_SECTION

    /** After the protected-times rows, before privacy. */
    override val order: Int = 60

    @Composable
    override fun Content(nav: NavHostController) {
        val viewModel: RamadanSettingsViewModel = hiltViewModel()
        val state by viewModel.uiState.collectAsStateWithLifecycle()

        SadiqCard {
            SettingRow(
                title = stringResource(R.string.ramadan_screen_title),
                subtitle = modeTitle(state.mode),
                showChevron = true,
                onClick = { nav.navigate(Routes.RAMADAN) }
            )
        }
    }
}

/**
 * The Ramadan screen.
 *
 * Three mode cards, the Hijri correction, and — when the mode is on — the month's thirty-minute wait
 * with a way into "Think it over". The pending queue is drawn at the bottom so a deferred answer is
 * visible instead of looking like a tap that did nothing.
 *
 * @param nav the host's controller, for the back arrow and the strict screen.
 * @param viewModel the screen's state and intents.
 */
@Composable
fun RamadanSettingsScreen(nav: NavController, viewModel: RamadanSettingsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        SadiqTopBar(
            title = stringResource(R.string.ramadan_screen_title),
            onBack = { nav.popBackStack() }
        )

        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.xl)
        ) {
            SectionHeader(text = stringResource(R.string.ramadan_section_header))
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                RamadanMode.entries.forEach { mode ->
                    SelectableCard(
                        title = modeTitle(mode),
                        subtitle = modeBody(mode),
                        selected = mode == state.mode,
                        onClick = { viewModel.setMode(mode) }
                    )
                }
            }

            SectionHeader(text = stringResource(R.string.ramadan_hijri_section_header))
            SadiqCard {
                Text(
                    text = stringResource(R.string.ramadan_offset_label),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.ramadan_hijri_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(Spacing.md))
                ChoiceChips(
                    options = RamadanSettingsViewModel.OFFSET_RANGE.toList(),
                    selected = state.hijriOffsetDays,
                    onSelect = viewModel::setHijriOffsetDays,
                    label = ::offsetLabel
                )
            }

            Spacer(Modifier.height(Spacing.md))
            SadiqCard {
                SettingRow(
                    title = stringResource(R.string.ramadan_strict_row_title),
                    subtitle = if (state.strictEnabled) {
                        stringResource(
                            R.string.ramadan_strict_row_subtitle_on,
                            DefaultStrictPolicy.RAMADAN_DELAY_MIN
                        )
                    } else {
                        stringResource(R.string.ramadan_strict_row_subtitle_off)
                    },
                    showChevron = true,
                    onClick = { nav.navigate(Routes.STRICT) }
                )
            }

            if (state.ramadanActive && state.strictEnabled) {
                Text(
                    text = stringResource(R.string.ramadan_delay_note, DefaultStrictPolicy.RAMADAN_DELAY_MIN),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.md)
                )
            }

            PendingChangesSection(
                pending = state.pending,
                zoneId = state.zoneId,
                onCancel = viewModel::cancel
            )
            Spacer(Modifier.height(Spacing.xxl))
        }
    }
}

/** The card title for one mode; the same words the Settings summary uses. */
@Composable
fun modeTitle(mode: RamadanMode): String = when (mode) {
    RamadanMode.OFF -> stringResource(R.string.ramadan_mode_off_title)
    RamadanMode.AUTO_SUGGEST -> stringResource(R.string.ramadan_mode_ask_title)
    RamadanMode.ON -> stringResource(R.string.ramadan_mode_on_title)
}

/** The sentence under each mode card. */
@Composable
private fun modeBody(mode: RamadanMode): String = when (mode) {
    RamadanMode.OFF -> stringResource(R.string.ramadan_mode_off_body)
    RamadanMode.AUTO_SUGGEST -> stringResource(R.string.ramadan_mode_ask_body)
    RamadanMode.ON -> stringResource(R.string.ramadan_mode_on_body)
}

/** "−2 … +2", written with an explicit sign so the direction is never ambiguous. */
private fun offsetLabel(days: Int): String = when {
    days > 0 -> "+$days"
    else -> "$days"
}
