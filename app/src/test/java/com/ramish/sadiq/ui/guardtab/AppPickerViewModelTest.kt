package com.ramish.sadiq.ui.guardtab

import com.ramish.sadiq.contract.AppInfo
import com.ramish.sadiq.contract.ApplyResult
import com.ramish.sadiq.core.model.GuardConfig
import com.ramish.sadiq.core.model.GuardedApp
import com.ramish.sadiq.core.model.Mode
import com.ramish.sadiq.core.model.SurfaceMode
import com.ramish.sadiq.core.strict.SettingsChange
import com.ramish.sadiq.strict.FakePendingChangesRepository
import com.ramish.sadiq.testing.FakeInstalledAppsSource
import com.ramish.sadiq.testing.FakeSettingsRepository
import kotlinx.coroutines.CompletableDeferred
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val INSTAGRAM: String = "com.instagram.android"
private const val REDDIT: String = "com.reddit.frontpage"
private const val YOUTUBE: String = "com.google.android.youtube"

/** `SharingStarted.WhileSubscribed` timeout, so a test can leave the shared flow with nothing pending. */
private const val STOP_TIMEOUT: Long = 5_000L

/**
 * Audit-4 A4-11, the picker's save half.
 *
 * Three things are pinned here, all of them silent failures in the app:
 *  - A config emission that lands mid-edit must not erase the apps he just ticked. The ViewModel
 *    keeps a delta of new ticks instead of mirroring the config, so an emission can only add.
 *  - A save must report back, and must report back **after** the write returned. The screen pops
 *    from that callback; popping first cancelled the write at the end of the exit transition.
 *  - A save writes the whole selection — including zero, so the last guarded app can be dropped —
 *    and reuses the configured [GuardedApp] so a `SHORTS_ONLY` app keeps its surface mode.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppPickerViewModelTest {

    /**
     * Runs a test on a Main dispatcher that shares its scheduler with the test body, because
     * [AppPickerViewModel] launches its save in `viewModelScope`, which is `Dispatchers.Main`.
     */
    private fun pickerTest(body: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            body()
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun createViewModel(
        repository: FakeSettingsRepository,
        pending: FakePendingChangesRepository = FakePendingChangesRepository(),
        installedApps: List<AppInfo> = defaultInstalledApps,
    ): AppPickerViewModel = AppPickerViewModel(
        installedAppsSource = FakeInstalledAppsSource(installedApps),
        settingsRepository = repository,
        pendingChanges = pending,
    )

    /** Collects [AppPickerViewModel.uiState] so the `WhileSubscribed` shared flow actually runs. */
    private fun TestScope.listen(viewModel: AppPickerViewModel): Job =
        launch { viewModel.uiState.collect { } }

    /** Drops the subscription, then lets `WhileSubscribed` cancel the upstream and time out. */
    private fun TestScope.stopListening(job: Job) {
        job.cancel()
        advanceTimeBy(STOP_TIMEOUT + 100L)
        runCurrent()
    }

    private fun AppPickerUiState.row(pkg: String): AppRow? =
        (suggestedApps + allApps).firstOrNull { it.packageName == pkg }

    @Test
    fun aConfigEmissionMidEditKeepsNewTicksAndUnTicks() = pickerTest {
        val repository = FakeSettingsRepository(
            initialConfig = GuardConfig(
                apps = listOf(
                    GuardedApp(INSTAGRAM, "Instagram"),
                    GuardedApp(REDDIT, "Reddit"),
                ),
            ),
        )
        val viewModel = createViewModel(repository)
        val subscription = listen(viewModel)
        runCurrent()

        // He ticks an app that is not guarded yet, and un-ticks one that is.
        viewModel.onAppToggle(YOUTUBE, "YouTube", true)
        viewModel.onAppToggle(REDDIT, "Reddit", false)
        runCurrent()
        assertEquals(setOf(INSTAGRAM, YOUTUBE), viewModel.uiState.value.selectedPackages)

        // A "Think it over" change, or a mode edit, lands while he is still choosing. It carries the
        // same app list; the old ViewModel overwrote its ticks from every emission like this one.
        repository.setConfig(
            GuardConfig(
                mode = Mode.HARDCORE,
                apps = listOf(
                    GuardedApp(INSTAGRAM, "Instagram"),
                    GuardedApp(REDDIT, "Reddit"),
                ),
            ),
        )
        runCurrent()

        assertEquals(
            setOf(INSTAGRAM, YOUTUBE),
            viewModel.uiState.value.selectedPackages,
            "an emission must not erase a tick he made, nor bring back one he un-ticked",
        )
        assertEquals(true, viewModel.uiState.value.row(YOUTUBE)?.isSelected)
        assertEquals(false, viewModel.uiState.value.row(REDDIT)?.isSelected)
        assertEquals(true, viewModel.uiState.value.row(INSTAGRAM)?.isSelected)

        stopListening(subscription)
    }

    @Test
    fun theSavePassesTheWholeSelectionAndKeepsEachSurfaceMode() = pickerTest {
        val repository = FakeSettingsRepository(
            initialConfig = GuardConfig(
                apps = listOf(
                    GuardedApp(INSTAGRAM, "Instagram", SurfaceMode.SHORTS_ONLY),
                    GuardedApp(REDDIT, "Reddit"),
                ),
            ),
        )
        val viewModel = createViewModel(repository)
        val subscription = listen(viewModel)
        runCurrent()

        viewModel.onAppToggle(YOUTUBE, "YouTube", true)
        viewModel.onAppToggle(REDDIT, "Reddit", false)
        runCurrent()

        var reported: ApplyResult? = null
        viewModel.saveGuardedApps { reported = it }
        runCurrent()

        val saved = repository.applied.single() as SettingsChange.SetApps
        assertEquals(
            listOf(
                // The configured app is written back as it was, SHORTS_ONLY intact, not rebuilt
                // from the package and label with the WHOLE_APP default.
                GuardedApp(INSTAGRAM, "Instagram", SurfaceMode.SHORTS_ONLY),
                // A new app takes its label from the installed list, not from the query's rows.
                GuardedApp(YOUTUBE, "YouTube"),
            ),
            saved.apps,
        )
        assertEquals(ApplyResult.Applied, reported)

        stopListening(subscription)
    }

    @Test
    fun unGuardingTheLastAppIsSavedAsAnEmptySelection() = pickerTest {
        val repository = FakeSettingsRepository(
            initialConfig = GuardConfig(apps = listOf(GuardedApp(INSTAGRAM, "Instagram"))),
        )
        val viewModel = createViewModel(repository)
        val subscription = listen(viewModel)
        runCurrent()

        viewModel.onAppToggle(INSTAGRAM, "Instagram", false)
        runCurrent()
        assertTrue(
            viewModel.uiState.value.selectedPackages.isEmpty(),
            "the button used to be disabled here, so the last app could never be un-guarded",
        )

        var saves: Int = 0
        viewModel.saveGuardedApps { saves += 1 }
        runCurrent()

        val saved = repository.applied.single() as SettingsChange.SetApps
        assertEquals(emptyList<GuardedApp>(), saved.apps)
        assertEquals(1, saves)

        stopListening(subscription)
    }

    @Test
    fun theCallbackWaitsForTheWriteAndCarriesItsResult() = pickerTest {
        val repository = FakeSettingsRepository()
        val release = CompletableDeferred<Unit>()
        repository.beforeApplyReturns = { release.await() }
        repository.applyResult = ApplyResult.Scheduled(1_000L)

        val viewModel = createViewModel(repository)
        val subscription = listen(viewModel)
        runCurrent()

        viewModel.onAppToggle(INSTAGRAM, "Instagram", true)
        runCurrent()

        var reported: ApplyResult? = null
        viewModel.saveGuardedApps { reported = it }
        runCurrent()

        assertEquals(1, repository.applied.size, "the write must be in flight")
        assertNull(
            reported,
            "the screen must not be told before the transaction can still be rolled back",
        )

        release.complete(Unit)
        runCurrent()

        assertEquals(ApplyResult.Scheduled(1_000L), reported, "a deferred save still reports back")
        stopListening(subscription)
    }

    @Test
    fun aSecondTapWhileTheWriteIsOpenDoesNotSaveTwice() = pickerTest {
        val repository = FakeSettingsRepository()
        val release = CompletableDeferred<Unit>()
        repository.beforeApplyReturns = { release.await() }

        val viewModel = createViewModel(repository)
        val subscription = listen(viewModel)
        runCurrent()

        viewModel.onAppToggle(INSTAGRAM, "Instagram", true)
        runCurrent()

        var saves: Int = 0
        viewModel.saveGuardedApps { saves += 1 }
        viewModel.saveGuardedApps { saves += 1 }
        runCurrent()

        release.complete(Unit)
        runCurrent()

        // Two pops would take him past the Guard tab, so the second tap is dropped with its callback.
        assertEquals(1, repository.applied.size)
        assertEquals(1, saves)

        stopListening(subscription)
    }

    /**
     * A5-01, the picker half: an app whose removal is already queued must render unticked, because
     * that is what he will actually have. The live config keeps it until the row lands, which is what
     * made him un-tick it a second time and made the second save record the same removal again.
     */
    @Test
    fun aQueuedRemovalRendersUnticked() = pickerTest {
        val repository = FakeSettingsRepository(
            initialConfig = GuardConfig(
                apps = listOf(GuardedApp(INSTAGRAM, "Instagram"), GuardedApp(REDDIT, "Reddit")),
            ),
        )
        val pending = FakePendingChangesRepository()
        pending.add(
            SettingsChange.SetApps(listOf(GuardedApp(REDDIT, "Reddit")), removed = setOf(INSTAGRAM)),
            applyAt = 1_000L,
        )
        val viewModel = createViewModel(repository, pending)
        val subscription = listen(viewModel)
        runCurrent()

        assertEquals(
            false,
            viewModel.uiState.value.row(INSTAGRAM)?.isSelected,
            "a removal that is already waiting is not a tick he still holds",
        )
        assertEquals(setOf(REDDIT), viewModel.uiState.value.selectedPackages)
        assertEquals(
            1_000L,
            viewModel.uiState.value.pendingRemovals[INSTAGRAM],
            "the screen needs the moment to say when it stops being guarded",
        )

        stopListening(subscription)
    }

    /** The other direction: an app a waiting row will add is shown as ticked before it lands. */
    @Test
    fun aQueuedAdditionRendersTicked() = pickerTest {
        val repository = FakeSettingsRepository(
            initialConfig = GuardConfig(apps = listOf(GuardedApp(INSTAGRAM, "Instagram"))),
        )
        val pending = FakePendingChangesRepository()
        pending.add(
            SettingsChange.SetApps(
                listOf(GuardedApp(INSTAGRAM, "Instagram"), GuardedApp(YOUTUBE, "YouTube")),
                removed = emptySet(),
            ),
            applyAt = 1_000L,
        )
        val viewModel = createViewModel(repository, pending)
        val subscription = listen(viewModel)
        runCurrent()

        assertEquals(true, viewModel.uiState.value.row(YOUTUBE)?.isSelected)
        assertEquals(setOf(INSTAGRAM, YOUTUBE), viewModel.uiState.value.selectedPackages)

        stopListening(subscription)
    }

    /**
     * A5-01, the save half: ticking an app whose removal is waiting is a decision, and the only one
     * that can call the removal off. The write itself is a no-op — the table already holds the app —
     * so without this the row would land ten minutes later and un-guard it behind his back.
     */
    @Test
    fun reTickingAPendingRemovalCallsItOff() = pickerTest {
        val repository = FakeSettingsRepository(
            initialConfig = GuardConfig(
                apps = listOf(GuardedApp(INSTAGRAM, "Instagram"), GuardedApp(REDDIT, "Reddit")),
            ),
        )
        val pending = FakePendingChangesRepository()
        pending.add(
            SettingsChange.SetApps(
                listOf(GuardedApp(INSTAGRAM, "Instagram"), GuardedApp(REDDIT, "Reddit")),
                removed = setOf(INSTAGRAM, YOUTUBE),
            ),
            applyAt = 1_000L,
        )
        val viewModel = createViewModel(repository, pending)
        val subscription = listen(viewModel)
        runCurrent()

        assertEquals(false, viewModel.uiState.value.row(INSTAGRAM)?.isSelected)

        // He changes his mind and ticks it again.
        viewModel.onAppToggle(INSTAGRAM, "Instagram", true)
        runCurrent()
        assertEquals(true, viewModel.uiState.value.row(INSTAGRAM)?.isSelected)

        viewModel.saveGuardedApps { }
        runCurrent()

        val row = pending.all.single()
        val rewritten = row.change as SettingsChange.SetApps
        assertFalse(
            INSTAGRAM in rewritten.removed.orEmpty(),
            "the queued row must stop planning to drop the app he just guarded",
        )
        assertTrue(
            YOUTUBE in rewritten.removed.orEmpty(),
            "a package he did not touch keeps its removal",
        )
        assertEquals(1_000L, row.applyAt, "the row keeps the moment it was given")

        stopListening(subscription)
    }
}

/** Instagram is marked as a feed so the suggested section is exercised as well as the full list. */
private val defaultInstalledApps: List<AppInfo> = listOf(
    AppInfo(packageName = INSTAGRAM, label = "Instagram", isSuggestedFeed = true),
    AppInfo(packageName = REDDIT, label = "Reddit", isSuggestedFeed = false),
    AppInfo(packageName = YOUTUBE, label = "YouTube", isSuggestedFeed = true),
)
