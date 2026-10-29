package com.ramish.sadiq.ui.strict

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ramish.sadiq.contract.PendingChange
import com.ramish.sadiq.contract.PendingChangesRepository
import com.ramish.sadiq.contract.SettingsRepository
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
 * The "Think it over" section's state.
 *
 * Everything shown here is read from the two sources of truth rather than held locally: the delay
 * switch and its minutes come from [SettingsRepository.config], and the queue comes from
 * [PendingChangesRepository]. That is what makes turning the switch off *look* delayed — the switch
 * stays on until the deferred edit lands, and the row explaining why is in [pending].
 *
 * @property enabled whether the delay is on.
 * @property delayMin how many minutes a loosening change waits.
 * @property pending edits that have been asked for and are waiting.
 * @property ramadanActive whether Ramadan mode is in force right now, which lengthens the wait.
 * @property zoneId the zone the effect times are shown in.
 */
data class StrictUiState(
    val enabled: Boolean = false,
    val delayMin: Int = 10,
    val pending: List<PendingChange> = emptyList(),
    val ramadanActive: Boolean = false,
    val zoneId: String = "Asia/Kolkata"
)

/**
 * The intents behind the strict section: one switch, one delay, one cancel.
 *
 * Every write goes through [SettingsRepository.apply], so the gate — not this class — decides
 * whether the edit lands now or waits. Turning the feature on is a tightening and lands at once;
 * turning it off is a loosening and waits, which is exactly the behaviour the section promises.
 */
@HiltViewModel
class StrictSettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val pendingChanges: PendingChangesRepository,
    private val clock: Clock
) : ViewModel() {

    val uiState: StateFlow<StrictUiState> = combine(
        settingsRepository.config,
        pendingChanges.observe()
    ) { config, pending ->
        val now = clock.now()
        StrictUiState(
            enabled = config.strict.enabled,
            delayMin = config.strict.delayMin,
            pending = pending,
            ramadanActive = RamadanCalendar.isRamadan(now.wallMs, now.zoneId, config.ramadan),
            zoneId = now.zoneId
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = StrictUiState()
    )

    /** Turn the delay on or off. Off is a loosening, so it lands after the wait it was guarding. */
    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val current = settingsRepository.config.value.strict
            if (current.enabled == enabled) return@launch
            settingsRepository.apply(SettingsChange.SetStrict(current.copy(enabled = enabled)))
        }
    }

    /**
     * Choose how long a loosening waits.
     *
     * Only the four offered values are accepted, so a stray call cannot write a delay the UI cannot
     * display. A shorter wait is a loosening and is deferred like any other.
     */
    fun setDelayMin(delayMin: Int) {
        if (delayMin !in DELAY_CHOICES) return
        viewModelScope.launch {
            val current = settingsRepository.config.value.strict
            if (current.delayMin == delayMin) return@launch
            settingsRepository.apply(SettingsChange.SetStrict(current.copy(delayMin = delayMin)))
        }
    }

    /** Drop a waiting change. @param id the pending row. */
    fun cancel(id: Long) {
        viewModelScope.launch { pendingChanges.remove(id) }
    }

    companion object {
        /** The wait lengths the section offers, in minutes. Ten is the plan's default. */
        val DELAY_CHOICES: List<Int> = listOf(5, 10, 15, 30)

        /** Keep the collectors alive across a configuration change, like every other tab. */
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
