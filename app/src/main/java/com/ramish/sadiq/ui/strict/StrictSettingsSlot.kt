package com.ramish.sadiq.ui.strict

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.ramish.sadiq.R
import com.ramish.sadiq.contract.SlotId
import com.ramish.sadiq.contract.UiSlot
import com.ramish.sadiq.core.strict.DefaultStrictPolicy
import com.ramish.sadiq.ui.components.ChoiceChips
import com.ramish.sadiq.ui.components.SadiqCard
import com.ramish.sadiq.ui.components.SectionHeader
import com.ramish.sadiq.ui.components.SettingRow
import com.ramish.sadiq.ui.theme.SadiqTheme
import com.ramish.sadiq.ui.theme.Spacing
import com.ramish.sadiq.ui.theme.ThemeFlavor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Think it over" as a section of the Guard tab.
 *
 * The Guard screen renders every [SlotId.RULES_SECTION] contribution in `order`, so this appears
 * under Protected times without the Guard screen knowing the feature exists. The section is
 * self-contained: the switch, the wait and the sentence that explains both are here, and the queue
 * of waiting edits is [PendingChangesSlot] just below it.
 *
 * The last line is the plan's honesty requirement: Android's own settings are outside this. Sadiq
 * delays edits made inside Sadiq; it cannot delay the three taps that switch its accessibility
 * service off, and it would be a lie to imply otherwise.
 */
@Singleton
class StrictSettingsSlot @Inject constructor() : UiSlot {

    override val slot: SlotId = SlotId.RULES_SECTION

    /** After the other M2 rules sections, before pending changes. */
    override val order: Int = ORDER

    @Composable
    override fun Content(nav: NavHostController) {
        val viewModel: StrictSettingsViewModel = hiltViewModel()
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        StrictSettingsSection(
            state = state,
            onToggle = viewModel::setEnabled,
            onDelayMin = viewModel::setDelayMin
        )
    }

    companion object {
        /** Orders the rules sections: websites 10, shorts 20, strictness 30, pending 40. */
        const val ORDER: Int = 30
    }
}

/**
 * The section itself, stateless so it can be drawn by the slot, by the full screen and by a preview.
 *
 * @param state the switch, the delay and whether Ramadan is lengthening it.
 * @param onToggle the switch's intent.
 * @param onDelayMin the chip row's intent.
 */
@Composable
fun StrictSettingsSection(
    state: StrictUiState,
    onToggle: (Boolean) -> Unit,
    onDelayMin: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        SectionHeader(text = stringResource(R.string.strict_section_header))
        SadiqCard {
            SettingRow(
                title = stringResource(R.string.strict_title),
                subtitle = if (state.enabled) {
                    pluralStringResource(R.plurals.strict_wait_sentence, state.delayMin, state.delayMin)
                } else {
                    stringResource(R.string.strict_subtitle_off)
                },
                checked = state.enabled,
                onCheckedChange = onToggle
            )

            if (state.enabled) {
                HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.md))
                Text(
                    text = stringResource(R.string.strict_delay_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(Spacing.sm))

                // The stored value may not be one of the four offered (an older build, a hand-edited
                // store), so it is added to the row instead of leaving no chip selected.
                val options = (StrictSettingsViewModel.DELAY_CHOICES + state.delayMin).distinct().sorted()
                val labels = options.associateWith { minutes ->
                    stringResource(R.string.strict_delay_value, minutes)
                }
                ChoiceChips(
                    options = options,
                    selected = state.delayMin,
                    onSelect = onDelayMin,
                    label = { minutes -> labels.getValue(minutes) }
                )

                if (state.ramadanActive) {
                    Text(
                        text = stringResource(R.string.strict_ramadan_note, DefaultStrictPolicy.RAMADAN_DELAY_MIN),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spacing.md)
                    )
                }

                Text(
                    text = stringResource(R.string.strict_outside_reach_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.md)
                )
            }
        }
    }
}

@Preview(name = "Think it over · Light", widthDp = 360, showBackground = true)
@Composable
private fun StrictSettingsSectionLightPreview() {
    SadiqTheme(flavor = ThemeFlavor.LIGHT) {
        StrictSettingsSection(
            state = StrictUiState(enabled = true, delayMin = 10),
            onToggle = {},
            onDelayMin = {},
            modifier = Modifier.padding(horizontal = Spacing.xl)
        )
    }
}

@Preview(name = "Think it over · Dark", widthDp = 360, showBackground = true)
@Composable
private fun StrictSettingsSectionDarkPreview() {
    SadiqTheme(flavor = ThemeFlavor.DARK) {
        StrictSettingsSection(
            state = StrictUiState(enabled = true, delayMin = 10, ramadanActive = true),
            onToggle = {},
            onDelayMin = {},
            modifier = Modifier.padding(horizontal = Spacing.xl)
        )
    }
}

@Preview(name = "Think it over · Off", widthDp = 360, showBackground = true)
@Composable
private fun StrictSettingsSectionOffPreview() {
    SadiqTheme(flavor = ThemeFlavor.SEPIA) {
        StrictSettingsSection(
            state = StrictUiState(enabled = false),
            onToggle = {},
            onDelayMin = {},
            modifier = Modifier.padding(horizontal = Spacing.xl)
        )
    }
}
