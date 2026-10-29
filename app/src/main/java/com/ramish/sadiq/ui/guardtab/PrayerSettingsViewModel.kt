package com.ramish.sadiq.ui.guardtab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ramish.sadiq.contract.SettingsRepository
import com.ramish.sadiq.core.model.CalcMethod
import com.ramish.sadiq.core.model.Madhab
import com.ramish.sadiq.core.model.PrayerConfig
import com.ramish.sadiq.core.model.PrayerName
import com.ramish.sadiq.core.schedule.ScheduleEvaluator
import com.ramish.sadiq.core.strict.SettingsChange
import com.ramish.sadiq.core.time.Clock
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
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

data class PrayerUiState(
    val place: String = "",
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val method: CalcMethod = CalcMethod.KARACHI,
    val madhab: Madhab = Madhab.HANAFI,
    val windowMin: Int = 20,
    val jumuahEnabled: Boolean = false,
    val todayPrayerTimes: List<PrayerRow> = emptyList(),
    val locationError: Boolean = false,
    val loading: Boolean = true,
)

data class PrayerRow(
    val name: PrayerName,
    val time: String,
    val isCurrent: Boolean,
)

@HiltViewModel
class PrayerSettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val scheduleEvaluator: ScheduleEvaluator,
    private val clock: Clock,
    private val oneShotLocation: OneShotLocation,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PrayerUiState())
    val uiState: StateFlow<PrayerUiState> = _uiState

    init {
        viewModelScope.launch {
            settingsRepository.config
                .map { config ->
                    val now = clock.now()
                    // The evaluator already computes today's prayer times for the config in force;
                    // reusing it keeps one implementation of the Adhan mapping.
                    val todayPrayerDay = scheduleEvaluator.contextAt(now, config, now.wallMs).today
                    val allPrayerTimes = listOf(
                        PrayerRow(PrayerName.FAJR, formatTime(todayPrayerDay.fajr, now.zoneId), false),
                        PrayerRow(PrayerName.DHUHR, formatTime(todayPrayerDay.dhuhr, now.zoneId), false),
                        PrayerRow(PrayerName.ASR, formatTime(todayPrayerDay.asr, now.zoneId), false),
                        PrayerRow(PrayerName.MAGHRIB, formatTime(todayPrayerDay.maghrib, now.zoneId), false),
                        PrayerRow(PrayerName.ISHA, formatTime(todayPrayerDay.isha, now.zoneId), false),
                    )
                    val activeWindow = scheduleEvaluator.contextAt(now, config, now.wallMs).window
                    val prayerTimesWithHighlight = allPrayerTimes.map { prayerRow ->
                        prayerRow.copy(isCurrent = prayerRow.name == activeWindow.prayer && activeWindow.kind == com.ramish.sadiq.core.model.WindowKind.PRAYER)
                    }
                    PrayerUiState(
                        place = config.prayer.place,
                        lat = config.prayer.lat,
                        lon = config.prayer.lon,
                        method = config.prayer.method,
                        madhab = config.prayer.madhab,
                        windowMin = config.prayer.windowMin,
                        jumuahEnabled = config.prayer.jumuahWindowMin > config.prayer.windowMin,
                        todayPrayerTimes = prayerTimesWithHighlight,
                        locationError = _uiState.value.locationError,
                        loading = false,
                    )
                }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue = PrayerUiState()
                ).collect { _uiState.value = it }
        }
    }

    fun setMethod(method: CalcMethod) {
        viewModelScope.launch {
            val current = uiState.value
            settingsRepository.apply(SettingsChange.SetPrayer(current.toPrayerConfig().copy(method = method)))
        }
    }

    fun setMadhab(madhab: Madhab) {
        viewModelScope.launch {
            val current = uiState.value
            settingsRepository.apply(SettingsChange.SetPrayer(current.toPrayerConfig().copy(madhab = madhab)))
        }
    }

    fun setWindowMin(minutes: Int) {
        viewModelScope.launch {
            val current = uiState.value
            settingsRepository.apply(SettingsChange.SetPrayer(current.toPrayerConfig().copy(windowMin = minutes)))
        }
    }

    fun setJumuahEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val current = uiState.value
            val jumuahWindowMin = if (enabled) 60 else current.windowMin
            settingsRepository.apply(SettingsChange.SetPrayer(current.toPrayerConfig().copy(jumuahWindowMin = jumuahWindowMin)))
        }
    }

    fun useCurrentLocation() {
        viewModelScope.launch {
            val result = oneShotLocation.requestLocation()
            if (result != null) {
                val current = uiState.value.toPrayerConfig()
                settingsRepository.apply(SettingsChange.SetPrayer(
                    current.copy(
                        lat = result.latitude,
                        lon = result.longitude,
                        place = result.placeName
                    )
                ))
            } else {
                // Update UI state to show location error
                val current = uiState.value
                _uiState.value = current.copy(locationError = true)
            }
        }
    }

    private fun PrayerUiState.toPrayerConfig(): PrayerConfig {
        return PrayerConfig(
            lat = this.lat,
            lon = this.lon,
            place = this.place,
            method = this.method,
            madhab = this.madhab,
            windowMin = this.windowMin,
            jumuahWindowMin = if (this.jumuahEnabled) 60 else this.windowMin
        )
    }

    private fun formatTime(wallMs: Long, zoneId: String): String {
        // CONTRACT-GAP: Need a proper time formatter that respects user's 12/24hr preference
        // For now, using a simple HH:MM format.
        val instant = Instant.ofEpochMilli(wallMs)
        val zonedDateTime = ZonedDateTime.ofInstant(instant, ZoneId.of(zoneId))
        return String.format("%02d:%02d", zonedDateTime.hour, zonedDateTime.minute)
    }
}
