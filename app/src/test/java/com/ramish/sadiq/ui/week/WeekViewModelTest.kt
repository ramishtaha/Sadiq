package com.ramish.sadiq.ui.week

import com.ramish.sadiq.R
import com.ramish.sadiq.contract.AppInfo
import com.ramish.sadiq.contract.ApplyResult
import com.ramish.sadiq.contract.SettingsTarget
import com.ramish.sadiq.core.apps.AppCatalog
import com.ramish.sadiq.core.economy.StarAward
import com.ramish.sadiq.core.economy.StarKind
import com.ramish.sadiq.core.engine.OpenOutcome
import com.ramish.sadiq.core.engine.RecordEvent
import com.ramish.sadiq.core.engine.SurfaceKind
import com.ramish.sadiq.core.insight.DefaultWeeklyReviewBuilder
import com.ramish.sadiq.core.insight.ReflectionReason
import com.ramish.sadiq.core.insight.UsageDayRow
import com.ramish.sadiq.core.insight.WeeklyInput
import com.ramish.sadiq.core.insight.WeeklySuggestion
import com.ramish.sadiq.core.model.GuardedApp
import com.ramish.sadiq.core.model.GuardConfig
import com.ramish.sadiq.core.model.WindowKind
import com.ramish.sadiq.core.strict.SettingsChange
import com.ramish.sadiq.core.time.LogicalDays
import com.ramish.sadiq.review.UsageDayAggregator
import com.ramish.sadiq.review.WeekCoverage
import com.ramish.sadiq.testing.FakeClock
import com.ramish.sadiq.testing.FakeHistoryRepository
import com.ramish.sadiq.testing.FakeSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Week screen's ViewModel, tested through the repositories it actually uses.
 *
 * The review maths is `core`'s and is tested there; what is tested here is the wiring §4.3 S4 needs
 * from the app: which week is read, what the screen is told when the records are too young, that the
 * "Approximate" flag survives, that usage rows are refreshed on open, and that the one suggestion
 * becomes exactly one [SettingsChange].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WeekViewModelTest {

    private val clock = FakeClock()
    private val today: Int = LogicalDays.of(clock.now().wallMs, clock.now().zoneId)
    private val week: IntRange = WeekCoverage.reviewDays(today)
    private val previous: IntRange = WeekCoverage.comparisonDays(today)

    private val history = FakeHistoryRepository()
    private val settings = FakeSettingsRepository()
    private val usage = FakeUsageHistorySource()
    private val launcher = FakeOemSettingsLauncher()
    private val apps = FakeInstalledAppsSource(
        listOf(
            AppInfo(packageName = INSTAGRAM, label = "Instagram", isSuggestedFeed = true),
            AppInfo(packageName = "com.google.android.youtube", label = "YouTube", isSuggestedFeed = true),
        )
    )

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(): WeekViewModel = WeekViewModel(
        historyRepository = history,
        settingsRepository = settings,
        installedAppsSource = apps,
        usageHistory = usage,
        usageDayAggregator = UsageDayAggregator(usage, history, settings, clock),
        weeklyReviewBuilder = DefaultWeeklyReviewBuilder(knownFeeds = AppCatalog.knownFeeds.toSet()),
        oemSettingsLauncher = launcher,
        clock = clock,
    )

    /**
     * A week with one guarded app, and a comparison week behind it.
     *
     * @param usageDays the days with usage rows; the default is the whole reviewed week.
     * @param peakHour the hour the profile puts the week's use in, or null for an empty profile.
     */
    private fun weekTemplate(
        usageDays: List<Int> = week.toList(),
        prevUsageDays: List<Int> = previous.toList(),
        perDayMs: Long = 3_600_000L,
        peakHour: Int? = 22,
        stars: List<StarAward> = emptyList(),
        opens: List<RecordEvent.Open> = emptyList(),
        reflections: List<RecordEvent.Reflected> = emptyList(),
        focusMinutes: Int = 0,
        quietNights: Int = 0,
        approximate: Boolean = false,
    ): WeeklyInput = WeeklyInput(
        days = week,
        usage = usageDays.map { day -> UsageDayRow(day, INSTAGRAM, perDayMs, 1, 0L) },
        prevUsage = prevUsageDays.map { day -> UsageDayRow(day, INSTAGRAM, 2_000_000L, 1, 0L) },
        stars = stars,
        opens = opens,
        reflections = reflections,
        focusMinutes = focusMinutes,
        quietNights = quietNights,
        hourlyMs = LongArray(24).also { hours ->
            if (peakHour != null) hours[peakHour] = 1_000_000L
        },
        config = GuardConfig(),
        approximate = approximate,
    )

    private fun open(day: Int, outcome: OpenOutcome): RecordEvent.Open = RecordEvent.Open(
        at = clock.now().wallMs,
        logicalDay = day,
        packageName = INSTAGRAM,
        domain = null,
        surface = SurfaceKind.PAUSE,
        outcome = outcome,
        decisionMs = null,
        grantId = null,
    )

    private fun reflected(day: Int, reason: ReflectionReason): RecordEvent.Reflected =
        RecordEvent.Reflected(
            at = clock.now().wallMs,
            logicalDay = day,
            reason = reason,
            packageName = INSTAGRAM,
            opensInWindow = 3,
        )

    @Test
    fun theWeekIsBuiltByTheCoreBuilderAndShownInFull() = runTest {
        history.week = weekTemplate(
            stars = listOf(
                StarAward(StarKind.LEAVE, 0L, week.last, WindowKind.NONE, INSTAGRAM, null),
                // A star from before the window must not be counted in this week.
                StarAward(StarKind.LEAVE, 0L, week.first - 1, WindowKind.NONE, INSTAGRAM, null),
            ),
            opens = listOf(
                open(week.last, OpenOutcome.LEFT),
                open(week.last, OpenOutcome.LEFT_GESTURE),
                open(week.last, OpenOutcome.STAYED),
            ),
            reflections = listOf(
                reflected(week.last, ReflectionReason.TIRED),
                reflected(week.last - 1, ReflectionReason.TIRED),
                reflected(week.last - 2, ReflectionReason.BORED),
            ),
            focusMinutes = 75,
            quietNights = 4,
        )

        val viewModel = viewModel()
        viewModel.refresh()
        val state = viewModel.uiState.value

        assertFalse(state.loading)
        assertTrue(state.available)
        assertFalse(state.approximate)

        assertEquals(7 * 3_600_000L, state.totalMs)
        assertEquals(7 * 3_600_000L - 7 * 2_000_000L, state.deltaMs)

        // Leaves are counted from opens, not stars: a leave still happened when no star was awarded.
        assertEquals(1, state.stars)
        assertEquals(2, state.leaves)
        assertEquals(4, state.quietNights)
        assertEquals(75, state.focusMinutes)

        assertEquals(ReflectionReason.TIRED, state.topReason)
        assertEquals(22, state.peakStartHour)

        assertEquals(listOf("Instagram"), state.topApps.map { bar -> bar.label })
        assertEquals(1f, state.topApps.single().fraction)
        assertTrue(state.suggestion is WeeklySuggestion.MoveSleepGate)
    }

    @Test
    fun beforeAWeekOfRecordsTheScreenSaysSo() = runTest {
        history.week = weekTemplate(usageDays = listOf(week.last), prevUsageDays = emptyList())

        val viewModel = viewModel()
        viewModel.refresh()
        val state = viewModel.uiState.value

        assertFalse(state.available)
        // The numbers are still there, and still true; only the claim "this is a week" is withheld.
        assertEquals(3_600_000L, state.totalMs)
        assertNull(state.deltaMs)
    }

    @Test
    fun theApproximateFlagIsCarriedFromTheRepositoryToTheScreen() = runTest {
        history.week = weekTemplate(approximate = true)

        val viewModel = viewModel()
        viewModel.refresh()

        assertTrue(viewModel.uiState.value.approximate)
    }

    @Test
    fun thePeakBandComesFromMeasuredUsageWhenItCanBeRead() = runTest {
        settings.setConfig(GuardConfig(apps = listOf(GuardedApp(INSTAGRAM, "Instagram"))))
        // The repository's grant profile is empty; only the platform's measured profile has use in it.
        history.week = weekTemplate(peakHour = null)
        usage.hourly = LongArray(24).also { hours -> hours[3] = 1_800_000L }

        val viewModel = viewModel()
        viewModel.refresh()

        assertEquals(3, viewModel.uiState.value.peakStartHour)
        assertEquals(week, usage.lastHourlyDays)
    }

    @Test
    fun theBedtimeSuggestionAppliesExactlyOneTighteningChange() = runTest {
        history.week = weekTemplate()

        val viewModel = viewModel()
        viewModel.refresh()
        val suggestion = viewModel.uiState.value.suggestion
        assertTrue(suggestion is WeeklySuggestion.MoveSleepGate)
        val move = suggestion as WeeklySuggestion.MoveSleepGate
        assertEquals(22 * 60, move.toMinuteOfDay)

        viewModel.onApplySuggestion()

        val change = settings.applied.single()
        assertTrue(change is SettingsChange.SetSleepGate)
        val gate = change as SettingsChange.SetSleepGate
        assertEquals(22 * 60, gate.cfg.startMinuteOfDay)
        assertTrue(gate.cfg.enabled)

        val state = viewModel.uiState.value
        assertTrue(state.suggestionApplied)
        assertNull(state.suggestion)
    }

    @Test
    fun theSuggestionCannotBeAppliedTwice() = runTest {
        history.week = weekTemplate()

        val viewModel = viewModel()
        viewModel.refresh()
        viewModel.onApplySuggestion()
        viewModel.onApplySuggestion()

        assertEquals(1, settings.applied.size)
    }

    @Test
    fun theGuardSuggestionAddsTheAppWithItsLauncherLabel() = runTest {
        // No peak hour, so no bedtime move; the default mode is Steady, so no mode switch either.
        history.week = weekTemplate(peakHour = null, perDayMs = 2 * 3_600_000L)

        val viewModel = viewModel()
        viewModel.refresh()
        val suggestion = viewModel.uiState.value.suggestion
        assertTrue(suggestion is WeeklySuggestion.GuardApp)
        val guard = suggestion as WeeklySuggestion.GuardApp
        assertEquals(INSTAGRAM, guard.packageName)
        assertEquals("Instagram", viewModel.uiState.value.suggestionAppLabel)

        viewModel.onApplySuggestion()

        val change = settings.applied.single()
        assertTrue(change is SettingsChange.SetApps)
        val appsChange = change as SettingsChange.SetApps
        assertEquals(listOf(GuardedApp(INSTAGRAM, "Instagram")), appsChange.apps)
    }

    @Test
    fun aDelayedApplicationIsReportedWithItsTime() = runTest {
        history.week = weekTemplate()
        settings.applyResult = ApplyResult.Scheduled(APPLIED_AT_WALL)

        val viewModel = viewModel()
        viewModel.refresh()
        viewModel.onApplySuggestion()

        assertTrue(viewModel.uiState.value.suggestionApplied)
        assertEquals(APPLIED_AT_WALL, viewModel.uiState.value.suggestionAppliedAtWall)
    }

    @Test
    fun openingTheTabRefreshesTheStoredUsageRows() = runTest {
        usage.rows = listOf(
            UsageDayRow(week.last, INSTAGRAM, 1_000L, 1, 0L),
            UsageDayRow(week.last - 1, INSTAGRAM, 2_000L, 1, 0L),
            // Outside the fourteen-day window: the aggregator must not write it.
            UsageDayRow(today - 20, INSTAGRAM, 3_000L, 1, 0L),
        )
        history.week = weekTemplate()

        viewModel().refresh()

        assertEquals(2, history.usage.size)
        assertEquals((today - 13)..today, usage.lastDays)
    }

    @Test
    fun withoutUsageAccessNothingIsWritten() = runTest {
        usage.access = false
        usage.rows = listOf(UsageDayRow(week.last, INSTAGRAM, 1_000L, 1, 0L))
        history.week = weekTemplate()

        viewModel().refresh()

        assertTrue(history.usage.isEmpty())
        assertNull(usage.lastDays)
    }

    @Test
    fun theApproximateFixOpensTheUsageAccessPage() = runTest {
        history.week = weekTemplate(approximate = true)

        val viewModel = viewModel()
        viewModel.refresh()
        viewModel.onTurnOnUsageAccess()

        assertEquals(SettingsTarget.USAGE_ACCESS, launcher.opened)
    }

    /**
     * A5-06. An approximate week is one Sadiq could not measure — `usage.isEmpty()` — so the tab must
     * say that it cannot see, and never that no time was recorded. The two claims are different, and
     * only one of them is true when usage access is off.
     */
    @Test
    fun anApproximateWeekWithoutUsageAccessSaysItCannotSee() = runTest {
        usage.access = false
        history.week = weekTemplate(
            usageDays = emptyList(),
            prevUsageDays = emptyList(),
            approximate = true,
        )

        val viewModel = viewModel()
        viewModel.refresh()
        val state = viewModel.uiState.value

        assertTrue(state.approximate)
        assertFalse(state.usageAccess)
        assertTrue(state.topApps.isEmpty())
        assertEquals(R.string.week_no_usage, whereItWentEmptyText(state.approximate, state.usageAccess))
        assertNotEquals(R.string.week_no_apps, whereItWentEmptyText(state.approximate, state.usageAccess))
    }

    /**
     * The other approximate cause: the permission is held and the week's rows have not arrived. The
     * screen must not send him to a page where the switch is already on.
     */
    @Test
    fun anApproximateWeekWithUsageAccessBlamesTheRowsNotThePermission() = runTest {
        usage.access = true
        history.week = weekTemplate(
            usageDays = emptyList(),
            prevUsageDays = emptyList(),
            approximate = true,
        )

        val viewModel = viewModel()
        viewModel.refresh()
        val state = viewModel.uiState.value

        assertTrue(state.approximate)
        assertTrue(state.usageAccess)
        assertEquals(R.string.week_no_usage_rows, whereItWentEmptyText(state.approximate, state.usageAccess))
        assertNotEquals(R.string.week_no_usage, whereItWentEmptyText(state.approximate, state.usageAccess))
    }

    @Test
    fun aMeasuredWeekWithNothingInItSaysNoTimeWasRecorded() = runTest {
        // With usage access there is no "cannot see" excuse, and an empty measured week means exactly
        // what it says.
        history.week = weekTemplate(usageDays = emptyList(), prevUsageDays = emptyList())

        val viewModel = viewModel()
        viewModel.refresh()
        val state = viewModel.uiState.value

        assertFalse(state.approximate)
        assertEquals(R.string.week_no_apps, whereItWentEmptyText(state.approximate, state.usageAccess))
    }

    private companion object {
        const val INSTAGRAM = "com.instagram.android"

        /** 22:40 on the evening the fake clock starts from. */
        const val APPLIED_AT_WALL = 1_793_292_000_000L
    }
}
