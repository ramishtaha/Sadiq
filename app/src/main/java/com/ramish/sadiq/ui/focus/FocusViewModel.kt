package com.ramish.sadiq.ui.focus

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ramish.sadiq.contract.GuardController
import com.ramish.sadiq.contract.SettingsRepository
import com.ramish.sadiq.contract.SoundController
import com.ramish.sadiq.core.economy.StarEconomy
import com.ramish.sadiq.core.engine.Effect
import com.ramish.sadiq.core.engine.GuardInput
import com.ramish.sadiq.core.engine.Choice
import com.ramish.sadiq.core.sound.SoundKind
import com.ramish.sadiq.core.time.Clock
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val MINUTE_MS: Long = 60_000L

/** How often the running timer re-reads the clock; one second is the resolution the screen shows. */
private const val TICK_MS: Long = 1_000L

enum class FocusPhase { SETUP, RUNNING, DONE }

data class FocusUiState(
    val phase: FocusPhase = FocusPhase.SETUP,
    val selectedMin: Int = 25, // The chip in SETUP; the session's length in RUNNING and DONE
    val selectedSound: SoundKind = SoundKind.NONE, // For SETUP
    val currentSound: SoundKind = SoundKind.NONE, // For RUNNING, from EngineState
    val projectedStars: Int = 0,
    val plannedMs: Long = 0L,
    val remainingMs: Long = 0L,
    val earnedSoFar: Int = 0,
    val lastResultStars: Int = 0,
    val confirmSheetVisible: Boolean = false,
    val soundPlaying: SoundKind = SoundKind.NONE // From SoundController.playing
)

@HiltViewModel
class FocusViewModel @Inject constructor(
    private val guardController: GuardController,
    private val settingsRepository: SettingsRepository,
    private val starEconomy: StarEconomy,
    private val soundController: SoundController,
    private val clock: Clock
) : ViewModel() {

    private val _selectedMin = MutableStateFlow(25)
    private val _selectedSound = MutableStateFlow(SoundKind.NONE) // Sound selected in SETUP
    private val _confirmSheetVisible = MutableStateFlow(false)
    private val _lastResultStars = MutableStateFlow(0) // For the DONE phase, after FocusCompleted effect

    /**
     * The last planned length that was actually running.
     *
     * Once a session finishes the engine has no focus left to read, so the DONE screen would fall back
     * to zero minutes. Remembering the length while the session exists keeps that screen truthful.
     */
    private var lastPlannedMin: Int = 0

    /**
     * A once-a-second reading of the clock the engine accounts against.
     *
     * `FocusState.elapsedMs` only advances when an engine input arrives, so without this the running
     * screen would show the full length for the whole session. `elapsedMs` is `elapsedRealtime`
     * (`SystemClockSource`), which is what [Clock] reports, so the two agree.
     */
    private val tick: Flow<Long> = flow {
        while (true) {
            emit(clock.now().elapsedMs)
            delay(TICK_MS)
        }
    }

    /** The four controls the setup screen owns, folded into one flow so the combine stays typed. */
    private data class Controls(
        val min: Int,
        val sound: SoundKind,
        val confirmVisible: Boolean,
        val lastResultStars: Int
    )

    private val controls = combine(
        _selectedMin,
        _selectedSound,
        _confirmSheetVisible,
        _lastResultStars
    ) { min, sound, confirmVisible, lastResultStars ->
        Controls(min, sound, confirmVisible, lastResultStars)
    }

    val uiState: StateFlow<FocusUiState> = combine(
        guardController.state,
        settingsRepository.prefs,
        controls,
        soundController.playing,
        tick
    ) { engineState, uiPrefs, control, soundPlaying, nowElapsed ->

        val focusState = engineState.focus
        val phase = when {
            focusState != null -> FocusPhase.RUNNING
            control.lastResultStars > 0 -> FocusPhase.DONE
            else -> FocusPhase.SETUP
        }

        // Written from the combine's sequential transform, so the remembered length is always the one
        // from the freshest state: the DONE phase reads it after the focus has already left the engine.
        if (focusState != null) lastPlannedMin = (focusState.plannedMs / MINUTE_MS).toInt()
        val selectedMin = when (phase) {
            FocusPhase.SETUP -> control.min
            FocusPhase.RUNNING, FocusPhase.DONE -> lastPlannedMin
        }
        val selectedSound = if (phase == FocusPhase.SETUP) control.sound else SoundKind.NONE
        val currentSound = focusState?.sound ?: SoundKind.NONE

        val projectedStars = if (phase == FocusPhase.SETUP) {
            starEconomy.projectFocusStars(control.min, engineState.economy.litFocus)
        } else {
            0
        }

        val plannedMs = focusState?.plannedMs ?: 0L
        // The engine's elapsed reading is a snapshot from its last input; the tick extends it to now on
        // the same clock, so the timer moves between inputs instead of sitting at the full length.
        val remainingMs = focusState?.let { focus ->
            val live = focus.elapsedMs + (nowElapsed - focus.lastAccountedElapsed).coerceAtLeast(0L)
            (focus.plannedMs - live).coerceIn(0L, focus.plannedMs)
        } ?: 0L
        val earnedSoFar = if (focusState != null) {
            starEconomy.onFocusEnded(engineState.economy, focusState, false, 0L, engineState.economy.logicalDay).second.size
        } else {
            0
        }

        FocusUiState(
            phase = phase,
            selectedMin = selectedMin,
            selectedSound = selectedSound,
            currentSound = currentSound,
            projectedStars = projectedStars,
            plannedMs = plannedMs,
            remainingMs = remainingMs,
            earnedSoFar = earnedSoFar,
            lastResultStars = control.lastResultStars,
            confirmSheetVisible = control.confirmVisible,
            soundPlaying = soundPlaying
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000L),
        FocusUiState()
    )

    init {
        // Initialize selectedMin and selectedSound from preferences
        viewModelScope.launch {
            settingsRepository.prefs.map { it.focusDefaultMin }.collect { _selectedMin.value = it }
        }
        viewModelScope.launch {
            settingsRepository.prefs.map { it.focusDefaultSound }.collect { _selectedSound.value = it }
        }

        // Handle FocusCompleted effect
        guardController.effects
            .onEach { effect ->
                if (effect is Effect.FocusCompleted) {
                    _lastResultStars.value = effect.stars
                }
            }
            .launchIn(viewModelScope)
    }

    fun selectMin(min: Int) {
        _selectedMin.value = min
        viewModelScope.launch {
            settingsRepository.updatePrefs { it.copy(focusDefaultMin = min) }
        }
    }

    fun selectSound(kind: SoundKind) {
        _selectedSound.value = kind
        viewModelScope.launch {
            settingsRepository.updatePrefs { it.copy(focusDefaultSound = kind) }
        }
    }

    fun previewSound(kind: SoundKind) {
        soundController.preview(kind)
    }

    fun startFocus() {
        viewModelScope.launch {
            guardController.submit(GuardInput.StartFocus(_selectedMin.value, _selectedSound.value))
            _lastResultStars.value = 0 // Reset for new session
        }
    }

    fun endSession() {
        _confirmSheetVisible.value = true
    }

    fun confirmEndSession() {
        viewModelScope.launch {
            guardController.submit(GuardInput.Chose(Choice.EndFocus))
        }
        _confirmSheetVisible.value = false
    }

    fun cancelEndSession() {
        _confirmSheetVisible.value = false
    }

    fun toggleSound() {
        viewModelScope.launch {
            if (uiState.value.soundPlaying != SoundKind.NONE) {
                // Sound is on, turn it off
                soundController.stop()
            } else {
                // Sound is off, turn it on with the current focus sound from the running session
                val currentFocusSound = guardController.state.value.focus?.sound
                currentFocusSound?.let {
                    if (it != SoundKind.NONE) {
                        soundController.play(it, null)
                    }
                }
            }
        }
    }

    fun backToSky() {
        _lastResultStars.value = 0
    }
}