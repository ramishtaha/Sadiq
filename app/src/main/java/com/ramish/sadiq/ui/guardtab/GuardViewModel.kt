package com.ramish.sadiq.ui.guardtab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ramish.sadiq.contract.GuardController
import com.ramish.sadiq.contract.SettingsRepository
import com.ramish.sadiq.core.model.GuardConfig
import com.ramish.sadiq.core.model.GuardedApp
import com.ramish.sadiq.core.model.Mode
import com.ramish.sadiq.core.model.MorningConfig
import com.ramish.sadiq.core.model.PrayerConfig
import com.ramish.sadiq.core.model.SleepGateConfig
import com.ramish.sadiq.core.schedule.ScheduleEvaluator
import com.ramish.sadiq.core.strict.SettingsChange
import com.ramish.sadiq.core.time.Clock
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GuardUiState(
    val mode: Mode = Mode.BALANCED,
    val apps: List<GuardedApp> = emptyList(),
    val sleepGate: SleepGateConfig = SleepGateConfig(),
    val morning: MorningConfig = MorningConfig(),
    val prayerSummary: String = "",
    val todayTimesLine: String = "",
    val loading: Boolean = true,
)

@HiltViewModel
class GuardViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val guardController: GuardController,
    private val clock: Clock,
    private val scheduleEvaluator: ScheduleEvaluator,
) : ViewModel() {

    val uiState: StateFlow<GuardUiState> = combine(
        settingsRepository.config,
        // The engine state carries no copy of the config; the repository is the only source of it.
        // Combining with the engine state keeps the line live when a grant or a window changes.
        guardController.state,
    ) { latestConfig, _ ->

        val now = clock.now()
        val guardContext = scheduleEvaluator.contextAt(now, latestConfig, now.wallMs)
        val today = guardContext.today

        GuardUiState(
            mode = latestConfig.mode,
            apps = latestConfig.apps,
            sleepGate = latestConfig.sleepGate,
            morning = latestConfig.morning,
            prayerSummary = "${latestConfig.prayer.method.name} · ${latestConfig.prayer.madhab.name} · ${latestConfig.prayer.place}",
            todayTimesLine = "Fajr ${formatTime(today.fajr, now.zoneId)}, Dhuhr ${formatTime(today.dhuhr, now.zoneId)}, Asr ${formatTime(today.asr, now.zoneId)}, Maghrib ${formatTime(today.maghrib, now.zoneId)}, Isha ${formatTime(today.isha, now.zoneId)}",
            loading = false,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = GuardUiState()
    )

    fun setMode(mode: Mode) {
        viewModelScope.launch { settingsRepository.apply(SettingsChange.SetMode(mode)) }
    }

    fun toggleSleepGate(enabled: Boolean) {
        viewModelScope.launch {
            val current = uiState.value.sleepGate
            settingsRepository.apply(SettingsChange.SetSleepGate(current.copy(enabled = enabled)))
        }
    }

    fun toggleMorning(enabled: Boolean) {
        viewModelScope.launch {
            val current = uiState.value.morning
            settingsRepository.apply(SettingsChange.SetMorning(current.copy(enabled = enabled)))
        }
    }

    private fun formatTime(wallMs: Long, zoneId: String): String {
        // CONTRACT-GAP: Need a proper time formatter that respects user's 12/24hr preference
        // For now, using a simple HH:MM format.
        val instant = java.time.Instant.ofEpochMilli(wallMs)
        val zonedDateTime = java.time.ZonedDateTime.ofInstant(instant, java.time.ZoneId.of(zoneId))
        return String.format("%02d:%02d", zonedDateTime.hour, zonedDateTime.minute)
    }
}
