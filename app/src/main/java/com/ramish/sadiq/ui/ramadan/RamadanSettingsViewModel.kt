package com.ramish.sadiq.ui.ramadan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ramish.sadiq.contract.PendingChange
import com.ramish.sadiq.contract.PendingChangesRepository
import com.ramish.sadiq.contract.SettingsRepository
import com.ramish.sadiq.core.model.RamadanConfig
import com.ramish.sadiq.core.model.RamadanMode
import com.ramish.sadiq.core.schedule.RamadanCalendar
import com.ramish.sadiq.core.strict.SettingsChange
import com.ramish.sadiq.core.time.Clock
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The Ramadan screen's state.
 *
 * @property mode off, on, or offered on 29 Sha'ban.
 * @property hijriOffsetDays the user's correction to the printed calendar, −2…+2.
 * @property ramadanActive whether the Ramadan schedule is in force right now.
 * @property strictEnabled whether "Think it over" is on, which is what makes the month's wait real.
 * @property strictDelayMin the ordinary wait, shown only when Ramadan is not lengthening it.
 * @property pending edits still waiting, so turning the mode off during Ramadan explains itself.
 * @property zoneId the zone the effect times are shown in.
 */
data class RamadanUiState(
    val mode: RamadanMode = RamadanMode.AUTO_SUGGEST,
    val hijriOffsetDays: Int = 0,
    val ramadanActive: Boolean = false,
    val strictEnabled: Boolean = false,
    val strictDelayMin: Int = 10,
    val pending: List<PendingChange> = emptyList(),
    val zoneId: String = "Asia/Kolkata"
)

/**
 * Ramadan mode: the three-way choice and the Hijri correction.
 *
 * Both edits go through [SettingsRepository.apply], so the strict gate has the last word. Turning
 * the mode on is a tightening and lands at once; turning it off during Ramadan is a loosening and
 * waits the month's thirty minutes, which is why the screen also draws the pending queue. The
 * correction is calendar-only, classified neutral, and takes effect immediately.
 */
@HiltViewModel
class RamadanSettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val pendingChanges: PendingChangesRepository,
    private val clock: Clock
) : ViewModel() {

    val uiState: StateFlow<RamadanUiState> = combine(
        settingsRepository.config,
        pendingChanges.observe()
    ) { config, pending ->
        val now = clock.now()
        RamadanUiState(
            mode = config.ramadan.mode,
            hijriOffsetDays = config.ramadan.hijriOffsetDays,
            ramadanActive = RamadanCalendar.isRamadan(now.wallMs, now.zoneId, config.ramadan),
            strictEnabled = config.strict.enabled,
            strictDelayMin = config.strict.delayMin,
            pending = pending,
            zoneId = now.zoneId
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = RamadanUiState()
    )

    /**
     * Choose off, offer, or on.
     *
     * @param mode the new mode; choosing the mode already in force is a no-op.
     */
    fun setMode(mode: RamadanMode) {
        viewModelScope.launch {
            val current = settingsRepository.config.value.ramadan
            if (current.mode == mode) return@launch
            settingsRepository.apply(SettingsChange.SetRamadan(current.copy(mode = mode)))
        }
    }

    /**
     * Correct the Hijri date by a day or two.
     *
     * @param days the correction, clamped to [OFFSET_RANGE] here as well as in the data layer, so a
     *   caller cannot store a value the screen cannot draw.
     */
    fun setHijriOffsetDays(days: Int) {
        val clamped = days.coerceIn(OFFSET_RANGE.first, OFFSET_RANGE.last)
        viewModelScope.launch {
            val current = settingsRepository.config.value.ramadan
            if (current.hijriOffsetDays == clamped) return@launch
            settingsRepository.apply(
                SettingsChange.SetRamadan(RamadanConfig(mode = current.mode, hijriOffsetDays = clamped))
            )
        }
    }

    /** Drop a waiting change. @param id the pending row. */
    fun cancel(id: Long) {
        viewModelScope.launch { pendingChanges.remove(id) }
    }

    companion object {
        /** The corrections the screen offers, in days. */
        val OFFSET_RANGE: IntRange = -2..2

        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
