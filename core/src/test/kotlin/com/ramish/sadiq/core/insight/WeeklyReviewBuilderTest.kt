package com.ramish.sadiq.core.insight

import com.ramish.sadiq.core.economy.StarAward
import com.ramish.sadiq.core.economy.StarKind
import com.ramish.sadiq.core.engine.OpenOutcome
import com.ramish.sadiq.core.engine.RecordEvent
import com.ramish.sadiq.core.engine.SurfaceKind
import com.ramish.sadiq.core.model.GuardConfig
import com.ramish.sadiq.core.model.GuardedApp
import com.ramish.sadiq.core.model.Mode
import com.ramish.sadiq.core.model.SleepGateConfig
import com.ramish.sadiq.core.model.WindowKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The weekly review maths of §4.3 S4.
 *
 * Every case pins the whole input, so a change in a threshold or a tie-break breaks a test with the rule's
 * name on it instead of quietly moving the numbers on the Week screen.
 */
class WeeklyReviewBuilderTest {

    private val builder: WeeklyReviewBuilder = DefaultWeeklyReviewBuilder()

    // ── totals, delta, bars, peak ────────────────────────────────────────────────────────────────────

    @Test
    fun theTotalSumsOnlyThisWeek() {
        val review = builder.build(
            input(
                usage = listOf(
                    row(WEEK_START, INSTAGRAM, HOUR),
                    row(WEEK_START + 1, YOUTUBE, 2 * HOUR),
                    row(WEEK_START - 1, INSTAGRAM, 5 * HOUR),
                    row(WEEK_END + 1, INSTAGRAM, 9 * HOUR),
                ),
            ),
        )

        assertEquals(3 * HOUR, review.totalMs)
    }

    @Test
    fun theDeltaIsNullWithoutAPreviousWeek() {
        val review = builder.build(input(usage = listOf(row(WEEK_START, INSTAGRAM, HOUR))))

        assertNull(review.deltaMs)
    }

    @Test
    fun theDeltaComparesBothDirections() {
        val up = builder.build(
            input(
                usage = listOf(row(WEEK_START, INSTAGRAM, 3 * HOUR)),
                prevUsage = listOf(row(WEEK_START - 7, INSTAGRAM, HOUR)),
            ),
        )
        val down = builder.build(
            input(
                usage = listOf(row(WEEK_START, INSTAGRAM, HOUR)),
                prevUsage = listOf(row(WEEK_START - 7, INSTAGRAM, 3 * HOUR)),
            ),
        )

        assertEquals(2 * HOUR, up.deltaMs)
        assertEquals(-2 * HOUR, down.deltaMs)
    }

    @Test
    fun theTopThreeAppsAreListedByTime() {
        val review = builder.build(
            input(
                usage = listOf(
                    row(WEEK_START, INSTAGRAM, 4 * HOUR),
                    row(WEEK_START, YOUTUBE, 3 * HOUR),
                    row(WEEK_START + 1, TWITTER, 2 * HOUR),
                    row(WEEK_START + 1, REDDIT, HOUR),
                ),
            ),
        )

        assertEquals(
            listOf(INSTAGRAM to 4 * HOUR, YOUTUBE to 3 * HOUR, TWITTER to 2 * HOUR),
            review.topApps,
        )
    }

    @Test
    fun topAppTiesBreakByName() {
        val review = builder.build(
            input(
                usage = listOf(
                    row(WEEK_START, TWITTER, 2 * HOUR),
                    row(WEEK_START, FACEBOOK, 2 * HOUR),
                ),
            ),
        )

        assertEquals(listOf(FACEBOOK to 2 * HOUR, TWITTER to 2 * HOUR), review.topApps)
    }

    @Test
    fun topAppsIgnoreRowsWithoutUse() {
        val review = builder.build(
            input(
                usage = listOf(
                    row(WEEK_START, INSTAGRAM, 0L),
                    row(WEEK_START, YOUTUBE, -5 * MIN),
                ),
            ),
        )

        assertEquals(0L, review.totalMs)
        assertEquals(emptyList(), review.topApps)
    }

    @Test
    fun thePeakIsTheBusiestTwoHourBand() {
        val review = builder.build(
            input(hourlyMs = profile(14 to 2 * HOUR, 15 to 3 * HOUR, 16 to HOUR)),
        )

        assertEquals(14, review.peakStartHour)
    }

    @Test
    fun thePeakBandWrapsAcrossMidnight() {
        val review = builder.build(input(hourlyMs = profile(23 to 3 * HOUR, 0 to 2 * HOUR)))

        assertEquals(23, review.peakStartHour)
    }

    @Test
    fun thereIsNoPeakWithoutUsage() {
        val review = builder.build(input(hourlyMs = LongArray(HOURS_PER_DAY)))

        assertNull(review.peakStartHour)
    }

    @Test
    fun thereIsNoPeakForAProfileOfUnknownShape() {
        val oddProfile = LongArray(HOURS_PER_DAY + 1) { HOUR }

        assertNull(builder.build(input(hourlyMs = oddProfile)).peakStartHour)
    }

    @Test
    fun aWeekOfHourlySlotsIsFoldedByHourOfDay() {
        val weekly = LongArray(HOURS_PER_DAY * 7)
        for (slot in listOf(5, 5 + HOURS_PER_DAY, 5 + 2 * HOURS_PER_DAY)) weekly[slot] = HOUR

        assertEquals(5, builder.build(input(hourlyMs = weekly)).peakStartHour)
    }

    // ── choices and reflections ─────────────────────────────────────────────────────────────────────

    @Test
    fun starsLeavesQuietNightsAndFocusAreCarriedThrough() {
        val review = builder.build(
            input(
                stars = listOf(star(WEEK_START), star(WEEK_START + 3), star(WEEK_END), star(WEEK_START - 1)),
                opens = listOf(open(WEEK_START, OpenOutcome.LEFT)),
                quietNights = 4,
                focusMinutes = 75,
            ),
        )

        assertEquals(3, review.stars)
        assertEquals(1, review.leaves)
        assertEquals(4, review.quietNights)
        assertEquals(75, review.focusMinutes)
    }

    @Test
    fun onlyLeavingOutcomesCountAsLeaves() {
        val allOutcomes = OpenOutcome.entries.map { outcome -> open(WEEK_START, outcome) }
        val stayed = listOf(
            OpenOutcome.STAYED,
            OpenOutcome.RESUMED,
            OpenOutcome.FREE_SCROLL,
            OpenOutcome.QUICK_NEED,
            OpenOutcome.FOCUS_STARTED,
            OpenOutcome.WIND_DOWN,
            OpenOutcome.DISMISSED,
        ).map { outcome -> open(WEEK_START, outcome) }

        assertEquals(3, builder.build(input(opens = allOutcomes)).leaves)
        assertEquals(0, builder.build(input(opens = stayed)).leaves)
    }

    @Test
    fun leavesOutsideTheWeekAreNotCounted() {
        val review = builder.build(
            input(
                opens = listOf(
                    open(WEEK_START, OpenOutcome.LEFT),
                    open(WEEK_START - 1, OpenOutcome.LEFT),
                    open(WEEK_END + 1, OpenOutcome.LEFT_GESTURE),
                ),
            ),
        )

        assertEquals(1, review.leaves)
    }

    // ── open anyway (§A.7) ──────────────────────────────────────────────────────────────────────────

    @Test
    fun Review_counts_overrides() {
        val review = builder.build(
            input(
                opens = listOf(
                    open(WEEK_START, OpenOutcome.OVERRIDE, SurfaceKind.COOLDOWN),
                    open(WEEK_START + 2, OpenOutcome.OVERRIDE, SurfaceKind.SLEEP_GATE),
                    open(WEEK_END, OpenOutcome.OVERRIDE, SurfaceKind.TIME_UP),
                    // A leave is not an override, and neither is one outside the week.
                    open(WEEK_START, OpenOutcome.LEFT),
                    open(WEEK_START - 1, OpenOutcome.OVERRIDE, SurfaceKind.COOLDOWN),
                ),
            ),
        )

        assertEquals(3, review.overrides, "every OVERRIDE open in the week is counted, on any surface")
        assertEquals(1, review.leaves, "an override is not a leave")
    }

    @Test
    fun theTopReasonIsTheMostFrequent() {
        val review = builder.build(
            input(
                reflections = listOf(
                    reflection(WEEK_START, ReflectionReason.BORED),
                    reflection(WEEK_START + 1, ReflectionReason.AVOIDING),
                    reflection(WEEK_START + 2, ReflectionReason.AVOIDING),
                ),
            ),
        )

        assertEquals(ReflectionReason.AVOIDING, review.topReason)
    }

    @Test
    fun reasonTiesAreStable() {
        val reflections = listOf(
            reflection(WEEK_START, ReflectionReason.TIRED),
            reflection(WEEK_START + 1, ReflectionReason.BORED),
        )
        val first = builder.build(input(reflections = reflections))
        val second = builder.build(input(reflections = reflections))

        // The tie falls to the enum's order, which is arbitrary but never changes between two openings.
        assertEquals(ReflectionReason.BORED, first.topReason)
        assertEquals(first.topReason, second.topReason)
    }

    @Test
    fun thereIsNoReasonWithoutReflections() {
        assertNull(builder.build(input()).topReason)
    }

    @Test
    fun theApproximateFlagIsCarriedThrough() {
        assertEquals(true, builder.build(input(approximate = true)).approximate)
        assertEquals(false, builder.build(input(approximate = false)).approximate)
    }

    // ── suggestion rules ────────────────────────────────────────────────────────────────────────────

    @Test
    fun aPeakJustBeforeBedMovesTheSleepGateThirtyMinutesEarlier() {
        val review = builder.build(input(hourlyMs = profile(22 to 2 * HOUR)))

        assertEquals(WeeklySuggestion.MoveSleepGate(1320), review.suggestion)
    }

    @Test
    fun aBandThatOverlapsTheHourBeforeTheGateAlsoCounts() {
        // 21:00–23:00 covers 21:30–22:30, so part of the peak is the hour before the 22:30 gate.
        val review = builder.build(input(hourlyMs = profile(21 to 3 * HOUR)))

        assertEquals(WeeklySuggestion.MoveSleepGate(1320), review.suggestion)
    }

    @Test
    fun aBandThatEndsBeforeTheLastHourDoesNotMoveTheGate() {
        // 19:00–21:00 stops half an hour before the pre-gate hour starts, so the peak is not bedtime use.
        val review = builder.build(input(hourlyMs = profile(19 to 3 * HOUR)))

        assertNull(review.suggestion)
    }

    @Test
    fun aPeakAfterTheGateDoesNotMoveIt() {
        val review = builder.build(input(hourlyMs = profile(23 to 3 * HOUR)))

        assertNull(review.suggestion)
    }

    @Test
    fun theMoveUsesTheConfiguredGateStart() {
        val review = builder.build(
            input(
                hourlyMs = profile(19 to 2 * HOUR),
                config = config(sleepStartMinute = 20 * 60),
            ),
        )

        assertEquals(WeeklySuggestion.MoveSleepGate(19 * 60 + 30), review.suggestion)
    }

    @Test
    fun aDisabledGateIsNotMoved() {
        val review = builder.build(
            input(hourlyMs = profile(22 to 2 * HOUR), config = config(sleepEnabled = false)),
        )

        assertNull(review.suggestion)
    }

    @Test
    fun gentleWithARiseOverTwentyPercentSwitchesToBalanced() {
        val review = builder.build(
            input(
                usage = listOf(row(WEEK_START, WORK_APP, 3 * HOUR)),
                prevUsage = listOf(row(WEEK_START - 7, WORK_APP, 2 * HOUR)),
                config = config(mode = Mode.GENTLE),
            ),
        )

        assertEquals(WeeklySuggestion.SwitchMode(Mode.BALANCED), review.suggestion)
    }

    @Test
    fun exactlyTwentyPercentIsNotEnough() {
        val atTwenty = builder.build(
            input(
                usage = listOf(row(WEEK_START, WORK_APP, 2 * HOUR + 24 * MIN)),
                prevUsage = listOf(row(WEEK_START - 7, WORK_APP, 2 * HOUR)),
                config = config(mode = Mode.GENTLE),
            ),
        )
        val justOver = builder.build(
            input(
                usage = listOf(row(WEEK_START, WORK_APP, 2 * HOUR + 24 * MIN + 1)),
                prevUsage = listOf(row(WEEK_START - 7, WORK_APP, 2 * HOUR)),
                config = config(mode = Mode.GENTLE),
            ),
        )

        assertNull(atTwenty.suggestion)
        assertEquals(WeeklySuggestion.SwitchMode(Mode.BALANCED), justOver.suggestion)
    }

    @Test
    fun aModeSwitchNeedsGentle() {
        val review = builder.build(
            input(
                usage = listOf(row(WEEK_START, WORK_APP, 3 * HOUR)),
                prevUsage = listOf(row(WEEK_START - 7, WORK_APP, HOUR)),
                config = config(mode = Mode.BALANCED),
            ),
        )

        assertNull(review.suggestion)
    }

    @Test
    fun thereIsNoModeSwitchWithoutAPreviousWeek() {
        val review = builder.build(
            input(usage = listOf(row(WEEK_START, WORK_APP, 3 * HOUR)), config = config(mode = Mode.GENTLE)),
        )

        assertNull(review.suggestion)
    }

    @Test
    fun thereIsNoModeSwitchWhenThePreviousWeekHadNoTime() {
        val review = builder.build(
            input(
                usage = listOf(row(WEEK_START, WORK_APP, 3 * HOUR)),
                prevUsage = listOf(row(WEEK_START - 7, WORK_APP, 0L)),
                config = config(mode = Mode.GENTLE),
            ),
        )

        assertNull(review.suggestion)
    }

    @Test
    fun Review_rest_overrides_in_firm_suggest_steady() {
        val restOverrides = List(4) { day -> open(WEEK_START + day, OpenOutcome.OVERRIDE, SurfaceKind.COOLDOWN) }

        val review = builder.build(input(opens = restOverrides, config = config(mode = Mode.HARDCORE)))

        assertEquals(4, review.overrides)
        assertEquals(
            WeeklySuggestion.SwitchMode(Mode.BALANCED),
            review.suggestion,
            "four trips through the rest wall in Firm mean the wall is the thing to loosen",
        )

        // The gate rule keeps its slot ahead of it: the sleep gate is still the first thing to try.
        val withPeak = builder.build(
            input(
                opens = restOverrides,
                hourlyMs = profile(22 to 2 * HOUR),
                config = config(mode = Mode.HARDCORE),
            ),
        )
        assertEquals(WeeklySuggestion.MoveSleepGate(1320), withPeak.suggestion)

        // Steady has nothing to loosen downwards, so the rule stays silent there.
        val steady = builder.build(input(opens = restOverrides, config = config(mode = Mode.BALANCED)))
        assertNull(steady.suggestion)
    }

    @Test
    fun Review_three_rest_overrides_suggest_nothing() {
        val review = builder.build(
            input(
                opens = List(3) { day -> open(WEEK_START + day, OpenOutcome.OVERRIDE, SurfaceKind.COOLDOWN) },
                config = config(mode = Mode.HARDCORE),
            ),
        )

        assertEquals(3, review.overrides, "three still show in the count")
        assertNull(review.suggestion, "three is a week, not a rule he keeps going around")
    }

    @Test
    fun Review_night_overrides_never_suggest_looser_mode() {
        // The 2 AM door and a Time's-up door are not the rest wall: he already gets "Sleep is the better
        // scroll" for the first, and loosening Firm because of either would give up the hour it exists for.
        val night = builder.build(
            input(
                opens = List(5) { day -> open(WEEK_START + day, OpenOutcome.OVERRIDE, SurfaceKind.SLEEP_GATE) },
                config = config(mode = Mode.HARDCORE),
            ),
        )
        val timeUp = builder.build(
            input(
                opens = List(5) { day -> open(WEEK_START + day, OpenOutcome.OVERRIDE, SurfaceKind.TIME_UP) },
                config = config(mode = Mode.HARDCORE),
            ),
        )

        assertEquals(5, night.overrides, "the night door is still counted")
        assertNull(night.suggestion, "a night override never loosens the mode")
        assertNull(timeUp.suggestion, "only COOLDOWN overrides count toward the rest rule")
    }

    @Test
    fun anUnguardedFeedOverTwoHoursIsNamed() {
        val review = builder.build(
            input(
                usage = listOf(row(WEEK_START, INSTAGRAM, 3 * HOUR)),
                hourlyMs = profile(12 to 2 * HOUR),
            ),
        )

        assertEquals(WeeklySuggestion.GuardApp(INSTAGRAM), review.suggestion)
    }

    @Test
    fun theLargestFeedIsNamed() {
        val review = builder.build(
            input(
                usage = listOf(
                    row(WEEK_START, INSTAGRAM, 3 * HOUR),
                    row(WEEK_START + 1, YOUTUBE, 4 * HOUR),
                ),
                hourlyMs = profile(12 to 2 * HOUR),
            ),
        )

        assertEquals(WeeklySuggestion.GuardApp(YOUTUBE), review.suggestion)
    }

    @Test
    fun theWeekIsSummedBeforeTheGuardThresholdIsApplied() {
        // Three one-hour days are three hours of one app, not three separate misses.
        val review = builder.build(
            input(
                usage = listOf(
                    row(WEEK_START, INSTAGRAM, HOUR),
                    row(WEEK_START + 1, INSTAGRAM, HOUR),
                    row(WEEK_START + 2, INSTAGRAM, HOUR),
                ),
                hourlyMs = profile(12 to 2 * HOUR),
            ),
        )

        assertEquals(WeeklySuggestion.GuardApp(INSTAGRAM), review.suggestion)
    }

    @Test
    fun twoHoursExactlyIsNotEnough() {
        val atTwoHours = builder.build(
            input(
                usage = listOf(row(WEEK_START, INSTAGRAM, 2 * HOUR)),
                hourlyMs = profile(12 to 2 * HOUR),
            ),
        )
        val justOver = builder.build(
            input(
                usage = listOf(row(WEEK_START, INSTAGRAM, 2 * HOUR + 1)),
                hourlyMs = profile(12 to 2 * HOUR),
            ),
        )

        assertNull(atTwoHours.suggestion)
        assertEquals(WeeklySuggestion.GuardApp(INSTAGRAM), justOver.suggestion)
    }

    @Test
    fun aGuardedAppIsNotSuggestedAgain() {
        val review = builder.build(
            input(
                usage = listOf(row(WEEK_START, INSTAGRAM, 3 * HOUR)),
                hourlyMs = profile(12 to 2 * HOUR),
                config = config(apps = listOf(GuardedApp(INSTAGRAM, "Instagram"))),
            ),
        )

        assertNull(review.suggestion)
    }

    @Test
    fun anAppOutsideTheCatalogIsNotNamed() {
        val review = builder.build(
            input(
                usage = listOf(row(WEEK_START, "com.example.feedapp", 5 * HOUR)),
                hourlyMs = profile(12 to 2 * HOUR),
            ),
        )

        assertNull(review.suggestion)
    }

    @Test
    fun anEmptyFeedCatalogDisablesTheGuardSuggestion() {
        val withoutCatalog = DefaultWeeklyReviewBuilder(knownFeeds = emptySet())

        val review = withoutCatalog.build(
            input(
                usage = listOf(row(WEEK_START, INSTAGRAM, 5 * HOUR)),
                hourlyMs = profile(12 to 2 * HOUR),
            ),
        )

        assertNull(review.suggestion)
    }

    @Test
    fun theSuggestionOrderIsGateThenModeThenApp() {
        val usage = listOf(row(WEEK_START, INSTAGRAM, 3 * HOUR))
        val previous = listOf(row(WEEK_START - 7, INSTAGRAM, 2 * HOUR))

        val gateFirst = builder.build(
            input(
                usage = usage,
                prevUsage = previous,
                hourlyMs = profile(22 to 2 * HOUR),
                config = config(mode = Mode.GENTLE),
            ),
        )
        val modeSecond = builder.build(
            input(
                usage = usage,
                prevUsage = previous,
                hourlyMs = profile(12 to 2 * HOUR),
                config = config(mode = Mode.GENTLE),
            ),
        )
        val appThird = builder.build(
            input(usage = usage, hourlyMs = profile(12 to 2 * HOUR), config = config(mode = Mode.BALANCED)),
        )

        assertEquals(WeeklySuggestion.MoveSleepGate(1320), gateFirst.suggestion)
        assertEquals(WeeklySuggestion.SwitchMode(Mode.BALANCED), modeSecond.suggestion)
        assertEquals(WeeklySuggestion.GuardApp(INSTAGRAM), appThird.suggestion)
    }

    @Test
    fun aQuietWeekHasNoSuggestion() {
        val review = builder.build(
            input(hourlyMs = profile(14 to HOUR), usage = listOf(row(WEEK_START, WORK_APP, HOUR))),
        )

        assertNull(review.suggestion)
    }

    @Test
    fun theSameInputProducesTheSameReview() {
        val input = input(
            usage = listOf(
                row(WEEK_START, INSTAGRAM, 3 * HOUR),
                row(WEEK_START, YOUTUBE, 2 * HOUR),
            ),
            prevUsage = listOf(row(WEEK_START - 7, INSTAGRAM, HOUR)),
            stars = listOf(star(WEEK_START), star(WEEK_START + 1)),
            opens = listOf(open(WEEK_START, OpenOutcome.LEFT)),
            reflections = listOf(reflection(WEEK_START, ReflectionReason.HABIT)),
            focusMinutes = 50,
            quietNights = 2,
            hourlyMs = profile(14 to 2 * HOUR, 15 to 3 * HOUR),
            config = config(mode = Mode.GENTLE),
            approximate = true,
        )

        assertEquals(builder.build(input), builder.build(input))
    }

    // ── fixtures ───────────────────────────────────────────────────────────────────────────────────

    private fun input(
        usage: List<UsageDayRow> = emptyList(),
        prevUsage: List<UsageDayRow> = emptyList(),
        stars: List<StarAward> = emptyList(),
        opens: List<RecordEvent.Open> = emptyList(),
        reflections: List<RecordEvent.Reflected> = emptyList(),
        focusMinutes: Int = 0,
        quietNights: Int = 0,
        hourlyMs: LongArray = LongArray(HOURS_PER_DAY),
        config: GuardConfig = GuardConfig(),
        approximate: Boolean = false,
    ): WeeklyInput = WeeklyInput(
        days = WEEK,
        usage = usage,
        prevUsage = prevUsage,
        stars = stars,
        opens = opens,
        reflections = reflections,
        focusMinutes = focusMinutes,
        quietNights = quietNights,
        hourlyMs = hourlyMs,
        config = config,
        approximate = approximate,
    )

    private fun row(logicalDay: Int, packageName: String, foregroundMs: Long): UsageDayRow =
        UsageDayRow(logicalDay = logicalDay, packageName = packageName, foregroundMs = foregroundMs, opens = 1, nightMs = 0L)

    private fun open(
        logicalDay: Int,
        outcome: OpenOutcome,
        surface: SurfaceKind = SurfaceKind.PAUSE,
    ): RecordEvent.Open = RecordEvent.Open(
        at = NOW,
        logicalDay = logicalDay,
        packageName = INSTAGRAM,
        domain = null,
        surface = surface,
        outcome = outcome,
        decisionMs = null,
        grantId = null,
    )

    private fun reflection(logicalDay: Int, reason: ReflectionReason): RecordEvent.Reflected = RecordEvent.Reflected(
        at = NOW,
        logicalDay = logicalDay,
        reason = reason,
        packageName = INSTAGRAM,
        opensInWindow = 6,
    )

    private fun star(logicalDay: Int): StarAward = StarAward(
        kind = StarKind.LEAVE,
        litWall = NOW,
        logicalDay = logicalDay,
        window = WindowKind.NONE,
        sourcePackage = null,
        sourceRef = null,
    )

    private fun config(
        mode: Mode = Mode.BALANCED,
        apps: List<GuardedApp> = emptyList(),
        sleepEnabled: Boolean = true,
        sleepStartMinute: Int = 1350,
    ): GuardConfig = GuardConfig(
        mode = mode,
        apps = apps,
        sleepGate = SleepGateConfig(enabled = sleepEnabled, startMinuteOfDay = sleepStartMinute),
    )

    /** A 24-slot hour-of-day profile with [slots] filled in. */
    private fun profile(vararg slots: Pair<Int, Long>): LongArray {
        val hours = LongArray(HOURS_PER_DAY)
        for ((hour, ms) in slots) hours[hour] = ms
        return hours
    }

    private companion object {
        const val NOW: Long = 1_790_000_000_000L
        const val MIN: Long = 60_000L
        const val HOUR: Long = 60 * MIN
        const val HOURS_PER_DAY: Int = 24

        const val WEEK_START: Int = 20_000
        const val WEEK_END: Int = WEEK_START + 6
        val WEEK: IntRange = WEEK_START..WEEK_END

        const val INSTAGRAM: String = "com.instagram.android"
        const val YOUTUBE: String = "com.google.android.youtube"
        const val FACEBOOK: String = "com.facebook.katana"
        const val TWITTER: String = "com.twitter.android"
        const val REDDIT: String = "com.reddit.frontpage"

        /** Not a feed, so only the total and the delta can react to it. */
        const val WORK_APP: String = "com.example.work"
    }
}
