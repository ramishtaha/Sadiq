package com.ramish.sadiq.core.insight

import com.ramish.sadiq.core.model.ActiveWindow
import com.ramish.sadiq.core.model.PrayerName
import com.ramish.sadiq.core.model.WindowKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The §4.4 first-match order, branch by branch.
 *
 * Each test builds the smallest context that can reach its branch, so a failure names the rule that broke
 * rather than "the suggester changed".
 */
class SuggesterTest {

    private val suggester: NextActionSuggester = DefaultNextActionSuggester()

    @Test
    fun aQuietContextReturnsTheRotationLine() {
        val suggestion = suggester.suggest(context())

        // day 20 000 + open 0 is slot 2 of the rotation; the exact arithmetic is covered below.
        assertEquals(SuggestionKey.GENERIC_BREATHE, suggestion.key)
        assertEquals(SuggestionAction.NONE, suggestion.action)
    }

    @Test
    fun aPrayerWindowNamesThePrayer() {
        val suggestion = suggester.suggest(
            context(
                window = ActiveWindow(
                    kind = WindowKind.PRAYER,
                    startWall = NOW - MIN,
                    endWall = NOW + 19 * MIN,
                    prayer = PrayerName.ASR,
                ),
            ),
        )

        assertEquals(SuggestionKey.PRAYER_NOW, suggestion.key)
        assertEquals(PrayerName.ASR, suggestion.prayer)
        assertEquals(SuggestionAction.NONE, suggestion.action)
    }

    @Test
    fun aPrayerWindowWithoutANameFallsThroughToTheNextAdhan() {
        // A window with no prayer name cannot be put on screen as "It's {}. Wudu first?", so the next
        // branch is allowed to speak instead.
        val suggestion = suggester.suggest(
            context(
                window = ActiveWindow(kind = WindowKind.PRAYER, startWall = NOW - MIN, endWall = NOW + 19 * MIN),
                nextPrayer = PrayerName.DHUHR,
                nextPrayerWall = NOW + 12 * MIN,
            ),
        )

        assertEquals(SuggestionKey.PRAYER_SOON, suggestion.key)
        assertEquals(PrayerName.DHUHR, suggestion.prayer)
    }

    @Test
    fun anAdhanWithinFifteenMinutesIsNamedWithItsMinutes() {
        val suggestion = suggester.suggest(
            context(nextPrayer = PrayerName.MAGHRIB, nextPrayerWall = NOW + 12 * MIN),
        )

        assertEquals(SuggestionKey.PRAYER_SOON, suggestion.key)
        assertEquals(PrayerName.MAGHRIB, suggestion.prayer)
        assertEquals(12, suggestion.minutes)
        assertEquals(NOW + 12 * MIN, suggestion.atWall)
    }

    @Test
    fun anAdhanExactlyFifteenMinutesAwayStillCounts() {
        val suggestion = suggester.suggest(
            context(nextPrayer = PrayerName.MAGHRIB, nextPrayerWall = NOW + 15 * MIN),
        )

        assertEquals(SuggestionKey.PRAYER_SOON, suggestion.key)
        assertEquals(15, suggestion.minutes)
    }

    @Test
    fun anAdhanSixteenMinutesAwayFallsThroughToTheRotation() {
        val suggestion = suggester.suggest(
            context(nextPrayer = PrayerName.MAGHRIB, nextPrayerWall = NOW + 16 * MIN),
        )

        assertEquals(SuggestionKey.GENERIC_BREATHE, suggestion.key)
    }

    @Test
    fun partialMinutesAreRoundedUp() {
        val suggestion = suggester.suggest(
            context(nextPrayer = PrayerName.MAGHRIB, nextPrayerWall = NOW + 11 * MIN + 30_000L),
        )

        assertEquals(SuggestionKey.PRAYER_SOON, suggestion.key)
        assertEquals(12, suggestion.minutes)
    }

    @Test
    fun anAdhanThatJustPassedIsNotAnnouncedAsUpcoming() {
        val atTheAdhan = suggester.suggest(context(nextPrayer = PrayerName.ASR, nextPrayerWall = NOW))
        val afterTheAdhan = suggester.suggest(context(nextPrayer = PrayerName.ASR, nextPrayerWall = NOW - MIN))
        val withoutATime = suggester.suggest(context(nextPrayer = PrayerName.ASR, nextPrayerWall = null))

        // "Asr in 0 minutes" would be invented urgency; the prayer-window branch owns that moment.
        assertEquals(SuggestionKey.GENERIC_BREATHE, atTheAdhan.key)
        assertEquals(SuggestionKey.GENERIC_BREATHE, afterTheAdhan.key)
        assertEquals(SuggestionKey.GENERIC_BREATHE, withoutATime.key)
        assertNull(atTheAdhan.minutes)
    }

    @Test
    fun theSleepGateNamesFajr() {
        val suggestion = suggester.suggest(
            context(
                window = ActiveWindow(kind = WindowKind.SLEEP_GATE, startWall = NOW - 2 * HOUR, endWall = NOW + 6 * HOUR),
                nextFajrWall = NOW + 6 * HOUR,
            ),
        )

        assertEquals(SuggestionKey.SLEEP_FAJR, suggestion.key)
        assertEquals(NOW + 6 * HOUR, suggestion.atWall)
        assertEquals(360, suggestion.minutes)
    }

    @Test
    fun theSleepGateFallsBackToTheWindowEnd() {
        // §3.7 defines the gate as ending at Fajr, so the window end is the same instant when the
        // schedule did not answer (0 is not a Fajr).
        val endWall = NOW + 5 * HOUR
        val suggestion = suggester.suggest(
            context(
                window = ActiveWindow(kind = WindowKind.SLEEP_GATE, startWall = NOW - 3 * HOUR, endWall = endWall),
                nextFajrWall = 0L,
            ),
        )

        assertEquals(SuggestionKey.SLEEP_FAJR, suggestion.key)
        assertEquals(endWall, suggestion.atWall)
        assertEquals(300, suggestion.minutes)
    }

    @Test
    fun theSleepGateInventsNoTimeWhenFajrIsUnknown() {
        val suggestion = suggester.suggest(
            context(
                window = ActiveWindow(kind = WindowKind.SLEEP_GATE, startWall = NOW - 3 * HOUR, endWall = null),
                nextFajrWall = 0L,
            ),
        )

        assertEquals(SuggestionKey.SLEEP_FAJR, suggestion.key)
        assertNull(suggestion.atWall)
        assertNull(suggestion.minutes)
    }

    @Test
    fun theSleepGateNeverNamesAPastFajr() {
        val suggestion = suggester.suggest(
            context(
                window = ActiveWindow(kind = WindowKind.SLEEP_GATE, startWall = NOW - 8 * HOUR, endWall = NOW - HOUR),
                nextFajrWall = NOW - HOUR,
            ),
        )

        assertEquals(SuggestionKey.SLEEP_FAJR, suggestion.key)
        assertNull(suggestion.atWall)
    }

    @Test
    fun theMorningOffersAFiveMinuteStart() {
        val suggestion = suggester.suggest(
            context(
                window = ActiveWindow(kind = WindowKind.MORNING, startWall = NOW - HOUR, endWall = NOW + 2 * HOUR),
            ),
        )

        assertEquals(SuggestionKey.MORNING_ONE_THING, suggestion.key)
        assertEquals(5, suggestion.minutes)
        assertEquals(SuggestionAction.START_FOCUS_5, suggestion.action)
    }

    @Test
    fun theGymHintStartsThirtyMinutesAfterMaghrib() {
        val atThirty = suggester.suggest(context(maghribWall = NOW - 30 * MIN))
        val atTwentyNine = suggester.suggest(context(maghribWall = NOW - 29 * MIN))

        assertEquals(SuggestionKey.GYM_SOON, atThirty.key)
        assertEquals(SuggestionKey.GENERIC_BREATHE, atTwentyNine.key)
    }

    @Test
    fun theGymHintBecomesGymHourAtSixtyMinutes() {
        val atFiftyNine = suggester.suggest(context(maghribWall = NOW - 59 * MIN - 59_000L))
        val atSixty = suggester.suggest(context(maghribWall = NOW - 60 * MIN))

        assertEquals(SuggestionKey.GYM_SOON, atFiftyNine.key)
        assertEquals(SuggestionKey.GYM_NOW, atSixty.key)
    }

    @Test
    fun theGymHintEndsAfterTwoHours() {
        val atOneNineteen = suggester.suggest(context(maghribWall = NOW - 119 * MIN))
        val atTwoHours = suggester.suggest(context(maghribWall = NOW - 120 * MIN))

        assertEquals(SuggestionKey.GYM_NOW, atOneNineteen.key)
        assertEquals(SuggestionKey.GENERIC_BREATHE, atTwoHours.key)
    }

    @Test
    fun theGymLinesCarryNoInventedCountdown() {
        val suggestion = suggester.suggest(context(maghribWall = NOW - 45 * MIN))

        assertEquals(SuggestionKey.GYM_SOON, suggestion.key)
        assertNull(suggestion.minutes)
        assertNull(suggestion.atWall)
    }

    @Test
    fun turningGymHintsOffSilencesBothGymLines() {
        val soon = suggester.suggest(context(maghribWall = NOW - 45 * MIN, gymHintEnabled = false))
        val hour = suggester.suggest(context(maghribWall = NOW - 90 * MIN, gymHintEnabled = false))

        assertEquals(SuggestionKey.GENERIC_BREATHE, soon.key)
        assertEquals(SuggestionKey.GENERIC_BREATHE, hour.key)
    }

    @Test
    fun beforeMaghribThereIsNoGymLine() {
        val suggestion = suggester.suggest(context(maghribWall = NOW + HOUR))

        assertEquals(SuggestionKey.GENERIC_BREATHE, suggestion.key)
    }

    @Test
    fun anUnknownMaghribSkipsTheGymLines() {
        val suggestion = suggester.suggest(context(maghribWall = 0L))

        assertEquals(SuggestionKey.GENERIC_BREATHE, suggestion.key)
    }

    @Test
    fun theGymHintYieldsToTheMorningAndTheGate() {
        val morning = suggester.suggest(
            context(
                window = ActiveWindow(kind = WindowKind.MORNING, startWall = NOW - HOUR, endWall = NOW + 2 * HOUR),
                maghribWall = NOW - 45 * MIN,
            ),
        )
        val gate = suggester.suggest(
            context(
                window = ActiveWindow(kind = WindowKind.SLEEP_GATE, startWall = NOW - HOUR, endWall = NOW + 6 * HOUR),
                maghribWall = NOW - 45 * MIN,
            ),
        )

        assertEquals(SuggestionKey.MORNING_ONE_THING, morning.key)
        assertEquals(SuggestionKey.SLEEP_FAJR, gate.key)
    }

    @Test
    fun theComingAdhanYieldsToTheOpenPrayerWindow() {
        val suggestion = suggester.suggest(
            context(
                window = ActiveWindow(
                    kind = WindowKind.PRAYER,
                    startWall = NOW - MIN,
                    endWall = NOW + 19 * MIN,
                    prayer = PrayerName.MAGHRIB,
                ),
                nextPrayer = PrayerName.ISHA,
                nextPrayerWall = NOW + 10 * MIN,
                maghribWall = NOW - 45 * MIN,
            ),
        )

        assertEquals(SuggestionKey.PRAYER_NOW, suggestion.key)
        assertEquals(PrayerName.MAGHRIB, suggestion.prayer)
    }

    @Test
    fun theComingAdhanYieldsToTheGymHint() {
        val suggestion = suggester.suggest(
            context(
                nextPrayer = PrayerName.ISHA,
                nextPrayerWall = NOW + 10 * MIN,
                maghribWall = NOW - 45 * MIN,
            ),
        )

        assertEquals(SuggestionKey.PRAYER_SOON, suggestion.key)
    }

    @Test
    fun theRotationIsDeterministicForDayAndIndex() {
        val first = suggester.suggest(context(logicalDay = ROTATION_DAY, openIndexToday = 3))
        val second = suggester.suggest(context(logicalDay = ROTATION_DAY, openIndexToday = 3))

        assertEquals(first, second)
        assertEquals(SuggestionKey.GENERIC_ONE_THING, first.key)
    }

    @Test
    fun theRotationWalksAllSixLinesInOrder() {
        val keys = (0 until 6).map { index ->
            suggester.suggest(context(logicalDay = ROTATION_DAY, openIndexToday = index)).key
        }

        assertEquals(
            listOf(
                SuggestionKey.GENERIC_WATER,
                SuggestionKey.GENERIC_WINDOW,
                SuggestionKey.GENERIC_BREATHE,
                SuggestionKey.GENERIC_ONE_THING,
                SuggestionKey.GENERIC_TEXT_BACK,
                SuggestionKey.GENERIC_STRETCH,
            ),
            keys,
        )
    }

    @Test
    fun theRotationDoesNotOfferAnActionThePauseSurfaceCannotTake() {
        // "Pick one thing. 5 minutes." is on the generic list, but only MORNING and REFLECT accept a
        // focus start (T13), so the generic line must not carry the action.
        val suggestion = suggester.suggest(context(logicalDay = ROTATION_DAY, openIndexToday = 3))

        assertEquals(SuggestionKey.GENERIC_ONE_THING, suggestion.key)
        assertEquals(SuggestionAction.NONE, suggestion.action)
    }

    @Test
    fun theRotationMovesWithTheDay() {
        val day = suggester.suggest(context(logicalDay = ROTATION_DAY, openIndexToday = 3))
        val nextDay = suggester.suggest(context(logicalDay = ROTATION_DAY + 1, openIndexToday = 3))

        assertEquals(SuggestionKey.GENERIC_ONE_THING, day.key)
        assertEquals(SuggestionKey.GENERIC_TEXT_BACK, nextDay.key)
    }

    @Test
    fun theRotationSurvivesANegativeSlot() {
        val suggestion = suggester.suggest(context(logicalDay = -1, openIndexToday = 0))

        // floorMod(-1, 6) is 5, the last line.
        assertEquals(SuggestionKey.GENERIC_STRETCH, suggestion.key)
    }

    @Test
    fun everyReasonHasItsOwnLine() {
        val expected = mapOf(
            ReflectionReason.BORED to SuggestionKey.REFLECT_BORED,
            ReflectionReason.TIRED to SuggestionKey.REFLECT_TIRED,
            ReflectionReason.AVOIDING to SuggestionKey.REFLECT_AVOIDING,
            ReflectionReason.LONELY to SuggestionKey.REFLECT_LONELY,
            ReflectionReason.ANXIOUS to SuggestionKey.REFLECT_ANXIOUS,
            ReflectionReason.HABIT to SuggestionKey.REFLECT_HABIT,
        )

        val keys = expected.map { (reason, key) ->
            val suggestion = suggester.suggest(context(reason = reason))
            assertEquals(key, suggestion.key, "reason $reason")
            suggestion.key
        }

        assertEquals(expected.size, keys.toSet().size, "each reason maps to its own line")
    }

    @Test
    fun aStatedReasonBeatsTheWindowLines() {
        // He answered the card; the answer is the line, even if a window opened underneath it.
        val suggestion = suggester.suggest(
            context(
                window = ActiveWindow(
                    kind = WindowKind.PRAYER,
                    startWall = NOW - MIN,
                    endWall = NOW + 19 * MIN,
                    prayer = PrayerName.ASR,
                ),
                nextPrayer = PrayerName.MAGHRIB,
                nextPrayerWall = NOW + 10 * MIN,
                reason = ReflectionReason.TIRED,
            ),
        )

        assertEquals(SuggestionKey.REFLECT_TIRED, suggestion.key)
    }

    @Test
    fun tiredInsideTheSleepGateOffersWindDown() {
        val suggestion = suggester.suggest(
            context(
                window = ActiveWindow(kind = WindowKind.SLEEP_GATE, startWall = NOW - HOUR, endWall = NOW + 6 * HOUR),
                reason = ReflectionReason.TIRED,
            ),
        )

        assertEquals(SuggestionKey.REFLECT_TIRED, suggestion.key)
        assertEquals(SuggestionAction.WIND_DOWN, suggestion.action)
    }

    @Test
    fun tiredDuringTheDayOffersNoAction() {
        val suggestion = suggester.suggest(context(reason = ReflectionReason.TIRED))

        assertEquals(SuggestionKey.REFLECT_TIRED, suggestion.key)
        assertEquals(SuggestionAction.NONE, suggestion.action)
    }

    @Test
    fun avoidingSomethingOffersAFiveMinuteStart() {
        val suggestion = suggester.suggest(context(reason = ReflectionReason.AVOIDING))

        assertEquals(SuggestionKey.REFLECT_AVOIDING, suggestion.key)
        assertEquals(SuggestionAction.START_FOCUS_5, suggestion.action)
    }

    @Test
    fun theOtherReasonsOfferNoAction() {
        val reasons = listOf(
            ReflectionReason.BORED,
            ReflectionReason.LONELY,
            ReflectionReason.ANXIOUS,
            ReflectionReason.HABIT,
        )

        for (reason in reasons) {
            val suggestion = suggester.suggest(context(reason = reason))
            assertEquals(SuggestionAction.NONE, suggestion.action, "reason $reason")
            assertNull(suggestion.minutes, "reason $reason")
        }
    }

    @Test
    fun Suggester_need_has_no_line() {
        // "I need it" is the override's own reason, and the confirm view already states the cost. The
        // suggester has no response line for it and offers no action, so the context answers exactly as it
        // would with no answer at all (OV-2).
        val need = suggester.suggest(context(reason = ReflectionReason.NEED))

        assertEquals(suggester.suggest(context(reason = null)), need, "NEED adds no line")
        assertEquals(SuggestionAction.NONE, need.action, "NEED offers no action")
        assertNull(need.minutes)

        // Even at night, where Tired would offer "Wind down": NEED is silence, not a nudge.
        val gate = ActiveWindow(kind = WindowKind.SLEEP_GATE, startWall = NOW - HOUR, endWall = NOW + 6 * HOUR)
        val needAtNight = suggester.suggest(
            context(window = gate, nextFajrWall = NOW + 6 * HOUR, reason = ReflectionReason.NEED),
        )

        assertEquals(SuggestionKey.SLEEP_FAJR, needAtNight.key)
        assertEquals(SuggestionAction.NONE, needAtNight.action)
        assertEquals(
            suggester.suggest(context(window = gate, nextFajrWall = NOW + 6 * HOUR, reason = null)),
            needAtNight,
        )
    }

    @Test
    fun everySuggestionIsStableAcrossRepeatedCalls() {
        val ctx = context(
            window = ActiveWindow(kind = WindowKind.SLEEP_GATE, startWall = NOW - HOUR, endWall = NOW + 6 * HOUR),
            nextFajrWall = NOW + 6 * HOUR,
            maghribWall = NOW - 45 * MIN,
        )

        assertEquals(suggester.suggest(ctx), suggester.suggest(ctx))
    }

    private fun context(
        window: ActiveWindow = ActiveWindow(kind = WindowKind.NONE, startWall = null, endWall = null),
        nowWall: Long = NOW,
        minuteOfDay: Int = 14 * 60,
        nextPrayer: PrayerName? = null,
        nextPrayerWall: Long? = null,
        maghribWall: Long = NOW - 3 * HOUR,
        nextFajrWall: Long = NOW + 8 * HOUR,
        reason: ReflectionReason? = null,
        gymHintEnabled: Boolean = true,
        openIndexToday: Int = 0,
        logicalDay: Int = DEFAULT_DAY,
    ): SuggestionContext = SuggestionContext(
        window = window,
        nowWall = nowWall,
        minuteOfDay = minuteOfDay,
        nextPrayer = nextPrayer,
        nextPrayerWall = nextPrayerWall,
        maghribWall = maghribWall,
        nextFajrWall = nextFajrWall,
        reason = reason,
        gymHintEnabled = gymHintEnabled,
        openIndexToday = openIndexToday,
        logicalDay = logicalDay,
    )

    private companion object {
        const val NOW: Long = 1_790_000_000_000L
        const val MIN: Long = 60_000L
        const val HOUR: Long = 60 * MIN

        /** 20 000 % 6 == 2, so the default context sits on GENERIC_BREATHE. */
        const val DEFAULT_DAY: Int = 20_000

        /** 20 004 % 6 == 0, so index 0 starts the rotation at its first line. */
        const val ROTATION_DAY: Int = 20_004
    }
}
