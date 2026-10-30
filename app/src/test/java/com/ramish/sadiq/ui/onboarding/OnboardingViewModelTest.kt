package com.ramish.sadiq.ui.onboarding

import android.content.ContextWrapper
import com.ramish.sadiq.contract.AppInfo
import com.ramish.sadiq.contract.EngineSnapshotStore
import com.ramish.sadiq.contract.OemSettingsLauncher
import com.ramish.sadiq.contract.SettingsTarget
import com.ramish.sadiq.contract.UsageHistorySource
import com.ramish.sadiq.core.economy.DefaultStarEconomy
import com.ramish.sadiq.core.engine.EngineState
import com.ramish.sadiq.core.engine.GuardEngine
import com.ramish.sadiq.core.engine.UsageSpan
import com.ramish.sadiq.core.insight.DefaultNextActionSuggester
import com.ramish.sadiq.core.insight.DefaultReflectionPolicy
import com.ramish.sadiq.core.insight.UsageDayRow
import com.ramish.sadiq.core.model.GuardConfig
import com.ramish.sadiq.core.schedule.DefaultScheduleEvaluator
import com.ramish.sadiq.guard.ContextAssembler
import com.ramish.sadiq.guard.GuardControllerImpl
import com.ramish.sadiq.guard.SystemClockSource
import com.ramish.sadiq.testing.DefaultTestPrefs
import com.ramish.sadiq.testing.FakeClock
import com.ramish.sadiq.testing.FakeInstalledAppsSource
import com.ramish.sadiq.testing.FakePermissionStatusProvider
import com.ramish.sadiq.testing.FakeSettingsRepository
import com.ramish.sadiq.testing.FakeStarRepository
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * OB-1 (WP-17.fix-3): the wizard may only tell the screen it is finished once `onboardingDone` is on
 * disk, and a wizard that opens on an install which is already set up must leave instead of starting
 * over.
 *
 * Both are the same bug seen from two sides. `done` used to flip before the writes, so the screen left
 * the wizard, the Sky composed against a still-false preference and asked for onboarding again — and
 * Back, which is reachable until the screen actually leaves, could walk a half-finished wizard
 * backwards while its writes were in flight. The tests hold the preference write open with a gate, so
 * what is asserted is the order the real DataStore fsync (100 ms or more) can hide on a device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    /**
     * The main dispatcher's scheduler, shared with [runTest] so `runCurrent` drives the ViewModel's
     * `viewModelScope` and the test body on the same virtual clock.
     */
    private val scheduler = TestCoroutineScheduler()
    private val main = StandardTestDispatcher(scheduler)

    private val clock = FakeClock()

    /** One installed feed, so the app list the wizard persists is not empty. */
    private val installed = FakeInstalledAppsSource(
        listOf(AppInfo(packageName = INSTAGRAM, label = "Instagram", isSuggestedFeed = true))
    )

    /** Nothing granted: the plan keeps every step, so no reconcile can finish the wizard by itself. */
    private val permissions = FakePermissionStatusProvider()

    private val launcher = object : OemSettingsLauncher {
        override fun open(target: SettingsTarget): Boolean = true
    }

    private val stars = FakeStarRepository()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(main)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * The wizard under test.
     *
     * `SystemClockSource` is only read by `recordWelcomeStar`, which none of these paths reach; the
     * Context is a JVM stub (`unitTests.isReturnDefaultValues`) and is never touched either — the
     * wizard's only use of it is launching a package in the self-test.
     */
    private fun TestScope.viewModel(settings: FakeSettingsRepository): OnboardingViewModel =
        OnboardingViewModel(
            context = ContextWrapper(null),
            permsProvider = permissions,
            settings = settings,
            oemSettingsLauncher = launcher,
            installedAppsSource = installed,
            guardController = guardController(settings),
            starRepository = stars,
            clock = SystemClockSource(ContextWrapper(null)),
        )

    /**
     * The real controller, as `GuardModule` builds it.
     *
     * The wizard takes the concrete `GuardControllerImpl`, so a fake is not an option. It is inert
     * here on purpose: `start()` is never called, so the engine processes no input, and nothing in
     * these tests reaches the self-test path.
     */
    private fun TestScope.guardController(settings: FakeSettingsRepository): GuardControllerImpl =
        GuardControllerImpl(
            reducer = GuardEngine(
                economy = DefaultStarEconomy(),
                reflection = DefaultReflectionPolicy(),
                suggester = DefaultNextActionSuggester(),
            ),
            clock = clock,
            assembler = ContextAssembler(DefaultScheduleEvaluator()),
            settings = settings,
            snapshots = NoSnapshot,
            usage = NoUsage,
            permissions = permissions,
            scope = backgroundScope,
        )

    @Test
    fun finish_sets_done_only_after_onboardingDone_is_written() = runTest(main) {
        val gate = CompletableDeferred<Unit>()
        val settings = FakeSettingsRepository()
        settings.beforeUpdatePrefsLands = { gate.await() }

        val viewModel = viewModel(settings)
        runCurrent()
        assertFalse(viewModel.uiState.value.done, "the wizard opened already finished")
        assertFalse(settings.currentPrefs().onboardingDone)

        viewModel.finish()
        runCurrent()

        // The four choice writes have run (apps, mode, sleep gate, morning); the preference write is
        // in flight and must not be outrun. `done` here is what the screen navigates on.
        assertEquals(4, settings.applied.size, "the choices must be written before the flag")
        assertFalse(settings.currentPrefs().onboardingDone, "the preference write has already landed")
        assertFalse(viewModel.uiState.value.done, "done flipped before onboardingDone was written")

        gate.complete(Unit)
        runCurrent()

        assertTrue(settings.currentPrefs().onboardingDone, "onboardingDone was never written")
        assertTrue(viewModel.uiState.value.done, "the screen is never told it may leave")
    }

    @Test
    fun opened_with_onboardingDone_true_leaves_immediately() = runTest(main) {
        val settings = FakeSettingsRepository(
            initialPrefs = DefaultTestPrefs.copy(onboardingDone = true, onboardingStep = 0)
        )

        val viewModel = viewModel(settings)
        val before = viewModel.uiState.value
        assertTrue(before.loading, "the wizard must start loading")
        assertTrue(before.steps.isEmpty(), "a step existed before the preferences were read")

        runCurrent()
        val after = viewModel.uiState.value

        assertFalse(after.loading, "the wizard stayed on its loading state")
        assertTrue(after.done, "a set-up install was shown the wizard again")
        assertTrue(after.steps.isEmpty(), "the wizard built a plan it should never have shown")
        assertNull(after.currentStep, "the wizard opened on a step")
        assertTrue(settings.applied.isEmpty(), "the wizard wrote a choice it was never asked for")
    }

    @Test
    fun back_during_finish_is_ignored() = runTest(main) {
        val gate = CompletableDeferred<Unit>()
        // Resuming at O2 puts the wizard past the first step, so an unguarded Back would visibly move.
        val settings = FakeSettingsRepository(
            initialPrefs = DefaultTestPrefs.copy(onboardingStep = APPS_INDEX)
        )
        settings.beforeUpdatePrefsLands = { gate.await() }

        val viewModel = viewModel(settings)
        runCurrent()
        val opening = viewModel.uiState.value.index
        assertEquals(APPS_INDEX, opening, "the wizard did not resume at O2")

        viewModel.finish()
        runCurrent()

        viewModel.back()
        runCurrent()

        assertEquals(
            opening,
            viewModel.uiState.value.index,
            "Back moved the wizard while its writes were still running",
        )
        assertEquals(OnboardingPlan.StepId.APPS, viewModel.uiState.value.currentStep)

        gate.complete(Unit)
        runCurrent()
        assertTrue(viewModel.uiState.value.done, "the wizard never finished")
    }

    private companion object {
        const val INSTAGRAM = "com.instagram.android"

        /** O2 is index 1: WELCOME is the only choice step before it. */
        const val APPS_INDEX = 1
    }
}

/** No saved engine state: the controller never starts, so nothing is read or written. */
private object NoSnapshot : EngineSnapshotStore {
    override suspend fun load(): EngineState? = null

    override suspend fun save(state: EngineState) = Unit
}

/**
 * Usage stats with no access at all, which is the one answer that keeps the controller from querying
 * anything: `reconcile` returns before it reads a span.
 */
private object NoUsage : UsageHistorySource {
    override fun hasAccess(): Boolean = false

    override suspend fun spans(fromWall: Long, toWall: Long): List<UsageSpan> = emptyList()

    override suspend fun dailyRows(days: IntRange, zoneId: String, config: GuardConfig): List<UsageDayRow> =
        emptyList()

    override suspend fun hourlyMs(days: IntRange, zoneId: String, packages: Set<String>): LongArray =
        LongArray(24)
}
