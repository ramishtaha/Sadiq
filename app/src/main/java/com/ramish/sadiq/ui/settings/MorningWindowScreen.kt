package com.ramish.sadiq.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.ramish.sadiq.R
import com.ramish.sadiq.contract.SettingsRepository
import com.ramish.sadiq.core.model.MorningConfig
import com.ramish.sadiq.core.strict.SettingsChange
import com.ramish.sadiq.ui.components.SadiqCard
import com.ramish.sadiq.ui.components.SadiqTopBar
import com.ramish.sadiq.ui.components.SectionHeader
import com.ramish.sadiq.ui.components.SettingRow
import com.ramish.sadiq.ui.components.TimePickerDialog
import com.ramish.sadiq.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the morning-window screen draws. */
data class MorningUiState(
    val enabled: Boolean = true,
    val endMinuteOfDay: Int = MorningConfig().endMinuteOfDay
)

/**
 * The morning window's state and its one edit path.
 *
 * Both intents go through [SettingsRepository.apply] with a [SettingsChange.SetMorning], the same
 * change the Guard tab makes, so the two screens can never disagree about the window.
 */
@HiltViewModel
class MorningViewModel @Inject constructor(
    private val settings: SettingsRepository,
) : ViewModel() {

    /** The window in force. */
    val state: StateFlow<MorningUiState> = settings.config
        .map { config -> MorningUiState(enabled = config.morning.enabled, endMinuteOfDay = config.morning.endMinuteOfDay) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = MorningUiState()
        )

    /** @param enabled whether the morning window is on. */
    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settings.apply(SettingsChange.SetMorning(settings.config.value.morning.copy(enabled = enabled)))
        }
    }

    /** @param minuteOfDay the latest minute of the day the window may end. */
    fun setEndMinute(minuteOfDay: Int) {
        viewModelScope.launch {
            settings.apply(
                SettingsChange.SetMorning(
                    settings.config.value.morning.copy(endMinuteOfDay = minuteOfDay)
                )
            )
        }
    }
}

/**
 * The morning window on its own: Fajr until [MorningUiState.endMinuteOfDay].
 *
 * It exists because Guard → "Your morning" opens it and nothing was registered at `Routes.MORNING`,
 * which made that row a crash. The edit is the same one the Protected times screen offers; this
 * screen is the short way in from the Guard tab.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MorningWindowScreen(nav: NavController, viewModel: MorningViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    var showPicker by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        SadiqTopBar(
            title = stringResource(R.string.settings_morning),
            onBack = { nav.popBackStack() },
        )

        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.xl)
        ) {
            SectionHeader(text = stringResource(R.string.morning_section_window))
            SadiqCard {
                SettingRow(
                    title = stringResource(R.string.morning_enabled_title),
                    subtitle = if (state.enabled) {
                        stringResource(R.string.protected_times_on)
                    } else {
                        stringResource(R.string.protected_times_off)
                    },
                    checked = state.enabled,
                    onCheckedChange = viewModel::setEnabled
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.md))
                SettingRow(
                    title = stringResource(R.string.settings_morning),
                    subtitle = stringResource(
                        R.string.settings_morning_value,
                        formatMinuteOfDay(state.endMinuteOfDay)
                    ),
                    showChevron = true,
                    onClick = { showPicker = true }
                )
            }
            Spacer(Modifier.height(Spacing.md))
            Text(
                text = stringResource(R.string.morning_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.xxl))
        }
    }

    if (showPicker) {
        // Created when the dialog opens, not when the screen composes, so it opens on the value the
        // row is showing right now rather than on the default the state had before the config loaded.
        val pickerState = rememberTimePickerState(
            initialHour = state.endMinuteOfDay / 60,
            initialMinute = state.endMinuteOfDay % 60,
            is24Hour = true
        )
        TimePickerDialog(
            title = stringResource(R.string.protected_times_select_morning_window_end_time),
            onCancel = { showPicker = false },
            onConfirm = {
                viewModel.setEndMinute(pickerState.hour * 60 + pickerState.minute)
                showPicker = false
            },
            state = pickerState
        )
    }
}
