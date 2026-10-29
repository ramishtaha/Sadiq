package com.ramish.sadiq.ui.strict

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.ramish.sadiq.R
import com.ramish.sadiq.contract.PendingChange
import com.ramish.sadiq.contract.SlotId
import com.ramish.sadiq.contract.UiSlot
import com.ramish.sadiq.core.model.Mode
import com.ramish.sadiq.core.model.StrictConfig
import com.ramish.sadiq.core.strict.SettingsChange
import com.ramish.sadiq.ui.components.SadiqCard
import com.ramish.sadiq.ui.components.SectionHeader
import com.ramish.sadiq.ui.components.TextPillButton
import com.ramish.sadiq.ui.theme.SadiqTheme
import com.ramish.sadiq.ui.theme.Spacing
import com.ramish.sadiq.ui.theme.ThemeFlavor
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The queue of edits that are waiting, under the strict section.
 *
 * This is the feature's other half: a delay the user cannot see is indistinguishable from a tap that
 * did nothing. Each row says what will change and when it lands — "Takes effect at 23:52" — and
 * offers Cancel, which drops the row without applying it. The section draws nothing at all when the
 * queue is empty, so the Guard tab does not carry an empty heading all day.
 *
 * It shares [StrictSettingsViewModel] with [StrictSettingsSlot]. Both are on the same back stack
 * entry, and `hiltViewModel()` keys by class, so the second one joins the first one's state instead
 * of opening a second queue reader.
 */
@Singleton
class PendingChangesSlot @Inject constructor() : UiSlot {

    override val slot: SlotId = SlotId.RULES_SECTION

    /** Immediately after [StrictSettingsSlot]. */
    override val order: Int = StrictSettingsSlot.ORDER + 10

    @Composable
    override fun Content(nav: NavHostController) {
        val viewModel: StrictSettingsViewModel = hiltViewModel()
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        PendingChangesSection(
            pending = state.pending,
            zoneId = state.zoneId,
            onCancel = viewModel::cancel
        )
    }
}

/**
 * The queue, stateless.
 *
 * @param pending the waiting edits, earliest first.
 * @param zoneId the zone the effect times are shown in.
 * @param onCancel drops the row with the given id.
 */
@Composable
fun PendingChangesSection(
    pending: List<PendingChange>,
    zoneId: String,
    onCancel: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    if (pending.isEmpty()) return

    Column(modifier = modifier) {
        SectionHeader(text = stringResource(R.string.strict_pending_header))
        SadiqCard {
            pending.forEachIndexed { index, change ->
                if (index > 0) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.sm))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = pendingLabel(change.change),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(
                                R.string.strict_pending_takes_effect,
                                formatClock(change.applyAt, zoneId)
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextPillButton(
                        text = stringResource(R.string.strict_pending_cancel),
                        onClick = { onCancel(change.id) }
                    )
                }
            }
        }
    }
}

/** One line naming the edit, so two waiting rows are never indistinguishable. */
@Composable
private fun pendingLabel(change: SettingsChange): String = when (change) {
    is SettingsChange.SetMode -> stringResource(R.string.strict_pending_mode, modeLabel(change.mode))
    is SettingsChange.SetApps -> stringResource(R.string.strict_pending_apps, change.apps.size)
    is SettingsChange.SetSurfaceMode -> stringResource(R.string.strict_pending_surface)
    is SettingsChange.SetDomains -> stringResource(R.string.strict_pending_domains, change.domains.size)
    is SettingsChange.SetSleepGate -> stringResource(R.string.strict_pending_bedtime)
    is SettingsChange.SetMorning -> stringResource(R.string.strict_pending_morning)
    is SettingsChange.SetPrayer -> stringResource(R.string.strict_pending_prayer)
    is SettingsChange.SetRamadan -> stringResource(R.string.strict_pending_ramadan)
    is SettingsChange.SetStrict -> stringResource(R.string.strict_pending_strict)
    is SettingsChange.SetFlags -> stringResource(R.string.strict_pending_flags)
}

/** Mode names as the name addendum fixes them: Gentle, Steady, Firm. */
@Composable
private fun modeLabel(mode: Mode): String = when (mode) {
    Mode.GENTLE -> stringResource(R.string.strict_mode_gentle)
    Mode.BALANCED -> stringResource(R.string.strict_mode_steady)
    Mode.HARDCORE -> stringResource(R.string.strict_mode_firm)
}

/** "23:52" in the user's zone, with ASCII digits whatever the locale. */
private fun formatClock(wallMs: Long, zoneId: String): String {
    val time = ZonedDateTime.ofInstant(Instant.ofEpochMilli(wallMs), ZoneId.of(zoneId))
    return String.format(Locale.ROOT, "%02d:%02d", time.hour, time.minute)
}

@Preview(name = "Pending changes · Light", widthDp = 360, showBackground = true)
@Composable
private fun PendingChangesSectionLightPreview() {
    val now = 1_793_288_400_000L
    SadiqTheme(flavor = ThemeFlavor.LIGHT) {
        PendingChangesSection(
            pending = listOf(
                PendingChange(1, now, now + 10 * 60_000L, SettingsChange.SetMode(Mode.GENTLE)),
                PendingChange(2, now, now + 25 * 60_000L, SettingsChange.SetStrict(StrictConfig(enabled = false)))
            ),
            zoneId = "Asia/Kolkata",
            onCancel = {},
            modifier = Modifier.padding(horizontal = Spacing.xl)
        )
    }
}
