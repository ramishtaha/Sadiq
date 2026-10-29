package com.ramish.sadiq.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.ramish.sadiq.R
import com.ramish.sadiq.contract.OemSettingsLauncher
import com.ramish.sadiq.contract.PermissionStatusProvider
import com.ramish.sadiq.contract.SettingsRepository
import com.ramish.sadiq.contract.SettingsTarget
import com.ramish.sadiq.ui.components.SadiqCard
import com.ramish.sadiq.ui.components.SadiqTopBar
import com.ramish.sadiq.ui.components.SectionHeader
import com.ramish.sadiq.ui.components.StatusRow
import com.ramish.sadiq.ui.components.StatusTone
import com.ramish.sadiq.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** What the Health screen draws. */
data class HealthUiState(
    val rows: List<HealthRow> = emptyList(),
    val loading: Boolean = true
)

/**
 * The Health screen's state: one row per permission, rebuilt every time the permission reader
 * reports. The rows themselves are [HealthRows]' work, so the same reading reads the same way here
 * and on the Sky card.
 */
@HiltViewModel
class HealthViewModel @Inject constructor(
    private val permissions: PermissionStatusProvider,
    private val settings: SettingsRepository,
    private val launcher: OemSettingsLauncher,
) : ViewModel() {

    private val mutableState = MutableStateFlow(HealthUiState())

    /** The screen state. */
    val state: StateFlow<HealthUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(permissions.changes, settings.prefs) { snapshot, prefs ->
                HealthUiState(rows = HealthRows.from(snapshot, prefs), loading = false)
            }.collect { next -> mutableState.value = next }
        }
    }

    /** Re-reads every permission. Called when the screen comes back from an OS page. */
    fun refresh() {
        permissions.refresh()
    }

    /** Opens the OS page that fixes a row. @param target the page. */
    fun open(target: SettingsTarget) {
        launcher.open(target)
    }
}

/**
 * S6: the Health check.
 *
 * One row per permission, in the order SPEC-UX lists them, each saying what was actually found. A
 * row that Android would not answer says "Not checked" rather than a green tick: the screen exists
 * because Sadiq must never claim a grant it has not seen.
 *
 * "Fix" changes something that is off; "Set" answers a check Android would not answer. The two are
 * never the same claim, so they are never the same word.
 */
@Composable
fun HealthCheckScreen(nav: NavController, viewModel: HealthViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SadiqTopBar(
            title = stringResource(R.string.health_title),
            onBack = { nav.popBackStack() },
        )

        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.xl)
        ) {
            SectionHeader(text = stringResource(R.string.health_section_permissions))
            SadiqCard {
                state.rows.forEachIndexed { index, row ->
                    if (index > 0) HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.xs))
                    StatusRow(
                        title = healthRowTitle(row.id),
                        subtitle = healthRowCopy(row),
                        tone = when (row.tone) {
                            HealthTone.OK -> StatusTone.SUCCESS
                            HealthTone.NEEDS_ACTION -> StatusTone.WARNING
                            HealthTone.UNKNOWN -> StatusTone.UNKNOWN
                        },
                        actionLabel = if (row.tone == HealthTone.NEEDS_ACTION) {
                            stringResource(R.string.settings_fix)
                        } else {
                            stringResource(R.string.settings_set)
                        },
                        onAction = row.target?.let { target -> { viewModel.open(target) } }
                    )
                }
            }
            Spacer(Modifier.height(Spacing.md))
            Text(
                text = stringResource(R.string.health_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.xxl))
        }
    }
}

/** The row's title. @param id which check this is. */
@Composable
private fun healthRowTitle(id: HealthRowId): String = when (id) {
    HealthRowId.ACCESSIBILITY -> stringResource(R.string.health_row_accessibility)
    HealthRowId.BACKGROUND_POPUPS -> stringResource(R.string.health_row_popups)
    HealthRowId.BATTERY -> stringResource(R.string.health_row_battery)
    HealthRowId.AUTOSTART -> stringResource(R.string.health_row_autostart)
    HealthRowId.RECENTS_LOCK -> stringResource(R.string.health_row_recents)
    HealthRowId.NOTIFICATIONS -> stringResource(R.string.health_row_notifications)
    HealthRowId.USAGE_ACCESS -> stringResource(R.string.health_row_usage)
    HealthRowId.LIVE_UPDATES -> stringResource(R.string.health_row_live_updates)
}

/** The row's verdict, in the words SPEC-UX S6 wrote for that exact state. */
@Composable
private fun healthRowCopy(row: HealthRow): String = when (row.id) {
    HealthRowId.ACCESSIBILITY -> when (row.state) {
        HealthState.ON -> stringResource(R.string.health_accessibility_on)
        HealthState.NOT_RUNNING -> stringResource(R.string.health_accessibility_not_running)
        else -> stringResource(R.string.health_accessibility_off)
    }

    HealthRowId.BACKGROUND_POPUPS -> when (row.state) {
        HealthState.ON -> stringResource(R.string.health_popups_on)
        HealthState.OFF -> stringResource(R.string.health_popups_off)
        else -> stringResource(R.string.health_popups_unknown)
    }

    HealthRowId.BATTERY -> when (row.state) {
        HealthState.ON -> stringResource(R.string.health_battery_on)
        HealthState.OFF -> stringResource(R.string.health_battery_off)
        HealthState.CONFIRMED -> stringResource(R.string.health_battery_confirmed)
        else -> stringResource(R.string.health_battery_unknown)
    }

    HealthRowId.AUTOSTART -> when (row.state) {
        HealthState.ON -> stringResource(R.string.health_autostart_on)
        HealthState.OFF -> stringResource(R.string.health_autostart_off)
        HealthState.CONFIRMED -> stringResource(R.string.health_autostart_confirmed)
        else -> stringResource(R.string.health_autostart_unknown)
    }

    HealthRowId.RECENTS_LOCK -> if (row.state == HealthState.CONFIRMED) {
        stringResource(R.string.health_recents_confirmed)
    } else {
        stringResource(R.string.health_recents_unknown)
    }

    HealthRowId.NOTIFICATIONS -> if (row.state == HealthState.ON) {
        stringResource(R.string.health_notifications_on)
    } else {
        stringResource(R.string.health_notifications_off)
    }

    HealthRowId.USAGE_ACCESS -> if (row.state == HealthState.ON) {
        stringResource(R.string.health_usage_on)
    } else {
        stringResource(R.string.health_usage_off)
    }

    HealthRowId.LIVE_UPDATES -> when (row.state) {
        HealthState.ON -> stringResource(R.string.health_live_updates_on)
        HealthState.OFF -> stringResource(R.string.health_live_updates_off)
        HealthState.NOT_AVAILABLE -> stringResource(R.string.health_live_updates_unavailable)
        else -> stringResource(R.string.health_live_updates_unknown)
    }
}
