package com.ramish.sadiq.ui.guardtab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ramish.sadiq.contract.SettingsRepository
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
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject

data class ProtectedTimesUiState(
    val sleepEnabled: Boolean = true,
    val sleepStartMinuteOfDay: Int = 1350,
    val morningEnabled: Boolean = true,
    val morningEndMinuteOfDay: Int = 570,
    val sleepGatePreview: String = "",
    val loading: Boolean = true,
)

@HiltViewModel
class ProtectedTimesViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val clock: Clock,
    private val scheduleEvaluator: ScheduleEvaluator,
) : ViewModel() {

    val uiState: StateFlow<ProtectedTimesUiState> = combine(
        settingsRepository.config.map { it.sleepGate },
        settingsRepository.config.map { it.morning },
        settingsRepository.config.map { it.prayer },
        settingsRepository.config.map { it.ramadan },
        settingsRepository.config // Get the full config to pass to scheduleEvaluator
    ) { sleepGateConfig, morningConfig, prayerConfig, ramadanConfig, fullConfig ->
        val now = clock.now()
        val guardContext = scheduleEvaluator.contextAt(now, fullConfig, now.wallMs)

        val today = guardContext.today

        // Calculate sleep gate preview
        val sleepGate = sleepGateConfig.copy(startMinuteOfDay = sleepGateConfig.startMinuteOfDay)
        val sleepGateStartWall = calculateSleepGateStartWall(now.wallMs, now.zoneId, sleepGate, prayerConfig, ramadanConfig, today)
        val sleepGateEndWall = today.nextFajr
        val sleepGatePreview = if (sleepGateStartWall != null && sleepGateEndWall != null && sleepGateEndWall > sleepGateStartWall) {
            "Tonight: ${formatTime(sleepGateStartWall, now.zoneId)} → ${formatTime(sleepGateEndWall, now.zoneId)} (Fajr)."
        } else {
            ""
        }

        ProtectedTimesUiState(
            sleepEnabled = sleepGateConfig.enabled,
            sleepStartMinuteOfDay = sleepGateConfig.startMinuteOfDay,
            morningEnabled = morningConfig.enabled,
            morningEndMinuteOfDay = morningConfig.endMinuteOfDay,
            sleepGatePreview = sleepGatePreview,
            loading = false,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ProtectedTimesUiState()
    )

    fun setSleepGateEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val current = uiState.value
            settingsRepository.apply(SettingsChange.SetSleepGate(current.toSleepGateConfig().copy(enabled = enabled)))
        }
    }

    fun setSleepGateStartTime(hour: Int, minute: Int) {
        viewModelScope.launch {
            val current = uiState.value
            val newMinuteOfDay = hour * 60 + minute
            settingsRepository.apply(SettingsChange.SetSleepGate(current.toSleepGateConfig().copy(startMinuteOfDay = newMinuteOfDay)))
        }
    }

    fun setMorningEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val current = uiState.value
            settingsRepository.apply(SettingsChange.SetMorning(current.toMorningConfig().copy(enabled = enabled)))
        }
    }

    fun setMorningEndTime(hour: Int, minute: Int) {
        viewModelScope.launch {
            val current = uiState.value
            val newMinuteOfDay = hour * 60 + minute
            settingsRepository.apply(SettingsChange.SetMorning(current.toMorningConfig().copy(endMinuteOfDay = newMinuteOfDay)))
        }
    }

    private fun formatTime(wallMs: Long, zoneId: String): String {
        // CONTRACT-GAP: Need a proper time formatter that respects user's 12/24hr preference
        val instant = Instant.ofEpochMilli(wallMs)
        val zonedDateTime = ZonedDateTime.ofInstant(instant, ZoneId.of(zoneId))
        return String.format("%02d:%02d", zonedDateTime.hour, zonedDateTime.minute)
    }

    private fun calculateSleepGateStartWall(
        nowWall: Long,
        zoneId: String,
        sleepGateConfig: SleepGateConfig,
        prayerConfig: PrayerConfig,
        ramadanConfig: com.ramish.sadiq.core.model.RamadanConfig,
        todayPrayerDay: com.ramish.sadiq.core.model.PrayerDay
    ): Long? {
        // Replicating the logic from DefaultScheduleEvaluator for sleep gate start calculation
        if (!sleepGateConfig.enabled) return null

        val bedtime = atMinuteOfDay(todayPrayerDay.epochDay, sleepGateConfig.startMinuteOfDay, zoneId)
        val ordinaryIshaDelayMs = 30L * 60 * 1000 // 30 minutes
        val ramadanIshaDelayMs = 90L * 60 * 1000 // 90 minutes

        val earliestStart = maxOf(bedtime, todayPrayerDay.isha + ordinaryIshaDelayMs)
        val ramadanActive = com.ramish.sadiq.core.schedule.RamadanCalendar.isRamadan(earliestStart, zoneId, ramadanConfig)

        return if (ramadanActive) {
            maxOf(bedtime, todayPrayerDay.isha + ramadanIshaDelayMs)
        } else {
            earliestStart
        }
    }

    private fun atMinuteOfDay(epochDay: Int, minuteOfDay: Int, zoneId: String): Long {
        val minute = Math.floorMod(minuteOfDay, 1440) // 1440 minutes in a day
        return java.time.LocalDate.ofEpochDay(epochDay.toLong())
            .atTime(minute / 60, minute % 60)
            .atZone(ZoneId.of(zoneId))
            .toInstant()
            .toEpochMilli()
    }

    private fun ProtectedTimesUiState.toSleepGateConfig(): SleepGateConfig =
        SleepGateConfig(enabled = sleepEnabled, startMinuteOfDay = sleepStartMinuteOfDay)

    private fun ProtectedTimesUiState.toMorningConfig(): MorningConfig =
        MorningConfig(enabled = morningEnabled, endMinuteOfDay = morningEndMinuteOfDay)
}
