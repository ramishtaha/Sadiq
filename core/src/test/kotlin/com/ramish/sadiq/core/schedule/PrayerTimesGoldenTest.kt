package com.ramish.sadiq.core.schedule

import com.ramish.sadiq.core.model.CalcMethod
import com.ramish.sadiq.core.model.Madhab
import com.ramish.sadiq.core.model.PrayerConfig
import com.ramish.sadiq.core.model.PrayerDay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Golden-instant guard for [AdhanPrayerTimesProvider].
 *
 * The Karachi fixture is the same one Rafiq pins in `PrayerEngineGoldenTest`: 24.8607 N, 67.0011 E,
 * 15 June 2026, Karachi method, Hanafi Asr, Asia/Karachi. If an Adhan upgrade, a method mapping or a
 * rounding change moves a prayer time, this test fails instead of quietly shifting the windows of
 * every Sadiq user.
 *
 * The host JVM runs in Asia/Kolkata, so these assertions also prove the provider reads the calendar
 * date it is given rather than the machine's default zone: only the fixture zone can produce 04:13.
 */
class PrayerTimesGoldenTest {

    private val provider = AdhanPrayerTimesProvider()

    private val karachi: String = "Asia/Karachi"
    private val thane: String = "Asia/Kolkata"

    private fun day(
        date: String,
        zone: String,
        lat: Double,
        lon: Double,
        method: CalcMethod = CalcMethod.KARACHI,
        madhab: Madhab = Madhab.HANAFI
    ): PrayerDay = provider.dayFor(
        epochDay = epochDay(date),
        zoneId = zone,
        cfg = PrayerConfig(lat = lat, lon = lon, method = method, madhab = madhab)
    )

    private fun epochDay(date: String): Int = LocalDate.parse(date).toEpochDay().toInt()

    private fun hhmm(wallMs: Long, zone: String): String =
        Instant.ofEpochMilli(wallMs)
            .atZone(ZoneId.of(zone))
            .toLocalTime()
            .format(DateTimeFormatter.ofPattern("HH:mm", Locale.US))

    // ── Golden ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun karachiGoldenMatchesRafiqsEngine() {
        val karachiDay = day("2026-06-15", karachi, 24.8607, 67.0011)

        assertEquals("04:13", hhmm(karachiDay.fajr, karachi), "Fajr")
        assertEquals("05:42", hhmm(karachiDay.sunrise, karachi), "Sunrise")
        assertEquals("12:33", hhmm(karachiDay.dhuhr, karachi), "Dhuhr")
        assertEquals("17:15", hhmm(karachiDay.asr, karachi), "Asr (Hanafi)")
        assertEquals("19:23", hhmm(karachiDay.maghrib, karachi), "Maghrib")
        assertEquals("20:52", hhmm(karachiDay.isha, karachi), "Isha")
    }

    @Test
    fun thaneGoldenIsRecorded() {
        // Second golden: the default install (Thane, 19.2183 / 72.9781) on the day the plan was written.
        val thaneDay = day("2026-10-29", thane, 19.2183, 72.9781)

        assertEquals("05:23", hhmm(thaneDay.fajr, thane), "Fajr")
        assertEquals("06:37", hhmm(thaneDay.sunrise, thane), "Sunrise")
        assertEquals("12:23", hhmm(thaneDay.dhuhr, thane), "Dhuhr")
        assertEquals("16:30", hhmm(thaneDay.asr, thane), "Asr (Hanafi)")
        assertEquals("18:06", hhmm(thaneDay.maghrib, thane), "Maghrib")
        assertEquals("19:20", hhmm(thaneDay.isha, thane), "Isha")
        assertEquals("05:23", hhmm(thaneDay.nextFajr, thane), "next Fajr (30 Oct)")
    }

    // ── Day shape ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun fridayFlagFollowsTheLocalDate() {
        assertEquals(false, day("2026-06-15", karachi, 24.8607, 67.0011).isFriday, "15 June 2026 is a Monday")
        assertTrue(day("2026-06-19", karachi, 24.8607, 67.0011).isFriday, "19 June 2026 is a Friday")
    }

    @Test
    fun nextFajrIsTheFollowingDaysFajr() {
        val monday = day("2026-06-15", karachi, 24.8607, 67.0011)
        val tuesday = day("2026-06-16", karachi, 24.8607, 67.0011)
        assertEquals(tuesday.fajr, monday.nextFajr)
        assertTrue(monday.nextFajr > monday.isha, "next Fajr belongs to tomorrow")
    }

    @Test
    fun timesAreOrderedThroughTheDay() {
        val thaneDay = day("2026-10-29", thane, 19.2183, 72.9781)
        val ordered = listOf(
            thaneDay.fajr,
            thaneDay.sunrise,
            thaneDay.dhuhr,
            thaneDay.asr,
            thaneDay.maghrib,
            thaneDay.isha,
            thaneDay.nextFajr
        )
        assertEquals(ordered.sorted(), ordered, "prayer times must increase through the day")
        assertEquals(ordered.toSet().size, ordered.size, "no two times may share an instant")
    }

    @Test
    fun everyTimeSitsOnAMinuteBoundary() {
        // Adhan rounds to the minute but leaves the sub-second remainder; the provider cuts it so a
        // window boundary is exactly the minute the user reads on screen.
        val thaneDay = day("2026-10-29", thane, 19.2183, 72.9781)
        val times = listOf(
            thaneDay.fajr,
            thaneDay.sunrise,
            thaneDay.dhuhr,
            thaneDay.asr,
            thaneDay.maghrib,
            thaneDay.isha,
            thaneDay.nextFajr
        )
        for (time in times) {
            assertEquals(0L, time % 60_000L, "not on a minute boundary: $time")
        }
    }

    // ── Settings flow through ───────────────────────────────────────────────────────────────────

    @Test
    fun madhabChangesAsrAndNothingElse() {
        val hanafi = day("2026-10-29", thane, 19.2183, 72.9781, madhab = Madhab.HANAFI)
        val shafi = day("2026-10-29", thane, 19.2183, 72.9781, madhab = Madhab.SHAFI)

        assertEquals("16:30", hhmm(hanafi.asr, thane), "Hanafi Asr")
        assertEquals("15:39", hhmm(shafi.asr, thane), "Shafi Asr")
        assertEquals(hanafi.fajr, shafi.fajr)
        assertEquals(hanafi.dhuhr, shafi.dhuhr)
        assertEquals(hanafi.maghrib, shafi.maghrib)
        assertEquals(hanafi.isha, shafi.isha)
    }

    @Test
    fun calculationMethodChangesFajrAndIsha() {
        val karachi = day("2026-10-29", thane, 19.2183, 72.9781, method = CalcMethod.KARACHI)
        val ummAlQura = day("2026-10-29", thane, 19.2183, 72.9781, method = CalcMethod.UMM_AL_QURA)
        val mwl = day("2026-10-29", thane, 19.2183, 72.9781, method = CalcMethod.MUSLIM_WORLD_LEAGUE)

        assertEquals("05:21", hhmm(ummAlQura.fajr, thane))
        assertEquals("19:36", hhmm(ummAlQura.isha, thane))
        assertEquals("19:16", hhmm(mwl.isha, thane))
        assertNotEquals(karachi.fajr, ummAlQura.fajr, "a different Fajr angle must move Fajr")
        assertNotEquals(karachi.isha, mwl.isha, "a different Isha angle must move Isha")
    }

    @Test
    fun latitudeAndLongitudeMoveTheTimes() {
        val thaneDay = day("2026-10-29", thane, 19.2183, 72.9781)
        val karachiDay = day("2026-10-29", thane, 24.8607, 67.0011)
        assertNotEquals(thaneDay.dhuhr, karachiDay.dhuhr, "a different longitude must move solar noon")
    }
}
