package com.ramish.sadiq.ui.week

import com.ramish.sadiq.review.WeeklyReviewTime
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The one calculation that decides whether the Sunday review exists at all.
 *
 * Every case is pinned to real dates, including the week boundary, so the test fails if the
 * adjuster, the hour or the "strictly after now" rule drifts.
 */
class WeeklyReviewTimeTest {

    private val zone = "Asia/Kolkata"

    private fun wall(local: String): Long =
        ZonedDateTime.parse("$local[$zone]").toInstant().toEpochMilli()

    @Test
    fun saturdayAfternoonFiresTheNextMorning() {
        // 2026-10-31 is a Saturday; 2026-11-01 is the Sunday across the week boundary.
        val now = wall("2026-10-31T12:00:00+05:30")
        assertEquals(wall("2026-11-01T11:00:00+05:30"), WeeklyReviewTime.nextFireWall(now, zone))
    }

    @Test
    fun sundayBeforeElevenFiresTheSameMorning() {
        val now = wall("2026-11-01T10:59:00+05:30")
        assertEquals(wall("2026-11-01T11:00:00+05:30"), WeeklyReviewTime.nextFireWall(now, zone))
    }

    @Test
    fun sundayAtElevenIsAlreadyThisWeekReview() {
        val now = wall("2026-11-01T11:00:00+05:30")
        assertEquals(wall("2026-11-08T11:00:00+05:30"), WeeklyReviewTime.nextFireWall(now, zone))
    }

    @Test
    fun sundayAfterElevenWaitsAWeek() {
        val now = wall("2026-11-01T23:59:00+05:30")
        assertEquals(wall("2026-11-08T11:00:00+05:30"), WeeklyReviewTime.nextFireWall(now, zone))
    }

    @Test
    fun anyOtherDayOfTheWeekFiresOnTheComingSunday() {
        val now = wall("2026-11-04T08:00:00+05:30") // Wednesday
        assertEquals(wall("2026-11-08T11:00:00+05:30"), WeeklyReviewTime.nextFireWall(now, zone))
    }

    @Test
    fun thePhonesOwnZoneDecidesWhenElevenIs() {
        // 05:00 UTC is 10:30 in Kolkata: Sunday morning before the review, so it fires in 30 minutes.
        val now = ZonedDateTime.parse("2026-11-01T05:00:00Z").toInstant().toEpochMilli()
        assertEquals(wall("2026-11-01T11:00:00+05:30"), WeeklyReviewTime.nextFireWall(now, zone))
    }

    @Test
    fun anUnknownZoneStillProducesAFutureSunday() {
        val now = wall("2026-11-04T08:00:00+05:30")
        val next = WeeklyReviewTime.nextFireWall(now, "Not/AZone")
        val fired = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(next), java.time.ZoneId.systemDefault())
        assertEquals(java.time.DayOfWeek.SUNDAY, fired.dayOfWeek)
        assertEquals(WeeklyReviewTime.FIRE_HOUR, fired.hour)
        assertEquals(0, fired.minute)
    }
}
