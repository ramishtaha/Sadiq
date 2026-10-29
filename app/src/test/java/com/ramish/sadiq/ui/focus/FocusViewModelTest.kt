package com.ramish.sadiq.ui.focus

import com.ramish.sadiq.core.economy.DefaultStarEconomy
import com.ramish.sadiq.core.engine.Effect
import com.ramish.sadiq.core.engine.FocusState
import com.ramish.sadiq.core.sound.SoundKind
import com.ramish.sadiq.testing.FakeClock
import com.ramish.sadiq.testing.FakeGuardController
import com.ramish.sadiq.testing.FakeSettingsRepository
import com.ramish.sadiq.testing.FakeSoundController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals

private const val MINUTE: Long = 60_000L

/** The ViewModel's own tick; the test advances virtual time by one of these to see it fire. */
private const val TICK: Long = 1_000L

/** `SharingStarted.WhileSubscribed` timeout, so the test can leave the tick cancelled and idle. */
private const val STOP_TIMEOUT: Long = 5_000L

/**
 * Audit-3 B-4, the Focus screen half: the timer is the clock's, not the engine's.
 *
 * `FocusState.elapsedMs` is only advanced when an engine input arrives, so a screen that reads it
 * alone sits at the full length for a whole session. These tests publish one running state and then
 * move only the clock, which is exactly the real situation — the engine is idle while Sadiq is in
 * front. They also pin the DONE screen's length, which used to read zero minutes because the focus it
 * was derived from no longer exists by then.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FocusViewModelTest {

    /**
     * Runs a test on a Main dispatcher that shares its scheduler with the test body, so `advanceTimeBy`
     * moves the ViewModel's tick as well as the test's own delays.
     */
    private fun focusTest(body: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            body()
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun createViewModel(clock: FakeClock, guard: FakeGuardController): FocusViewModel = FocusViewModel(
        guardController = guard,
        settingsRepository = FakeSettingsRepository(),
        starEconomy = DefaultStarEconomy(),
        soundController = FakeSoundController(),
        clock = clock,
    )

    private fun runningFocus(plannedMin: Int, accountedAt: Long): FocusState = FocusState(
        id = "f1",
        plannedMs = plannedMin * MINUTE,
        elapsedMs = 0L,
        lastAccountedElapsed = accountedAt,
        startedWall = 0L,
        sound = SoundKind.NONE,
    )

    /** Collects [FocusViewModel.uiState] so the shared flow actually runs. */
    private fun TestScope.listen(viewModel: FocusViewModel): Job =
        launch { viewModel.uiState.collect { } }

    /**
     * Lets the shared flow shut down: the subscription is dropped, then the stop timeout expires and
     * `WhileSubscribed` cancels the tick, which leaves the test scheduler with nothing pending.
     */
    private fun TestScope.stopListening(job: Job) {
        job.cancel()
        advanceTimeBy(STOP_TIMEOUT + 100L)
        runCurrent()
    }

    @Test
    fun theRunningTimerCountsFromTheClockWithoutAnEngineInput() = focusTest {
        val clock = FakeClock()
        val guard = FakeGuardController()
        val viewModel = createViewModel(clock, guard)
        val subscription = listen(viewModel)
        runCurrent()

        guard.emitState(FakeGuardController.IDLE.copy(focus = runningFocus(25, accountedAt = clock.now().elapsedMs)))
        runCurrent()
        assertEquals(FocusPhase.RUNNING, viewModel.uiState.value.phase)
        assertEquals(25 * MINUTE, viewModel.uiState.value.remainingMs)

        // No further engine state: only the clock moves, as it does while the user watches the screen.
        clock.advance(elapsedMinutes = 10)
        advanceTimeBy(TICK + 100L)
        assertEquals(15 * MINUTE, viewModel.uiState.value.remainingMs)

        clock.advance(elapsedMinutes = 20)
        advanceTimeBy(TICK + 100L)
        assertEquals(0L, viewModel.uiState.value.remainingMs, "past the end the timer must read zero, not negative")

        stopListening(subscription)
    }

    @Test
    fun theDoneScreenKeepsTheLengthThatJustRan() = focusTest {
        val clock = FakeClock()
        val guard = FakeGuardController()
        val viewModel = createViewModel(clock, guard)
        val subscription = listen(viewModel)
        runCurrent()

        guard.emitState(FakeGuardController.IDLE.copy(focus = runningFocus(25, accountedAt = clock.now().elapsedMs)))
        runCurrent()

        // The engine clears the focus and announces the stars, which is what moves the screen to DONE.
        guard.emitState(FakeGuardController.IDLE)
        guard.emitEffect(Effect.FocusCompleted(2))
        runCurrent()

        assertEquals(FocusPhase.DONE, viewModel.uiState.value.phase)
        assertEquals(25, viewModel.uiState.value.selectedMin, "the finished screen must not read 0 minutes")
        assertEquals(2, viewModel.uiState.value.lastResultStars)

        stopListening(subscription)
    }

    @Test
    fun theDoneScreenReportsTheSessionLengthNotTheSetupDefault() = focusTest {
        val clock = FakeClock()
        val guard = FakeGuardController()
        val viewModel = createViewModel(clock, guard)
        val subscription = listen(viewModel)
        runCurrent()

        // The setup default is 25 (the fixture preference); this session is a 45-minute one.
        guard.emitState(FakeGuardController.IDLE.copy(focus = runningFocus(45, accountedAt = clock.now().elapsedMs)))
        runCurrent()
        guard.emitState(FakeGuardController.IDLE)
        guard.emitEffect(Effect.FocusCompleted(3))
        runCurrent()

        assertEquals(45, viewModel.uiState.value.selectedMin)

        stopListening(subscription)
    }
}
