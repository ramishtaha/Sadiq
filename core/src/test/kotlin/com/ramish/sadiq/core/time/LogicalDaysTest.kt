package com.ramish.sadiq.core.time

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The 04:00 rollover of §3.7.
 *
 * The interesting cases are the minute either side of the boundary and the two days a year on which
 * a zone changes its offset: a logical day must still start at 04:00 local time, whatever the offset.
 */
class LogicalDaysTest {

    private val thane: String = "Asia/Kolkata"

    private fun wall(date: String, hour: Int, minute: Int, zone: String = thane): Long =
        LocalDate.parse(date)
            .atTime(hour, minute)
            .atZone(ZoneId.of(zone))
            .toInstant()
            .toEpochMilli()

    private fun epochDay(date: String): Int = LocalDate.parse(date).toEpochDay().toInt()

    // ── The boundary ───────────────────────────────────────────────────────────────────────────

    @Test
    fun threeFiftyNineStillBelongsToYesterday() {
        assertEquals(epochDay("2026-10-28"), LogicalDays.of(wall("2026-10-29", 3, 59), thane))
    }

    @Test
    fun fourAmStartsTheNewLogicalDay() {
        assertEquals(epochDay("2026-10-29"), LogicalDays.of(wall("2026-10-29", 4, 0), thane))
        assertEquals(epochDay("2026-10-29"), LogicalDays.of(wall("2026-10-29", 12, 0), thane))
        assertEquals(epochDay("2026-10-29"), LogicalDays.of(wall("2026-10-29", 23, 59), thane))
    }

    @Test
    fun midnightIsStillThePreviousLogicalDay() {
        assertEquals(epochDay("2026-10-28"), LogicalDays.of(wall("2026-10-29", 0, 0), thane))
        assertEquals(epochDay("2026-10-28"), LogicalDays.of(wall("2026-10-29", 0, 1), thane))
        assertEquals(epochDay("2026-10-29"), LogicalDays.of(wall("2026-10-29", 23, 59), thane))
    }

    // ── startWall ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun startWallIsFourAmLocalTime() {
        val start = LogicalDays.startWall(epochDay("2026-10-29"), thane)

        assertEquals(wall("2026-10-29", 4, 0), start)
        assertEquals(Instant.parse("2026-10-28T22:30:00Z").toEpochMilli(), start, "04:00 IST is 22:30 UTC")
    }

    @Test
    fun startWallAndOfRoundTrip() {
        val day = epochDay("2026-10-29")
        val start = LogicalDays.startWall(day, thane)

        assertEquals(day, LogicalDays.of(start, thane))
        assertEquals(day - 1, LogicalDays.of(start - 1, thane), "one millisecond earlier is yesterday")
        assertEquals(day, LogicalDays.of(start + 1, thane))
    }

    // ── Zones ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun theZoneDecidesWhichLogicalDayAnInstantBelongsTo() {
        val instant = Instant.parse("2026-06-15T23:00:00Z").toEpochMilli()

        // 04:30 on 16 June in Thane, 23:00 on 15 June in UTC.
        assertEquals(epochDay("2026-06-16"), LogicalDays.of(instant, "Asia/Kolkata"))
        assertEquals(epochDay("2026-06-15"), LogicalDays.of(instant, "UTC"))
    }

    @Test
    fun aSpringForwardDayStillStartsAtFourAm() {
        // New York jumps from 02:00 EST to 03:00 EDT on 8 March 2026; 04:00 EDT is 08:00 UTC.
        val day = epochDay("2026-03-08")
        val start = LogicalDays.startWall(day, "America/New_York")

        assertEquals(Instant.parse("2026-03-08T08:00:00Z").toEpochMilli(), start)
        assertEquals(day, LogicalDays.of(start, "America/New_York"))
    }

    @Test
    fun aFallBackDayStillStartsAtFourAm() {
        // DST ends on 1 November 2026; 04:00 EST is 09:00 UTC.
        val day = epochDay("2026-11-01")
        val start = LogicalDays.startWall(day, "America/New_York")

        assertEquals(Instant.parse("2026-11-01T09:00:00Z").toEpochMilli(), start)
        assertEquals(day, LogicalDays.of(start, "America/New_York"))
    }
}
