package com.ramish.sadiq.ui.sky

import android.content.ContextWrapper
import com.ramish.sadiq.contract.HomeAction
import com.ramish.sadiq.contract.HomeCardModel
import com.ramish.sadiq.contract.HomeCardProvider
import com.ramish.sadiq.contract.SettingsTarget
import com.ramish.sadiq.core.economy.DefaultStarEconomy
import com.ramish.sadiq.core.schedule.DefaultScheduleEvaluator
import com.ramish.sadiq.testing.DefaultTestPrefs
import com.ramish.sadiq.testing.FakeClock
import com.ramish.sadiq.testing.FakeGuardController
import com.ramish.sadiq.testing.FakePermissionStatusProvider
import com.ramish.sadiq.testing.FakeSettingsRepository
import com.ramish.sadiq.testing.FakeStarRepository
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * OB-2 (WP-18.fix-2): the Sky asks for onboarding exactly while `onboardingDone` is false, and never
 * answers from a latch of its own.
 *
 * `SkyViewModel` used to keep a `_needsOnboarding` latch that was set on the first `false` reading and
 * never cleared, so the wizard was permanent: finishing setup wrote the preference, but the Sky had
 * already decided. The first test is that regression, stated as the sequence the user causes — a
 * false preference followed by a true one. The second pins the cold start, which is the other half:
 * before the preference has been read the state is `loading = true` and must not request onboarding,
 * or a launch would redirect on a value that does not exist yet.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SkyViewModelTest {

    /**
     * The main dispatcher's scheduler, shared with [runTest] so `runCurrent` drives the ViewModel's
     * `viewModelScope` and the test body on the same virtual clock.
     */
    private val scheduler = TestCoroutineScheduler()
    private val main = StandardTestDispatcher(scheduler)

    private val clock = FakeClock()
    private val settings = FakeSettingsRepository()
    private val stars = FakeStarRepository()
    private val guard = FakeGuardController()

    /**
     * Accessibility off, which is what a fresh install looks like: the resolver answers "asleep" from
     * its first branch and never formats a string, so the test needs no Android resources.
     */
    private val permissions = FakePermissionStatusProvider()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(main)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(): SkyViewModel = SkyViewModel(
        starRepository = stars,
        settingsRepository = settings,
        guardController = guard,
        homeCardProviders = setOf(NoCard),
        permissionStatusProvider = permissions,
        homeAction = NoHome,
        skyStatusResolver = SkyStatusResolver(ContextWrapper(null)),
        starEconomy = DefaultStarEconomy(),
        clock = clock,
        scheduleEvaluator = DefaultScheduleEvaluator(),
    )

    @Test
    fun prefs_false_then_true_ends_with_needsOnboarding_false() = runTest(main) {
        settings.setPrefs(DefaultTestPrefs.copy(onboardingDone = false))
        val viewModel = viewModel()
        val subscription = backgroundScope.launch { viewModel.uiState.collect { } }
        runCurrent()

        assertFalse(viewModel.uiState.value.loading, "the sky never left its loading state")
        assertTrue(
            viewModel.uiState.value.needsOnboarding,
            "a fresh install must be sent to the wizard",
        )

        // The wizard finished: the preference is written and the Sky re-reads it.
        settings.setPrefs(DefaultTestPrefs.copy(onboardingDone = true))
        runCurrent()

        assertFalse(
            viewModel.uiState.value.needsOnboarding,
            "the latch survived the write, which makes onboarding permanent",
        )

        // Later emissions — a permission read, say — must not resurrect the request either.
        permissions.update { it.copy(notifications = true) }
        runCurrent()
        assertFalse(viewModel.uiState.value.needsOnboarding, "an unrelated emission asked for the wizard")

        subscription.cancel()
        advanceTimeBy(STOP_TIMEOUT_MS + 100L)
        runCurrent()
    }

    @Test
    fun initial_state_never_requests_onboarding() = runTest(main) {
        // A fresh install: the preference is false on disk, but the Sky has not read it yet.
        settings.setPrefs(DefaultTestPrefs.copy(onboardingDone = false))
        val viewModel = viewModel()

        val initial = viewModel.uiState.value
        assertTrue(initial.loading, "the Sky must start loading")
        assertFalse(
            initial.needsOnboarding,
            "the Sky asked for onboarding before it had read the preference",
        )

        // The contrast, so the assertion above cannot pass on a flag that is simply hard-wired to
        // false: once the preference has been read, a false value does ask for the wizard.
        val subscription = backgroundScope.launch { viewModel.uiState.collect { } }
        runCurrent()
        assertTrue(viewModel.uiState.value.needsOnboarding, "the Sky never asked for the wizard")

        subscription.cancel()
        advanceTimeBy(STOP_TIMEOUT_MS + 100L)
        runCurrent()
    }

    private companion object {
        /** `SharingStarted.WhileSubscribed`: how long the state stays subscribed with no collector. */
        const val STOP_TIMEOUT_MS: Long = 5_000L
    }
}

/** One provider with nothing to say: the Sky renders at most one card, and these tests need none. */
private object NoCard : HomeCardProvider {
    override val priority: Int = 0

    override val card: Flow<HomeCardModel?> = flowOf(null)
}

/** Nothing to send the user to: no card in these tests carries an action. */
private object NoHome : HomeAction {
    override fun goHome(): Boolean = false

    override fun openSadiq(route: String) = Unit

    override fun openSettings(target: SettingsTarget) = Unit
}
