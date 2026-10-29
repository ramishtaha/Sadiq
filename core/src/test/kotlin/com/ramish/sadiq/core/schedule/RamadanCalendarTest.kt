package com.ramish.sadiq.core.schedule

import com.ramish.sadiq.core.model.RamadanConfig
import com.ramish.sadiq.core.model.RamadanMode
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Ramadan is read from the Umm al-Qura calendar plus the user's correction.
 *
 * Fixtures come from java.time's Umm al-Qura chronology: 17 February 2026 is 29 Sha'ban 1447 and the
 * next day is 1 Ramadan 1447; 6 February 2027 is 29 Sha'ban 1448 and 8 February 2027 is 1 Ramadan
 * 1448.
 */
class RamadanCalendarTest {

    private val zone: String = "Asia/Kolkata"

    private fun wall(date: String, hour: Int = 12, minute: Int = 0, zoneId: String = zone): Long =
        LocalDate.parse(date)
            .atTime(hour, minute)
            .atZone(ZoneId.of(zoneId))
            .toInstant()
            .toEpochMilli()

    // ── Hijri date ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun hijriDateMatchesTheUmmAlQuraCalendar() {
        assertEquals(HijriDate(1447, 8, 29), RamadanCalendar.hijriAt(wall("2026-02-17"), zone, 0))
        assertEquals(HijriDate(1447, 9, 1), RamadanCalendar.hijriAt(wall("2026-02-18"), zone, 0))
        assertEquals(HijriDate(1448, 9, 1), RamadanCalendar.hijriAt(wall("2027-02-08"), zone, 0))
        assertEquals(HijriDate(1448, 8, 29), RamadanCalendar.hijriAt(wall("2027-02-06"), zone, 0))
    }

    @Test
    fun theOffsetMovesTheHijriDateWithTheGregorianOne() {
        assertEquals(HijriDate(1447, 9, 1), RamadanCalendar.hijriAt(wall("2026-02-17"), zone, 1))
        assertEquals(HijriDate(1447, 8, 29), RamadanCalendar.hijriAt(wall("2026-02-18"), zone, -1))
        assertEquals(HijriDate(1447, 8, 27), RamadanCalendar.hijriAt(wall("2026-02-17"), zone, -2))
    }

    @Test
    fun theZoneDecidesWhichHijriDateAnInstantFallsOn() {
        // 20:00 UTC is already 01:30 the next morning in Thane, and the Hijri date turns at midnight.
        val instant = wall("2026-02-17", 20, 0, "UTC")

        assertEquals(HijriDate(1447, 9, 1), RamadanCalendar.hijriAt(instant, zone, 0))
        assertEquals(HijriDate(1447, 8, 29), RamadanCalendar.hijriAt(instant, "UTC", 0))
    }

    // ── Ramadan is only in force when it is on ─────────────────────────────────────────────────

    @Test
    fun isRamadanOnlyWhenTheModeIsOn() {
        val inRamadan = wall("2026-02-18")

        assertTrue(RamadanCalendar.isRamadan(inRamadan, zone, RamadanConfig(mode = RamadanMode.ON)))
        assertFalse(RamadanCalendar.isRamadan(inRamadan, zone, RamadanConfig(mode = RamadanMode.OFF)))
        assertFalse(
            RamadanCalendar.isRamadan(inRamadan, zone, RamadanConfig(mode = RamadanMode.AUTO_SUGGEST)),
            "the suggestion never turns the schedule on by itself"
        )
        assertFalse(
            RamadanCalendar.isRamadan(wall("2026-02-17"), zone, RamadanConfig(mode = RamadanMode.ON)),
            "29 Sha'ban is not Ramadan"
        )
    }

    @Test
    fun theOffsetCanStartRamadanADayEarlierOrLater() {
        val on = RamadanConfig(mode = RamadanMode.ON, hijriOffsetDays = 0)
        val shifted = RamadanConfig(mode = RamadanMode.ON, hijriOffsetDays = 1)

        assertFalse(RamadanCalendar.isRamadan(wall("2026-02-17"), zone, on))
        assertTrue(
            RamadanCalendar.isRamadan(wall("2026-02-17"), zone, shifted),
            "+1 makes 17 February the first of Ramadan"
        )
        assertTrue(RamadanCalendar.isRamadan(wall("2026-03-19"), zone, on), "30 Ramadan is still Ramadan")
        assertFalse(RamadanCalendar.isRamadan(wall("2026-03-20"), zone, on), "Eid is Shawwal")
    }

    // ── The home card ──────────────────────────────────────────────────────────────────────────

    @Test
    fun theCardIsOfferedOnTheTwentyNinthOfShaaban() {
        val suggest = RamadanConfig(mode = RamadanMode.AUTO_SUGGEST)

        assertTrue(RamadanCalendar.shouldSuggest(wall("2026-02-17"), zone, suggest))
        assertTrue(RamadanCalendar.shouldSuggest(wall("2027-02-06"), zone, suggest))
        assertFalse(RamadanCalendar.shouldSuggest(wall("2026-02-16"), zone, suggest), "28 Sha'ban is too early")
        assertFalse(RamadanCalendar.shouldSuggest(wall("2026-02-18"), zone, suggest), "Ramadan has begun")
        assertFalse(
            RamadanCalendar.shouldSuggest(wall("2026-02-17"), zone, RamadanConfig(mode = RamadanMode.ON)),
            "once answered the card never returns"
        )
    }

    @Test
    fun theOffsetMovesTheCardWithTheCalendar() {
        // 7 February 2027 is 30 Sha'ban; a −1 correction makes the previous day the 29th.
        assertFalse(RamadanCalendar.shouldSuggest(wall("2027-02-07"), zone, RamadanConfig(mode = RamadanMode.AUTO_SUGGEST)))
        assertTrue(
            RamadanCalendar.shouldSuggest(
                wall("2027-02-07"),
                zone,
                RamadanConfig(mode = RamadanMode.AUTO_SUGGEST, hijriOffsetDays = -1)
            )
        )
    }
}
