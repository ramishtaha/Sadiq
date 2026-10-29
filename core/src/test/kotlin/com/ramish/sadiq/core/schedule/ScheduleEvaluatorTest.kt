package com.ramish.sadiq.core.schedule

import com.ramish.sadiq.core.model.ActiveWindow
import com.ramish.sadiq.core.model.GuardConfig
import com.ramish.sadiq.core.model.GuardContext
import com.ramish.sadiq.core.model.MorningConfig
import com.ramish.sadiq.core.model.PrayerConfig
import com.ramish.sadiq.core.model.PrayerDay
import com.ramish.sadiq.core.model.PrayerName
import com.ramish.sadiq.core.model.ProtectedSpan
import com.ramish.sadiq.core.model.RamadanConfig
import com.ramish.sadiq.core.model.RamadanMode
import com.ramish.sadiq.core.model.SleepGateConfig
import com.ramish.sadiq.core.model.WindowKind
import com.ramish.sadiq.core.time.TimeSnapshot
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every rule of §3.7, one window at a time.
 *
 * Fixtures are Thane (19.2183 / 72.9781, Karachi method, Hanafi Asr) on 29 October 2026: Fajr 05:23,
 * Dhuhr 12:23, Asr 16:30, Maghrib 18:06, Isha 19:20, next Fajr 05:23. Those instants come from the
 * golden test, so a failure here is a schedule bug rather than an astronomy surprise.
 *
 * On priority: PRAYER and SLEEP_GATE are the only two windows that can overlap. The gate ends at
 * Fajr and the morning begins once the Fajr window has closed, and the gate starts no earlier than
 * Isha plus thirty minutes while the morning ends by Dhuhr — so SLEEP_GATE/MORNING overlap cannot be
 * built from any legal configuration. The order is still enforced by construction in the evaluator;
 * the reachable case is asserted directly below.
 */
class ScheduleEvaluatorTest {

    private val evaluator: ScheduleEvaluator = DefaultScheduleEvaluator()
    private val provider: PrayerTimesProvider = AdhanPrayerTimesProvider()
    private val zone: String = "Asia/Kolkata"

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────

    private fun wall(date: String, hour: Int, minute: Int, zoneId: String = zone): Long =
        LocalDate.parse(date)
            .atTime(hour, minute)
            .atZone(ZoneId.of(zoneId))
            .toInstant()
            .toEpochMilli()

    private fun epochDay(date: String): Int = LocalDate.parse(date).toEpochDay().toInt()

    private fun snapshot(wallMs: Long): TimeSnapshot =
        TimeSnapshot(wallMs = wallMs, elapsedMs = 0L, bootCount = 1, zoneId = zone)

    private fun dayOf(date: String): PrayerDay =
        provider.dayFor(epochDay(date), zone, PrayerConfig())

    private fun windowAt(wallMs: Long, config: GuardConfig = GuardConfig()): ActiveWindow =
        evaluator.contextAt(snapshot(wallMs), config, 0L).window

    private fun contextAt(
        wallMs: Long,
        config: GuardConfig = GuardConfig(),
        lastSeenWall: Long = 0L
    ): GuardContext = evaluator.contextAt(snapshot(wallMs), config, lastSeenWall)

    private fun ended(
        fromWall: Long,
        toWall: Long,
        config: GuardConfig = GuardConfig()
    ): List<ProtectedSpan> =
        evaluator.endedProtectedWindows(fromWall, toWall, zone, config)

    // ── Prayer windows ──────────────────────────────────────────────────────────────────────────

    @Test
    fun everyPrayerWindowStartsInclusiveAndEndsExclusive() {
        val day = dayOf("2026-10-29")
        val windows = listOf(
            PrayerName.FAJR to day.fajr,
            PrayerName.DHUHR to day.dhuhr,
            PrayerName.ASR to day.asr,
            PrayerName.MAGHRIB to day.maghrib,
            PrayerName.ISHA to day.isha
        )

        for ((name, adhan) in windows) {
            val atAdhan = windowAt(adhan)
            assertEquals(WindowKind.PRAYER, atAdhan.kind, "$name at its adhan is a prayer window")
            assertEquals(name, atAdhan.prayer, "$name owns its window")
            assertEquals(adhan, atAdhan.startWall, "$name window starts at the adhan")
            assertEquals(adhan + 20 * 60_000L, atAdhan.endWall, "$name window is 20 minutes")

            assertEquals(
                name,
                windowAt(adhan + 20 * 60_000L - 1).prayer,
                "$name is still active one millisecond before its end"
            )
            assertNotEquals(
                name,
                windowAt(adhan + 20 * 60_000L).prayer,
                "$name must be over at adhan + window"
            )
        }
    }

    @Test
    fun theFajrWindowHandsOverToTheMorningAtItsEnd() {
        val day = dayOf("2026-10-29")
        val afterFajr = windowAt(day.fajr + 20 * 60_000L)

        assertEquals(WindowKind.MORNING, afterFajr.kind)
        assertEquals(day.fajr + 20 * 60_000L, afterFajr.startWall)
    }

    @Test
    fun jumuahReplacesDhuhrOnFridayOnly() {
        val friday = dayOf("2026-10-30")
        val thursday = dayOf("2026-10-29")

        assertTrue(friday.isFriday)
        assertFalse(thursday.isFriday)

        val jumuah = windowAt(friday.dhuhr)
        assertEquals(WindowKind.PRAYER, jumuah.kind)
        assertEquals(PrayerName.DHUHR, jumuah.prayer)
        assertTrue(jumuah.isJumuah)
        assertEquals(friday.dhuhr + 60 * 60_000L, jumuah.endWall, "Jumu'ah runs for jumuah_window_min")
        assertTrue(jumuah.isJumuah && windowAt(friday.dhuhr + 59 * 60_000L).isJumuah)
        assertNotEquals(PrayerName.DHUHR, windowAt(friday.dhuhr + 60 * 60_000L).prayer)

        val weekdayDhuhr = windowAt(thursday.dhuhr + 10 * 60_000L)
        assertEquals(PrayerName.DHUHR, weekdayDhuhr.prayer)
        assertFalse(weekdayDhuhr.isJumuah)
        assertNotEquals(PrayerName.DHUHR, windowAt(thursday.dhuhr + 20 * 60_000L).prayer)
    }

    // ── Sleep gate ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun sleepGateCrossesMidnight() {
        val evening = windowAt(wall("2026-10-29", 23, 0))
        assertEquals(WindowKind.SLEEP_GATE, evening.kind)
        assertEquals(wall("2026-10-29", 22, 30), evening.startWall)
        assertEquals(wall("2026-10-30", 5, 23), evening.endWall)
        assertNull(evening.prayer, "the gate belongs to no prayer")
        assertFalse(evening.isJumuah)

        assertEquals(WindowKind.NONE, windowAt(wall("2026-10-29", 22, 29)).kind, "before bedtime")

        val afterMidnight = windowAt(wall("2026-10-30", 2, 0))
        assertEquals(WindowKind.SLEEP_GATE, afterMidnight.kind)
        assertEquals(wall("2026-10-29", 22, 30), afterMidnight.startWall, "the gate began last evening")
        assertEquals(wall("2026-10-30", 5, 23), afterMidnight.endWall, "and ends at this morning's Fajr")

        assertEquals(
            WindowKind.SLEEP_GATE,
            windowAt(wall("2026-10-30", 5, 23) - 1).kind,
            "still inside one millisecond before Fajr"
        )
        assertEquals(
            PrayerName.FAJR,
            windowAt(wall("2026-10-30", 5, 23)).prayer,
            "the gate ends exactly at Fajr, which opens the Fajr window"
        )
    }

    @Test
    fun theGateOutlivesTheFourAmLogicalDay() {
        // 04:30 is a new logical day, but the gate that began last evening is still running to Fajr.
        val context = contextAt(wall("2026-10-30", 4, 30))

        assertEquals(epochDay("2026-10-30"), context.logicalDay)
        assertEquals(WindowKind.SLEEP_GATE, context.window.kind)
        assertEquals(wall("2026-10-29", 22, 30), context.window.startWall)
        assertEquals(wall("2026-10-30", 5, 23), context.window.endWall)
    }

    @Test
    fun sleepGateStartIsClampedToIshaPlusThirty() {
        // Bedtime at 19:00 is meaningless while Isha is at 19:20; the gate waits for Isha + 30.
        val config = GuardConfig(sleepGate = SleepGateConfig(enabled = true, startMinuteOfDay = 19 * 60))
        val gate = windowAt(wall("2026-10-29", 20, 0), config)

        assertEquals(WindowKind.SLEEP_GATE, gate.kind)
        assertEquals(wall("2026-10-29", 19, 50), gate.startWall)
        assertEquals(WindowKind.NONE, windowAt(wall("2026-10-29", 19, 49), config).kind)
    }

    // ── Ramadan ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun ramadanGateRunsFromIshaPlusNinetyToFajrMinusForty() {
        val config = GuardConfig(ramadan = RamadanConfig(mode = RamadanMode.ON, hijriOffsetDays = 0))

        // 15 February 2027 is 8 Ramadan 1448 in Umm al-Qura; that night Isha is 19:52 and Fajr 05:52.
        val gate = windowAt(wall("2027-02-15", 23, 0), config)
        assertEquals(WindowKind.SLEEP_GATE, gate.kind)
        assertEquals(wall("2027-02-15", 22, 30), gate.startWall)
        assertEquals(wall("2027-02-16", 5, 12), gate.endWall, "Fajr 05:52 minus 40 minutes")

        val afterMidnight = windowAt(wall("2027-02-16", 1, 0), config)
        assertEquals(WindowKind.SLEEP_GATE, afterMidnight.kind)
        assertEquals(wall("2027-02-16", 5, 12), afterMidnight.endWall)

        assertEquals(
            WindowKind.NONE,
            windowAt(wall("2027-02-16", 5, 20), config).kind,
            "Suhoor stays open between Fajr − 40 and the adhan"
        )
        assertEquals(
            PrayerName.FAJR,
            windowAt(wall("2027-02-16", 5, 52), config).prayer,
            "the Fajr window itself is unchanged"
        )
    }

    @Test
    fun ramadanGateStartIsStillClampedToBedtime() {
        // Isha + 90 = 21:22, which is later than a 20:00 bedtime, so the Isha clamp wins.
        val config = GuardConfig(
            sleepGate = SleepGateConfig(enabled = true, startMinuteOfDay = 20 * 60),
            ramadan = RamadanConfig(mode = RamadanMode.ON, hijriOffsetDays = 0)
        )
        val gate = windowAt(wall("2027-02-15", 21, 30), config)

        assertEquals(WindowKind.SLEEP_GATE, gate.kind)
        assertEquals(wall("2027-02-15", 21, 22), gate.startWall)
    }

    @Test
    fun theHijriOffsetDecidesWhetherTheRamadanGateApplies() {
        // 8 February 2027 is 1 Ramadan 1448 with no correction, and 30 Sha'ban with a −1 correction.
        val on = GuardConfig(ramadan = RamadanConfig(mode = RamadanMode.ON, hijriOffsetDays = 0))
        val shifted = GuardConfig(ramadan = RamadanConfig(mode = RamadanMode.ON, hijriOffsetDays = -1))
        val night = wall("2027-02-08", 23, 0)

        assertEquals(wall("2027-02-09", 5, 15), windowAt(night, on).endWall, "next Fajr 05:55 − 40")
        assertEquals(wall("2027-02-09", 5, 55), windowAt(night, shifted).endWall, "an ordinary night")
        assertTrue(contextAt(night, on).ramadanActive)
        assertFalse(contextAt(night, shifted).ramadanActive)
    }

    // ── Morning ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun morningWindowRunsFromTheEndOfTheFajrWindowToMorningEnd() {
        val morning = windowAt(wall("2026-10-29", 6, 0))

        assertEquals(WindowKind.MORNING, morning.kind)
        assertEquals(wall("2026-10-29", 5, 43), morning.startWall, "Fajr 05:23 + 20")
        assertEquals(wall("2026-10-29", 9, 30), morning.endWall)
        assertNull(morning.prayer)

        assertEquals(WindowKind.MORNING, windowAt(wall("2026-10-29", 9, 29)).kind)
        assertEquals(WindowKind.NONE, windowAt(wall("2026-10-29", 9, 30)).kind, "the end is exclusive")
    }

    @Test
    fun morningWindowIsClampedToDhuhr() {
        // A 20:00 morning end is meaningless; Dhuhr at 12:23 closes the window first.
        val config = GuardConfig(morning = MorningConfig(enabled = true, endMinuteOfDay = 20 * 60))
        val morning = windowAt(wall("2026-10-29", 12, 0), config)

        assertEquals(WindowKind.MORNING, morning.kind)
        assertEquals(wall("2026-10-29", 5, 43), morning.startWall)
        assertEquals(wall("2026-10-29", 12, 23), morning.endWall)
        assertEquals(WindowKind.PRAYER, windowAt(wall("2026-10-29", 12, 23), config).kind)
    }

    @Test
    fun morningWindowIsEmptyWhenTheFajrWindowEndsAfterMorningEnd() {
        // Fajr's window closes at 05:43; a 05:25 morning end leaves no interval at all.
        val config = GuardConfig(morning = MorningConfig(enabled = true, endMinuteOfDay = 5 * 60 + 25))

        assertEquals(WindowKind.NONE, windowAt(wall("2026-10-29", 5, 45), config).kind)
        assertEquals(WindowKind.NONE, windowAt(wall("2026-10-29", 6, 30), config).kind)
        assertTrue(ended(wall("2026-10-29", 0, 0), wall("2026-10-29", 23, 59), config)
            .none { it.kind == WindowKind.MORNING })
    }

    // ── Priority ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun prayerOutranksTheSleepGateWhenTheyOverlap() {
        // A 40-minute Isha window (19:20–20:00) overlaps a gate that opens at Isha + 30 (19:50).
        val config = GuardConfig(
            prayer = PrayerConfig(windowMin = 40),
            sleepGate = SleepGateConfig(enabled = true, startMinuteOfDay = 19 * 60)
        )

        val overlapping = windowAt(wall("2026-10-29", 19, 55), config)
        assertEquals(WindowKind.PRAYER, overlapping.kind, "PRAYER wins over SLEEP_GATE")
        assertEquals(PrayerName.ISHA, overlapping.prayer)

        val afterPrayer = windowAt(wall("2026-10-29", 20, 0), config)
        assertEquals(WindowKind.SLEEP_GATE, afterPrayer.kind)
        assertEquals(wall("2026-10-29", 19, 50), afterPrayer.startWall, "the gate had already opened")
    }

    @Test
    fun disablingTheGateOrTheMorningLeavesNoWindow() {
        val noGate = GuardConfig(sleepGate = SleepGateConfig(enabled = false))
        assertEquals(WindowKind.NONE, windowAt(wall("2026-10-29", 23, 0), noGate).kind)

        val noMorning = GuardConfig(morning = MorningConfig(enabled = false))
        assertEquals(WindowKind.NONE, windowAt(wall("2026-10-29", 6, 0), noMorning).kind)
    }

    // ── Next prayer and context ────────────────────────────────────────────────────────────────

    @Test
    fun nextPrayerIsTheNextAdhanOrTomorrowsFajr() {
        val day = dayOf("2026-10-29")

        assertEquals(PrayerName.FAJR, contextAt(wall("2026-10-29", 4, 0)).nextPrayer)
        assertEquals(day.fajr, contextAt(wall("2026-10-29", 4, 0)).nextPrayerWall)

        assertEquals(PrayerName.DHUHR, contextAt(wall("2026-10-29", 12, 0)).nextPrayer)
        assertEquals(PrayerName.ASR, contextAt(wall("2026-10-29", 13, 0)).nextPrayer)
        assertEquals(PrayerName.MAGHRIB, contextAt(wall("2026-10-29", 17, 0)).nextPrayer)
        assertEquals(PrayerName.ISHA, contextAt(wall("2026-10-29", 19, 0)).nextPrayer)

        val afterIsha = contextAt(wall("2026-10-29", 20, 0))
        assertEquals(PrayerName.FAJR, afterIsha.nextPrayer, "after Isha the next adhan is tomorrow's Fajr")
        assertEquals(wall("2026-10-30", 5, 23), afterIsha.nextPrayerWall)

        assertEquals(
            PrayerName.DHUHR,
            contextAt(wall("2026-10-29", 5, 23)).nextPrayer,
            "at the adhan the prayer is in force, so the next one is Dhuhr"
        )
    }

    @Test
    fun contextCarriesTheLogicalDayMinuteOfDayAndRamadanFlag() {
        val late = contextAt(wall("2026-10-29", 23, 59))
        assertEquals(1439, late.minuteOfDay)
        assertEquals(epochDay("2026-10-29"), late.logicalDay)

        val midnight = contextAt(wall("2026-10-30", 0, 0))
        assertEquals(0, midnight.minuteOfDay)
        assertEquals(epochDay("2026-10-29"), midnight.logicalDay, "00:00 belongs to yesterday's logical day")

        val ramadan = contextAt(
            wall("2027-02-15", 12, 0),
            GuardConfig(ramadan = RamadanConfig(mode = RamadanMode.ON, hijriOffsetDays = 0))
        )
        assertTrue(ramadan.ramadanActive)

        assertFalse(
            contextAt(wall("2027-02-15", 12, 0)).ramadanActive,
            "AUTO_SUGGEST never turns the schedule on by itself"
        )
    }

    @Test
    fun todayIsThePrayerDayOfTheLogicalDay() {
        val context = contextAt(wall("2026-10-30", 2, 0))

        assertEquals(context.logicalDay, context.today.epochDay)
        assertEquals(
            wall("2026-10-29", 5, 23),
            context.today.fajr,
            "before 04:00 the in-force day is still yesterday's"
        )
        assertEquals(
            wall("2026-10-30", 5, 23),
            context.today.nextFajr,
            "the next Fajr is the one that ends the night in progress"
        )
    }

    @Test
    fun beforeFourAmAPrayerOfTheCalendarDayIsStillSeen() {
        // London on 1 May 2026: Fajr is 03:04, before the 04:00 rollover. At 03:50 the logical day is
        // still 30 April, but the morning window and the next prayer already belong to 1 May — the
        // schedule must not fall back to a Fajr that has already passed.
        val london = "Europe/London"
        val config = GuardConfig(prayer = PrayerConfig(lat = 51.5072, lon = -0.1276))
        val now = wall("2026-05-01", 3, 50, london)
        val context = evaluator.contextAt(TimeSnapshot(now, 0L, 1, london), config, 0L)

        assertEquals(epochDay("2026-04-30"), context.logicalDay)
        assertEquals(epochDay("2026-04-30"), context.today.epochDay, "today stays the logical day")
        assertEquals(WindowKind.MORNING, context.window.kind)
        assertEquals(PrayerName.DHUHR, context.nextPrayer, "the 03:04 Fajr is past, so Dhuhr is next")
        assertEquals(wall("2026-05-01", 12, 59, london), context.nextPrayerWall)
    }

    // ── Ended protected windows ────────────────────────────────────────────────────────────────

    @Test
    fun endedProtectedWindowsReportsAGateThatEndedWithNoInputInside() {
        val spans = ended(wall("2026-10-29", 22, 0), wall("2026-10-30", 6, 0))

        assertEquals(1, spans.size, "one untouched night still ended")
        assertEquals(WindowKind.SLEEP_GATE, spans[0].kind)
        assertEquals(wall("2026-10-29", 22, 30), spans[0].startWall)
        assertEquals(wall("2026-10-30", 5, 23), spans[0].endWall)
    }

    @Test
    fun endedWindowsAreExclusiveOfTheStartAndInclusiveOfTheEnd() {
        assertTrue(
            ended(wall("2026-10-30", 5, 23), wall("2026-10-30", 5, 24)).isEmpty(),
            "a window that ended exactly at the previous input has already been reported"
        )
        assertEquals(
            1,
            ended(wall("2026-10-30", 5, 22), wall("2026-10-30", 5, 23)).size,
            "a window that ends exactly at the current input is reported"
        )
        assertTrue(
            ended(wall("2026-10-29", 22, 0), wall("2026-10-30", 5, 0)).isEmpty(),
            "a gate that has not ended yet is not reported"
        )
    }

    @Test
    fun endedProtectedWindowsListsEveryWindowAcrossTwoDays() {
        val spans = ended(wall("2026-10-29", 0, 0), wall("2026-10-30", 12, 0))

        assertEquals(
            listOf(
                WindowKind.SLEEP_GATE to wall("2026-10-29", 5, 23),
                WindowKind.MORNING to wall("2026-10-29", 9, 30),
                WindowKind.SLEEP_GATE to wall("2026-10-30", 5, 23),
                WindowKind.MORNING to wall("2026-10-30", 9, 30)
            ),
            spans.map { it.kind to it.endWall }
        )
        assertEquals(wall("2026-10-29", 5, 43), spans[1].startWall)
    }

    @Test
    fun endedProtectedWindowsIgnoresAnAbsentOrBackwardsPreviousInput() {
        assertTrue(ended(0L, wall("2026-10-30", 6, 0)).isEmpty(), "a fresh install has no night to report")
        assertTrue(ended(wall("2026-10-30", 6, 0), wall("2026-10-30", 6, 0)).isEmpty(), "no time passed")
        assertTrue(
            ended(wall("2026-10-30", 6, 0), wall("2026-10-29", 6, 0)).isEmpty(),
            "a clock that moved backwards must not invent a window"
        )
    }

    @Test
    fun endedProtectedWindowsFollowsTheRamadanShape() {
        val ramadan = GuardConfig(ramadan = RamadanConfig(mode = RamadanMode.ON, hijriOffsetDays = 0))
        val spans = ended(wall("2027-02-15", 22, 0), wall("2027-02-16", 6, 0), ramadan)

        assertEquals(1, spans.size)
        assertEquals(WindowKind.SLEEP_GATE, spans[0].kind)
        assertEquals(wall("2027-02-15", 22, 30), spans[0].startWall)
        assertEquals(wall("2027-02-16", 5, 12), spans[0].endWall)

        val ordinary = ended(wall("2027-02-15", 22, 0), wall("2027-02-16", 6, 0))
        assertEquals(wall("2027-02-16", 5, 52), ordinary.single().endWall, "the default mode keeps the ordinary gate")
    }

    @Test
    fun contextAtUsesThePreviousInputForEndedProtected() {
        val context = contextAt(wall("2026-10-30", 6, 0), lastSeenWall = wall("2026-10-29", 22, 0))

        assertEquals(1, context.endedProtected.size)
        assertEquals(WindowKind.SLEEP_GATE, context.endedProtected[0].kind)
        assertEquals(wall("2026-10-30", 5, 23), context.endedProtected[0].endWall)
    }
}
