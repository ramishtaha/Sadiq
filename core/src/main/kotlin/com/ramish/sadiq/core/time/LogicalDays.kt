package com.ramish.sadiq.core.time

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The logical day: the 24 hours that begin at 04:00 local time.
 *
 * Sadiq counts a late night as part of the day that is ending, not the one that has just started. A
 * guarded open at 01:30 is still "yesterday" for the budget, the star counters and the daily caps,
 * which is what stops a 2 AM scroll from spending tomorrow's allowance. The day is expressed as an
 * epoch day (`LocalDate.toEpochDay()`), so it can be stored in a database column and compared with a
 * plain `Int`.
 *
 * Everything here is a pure function of the wall clock and a zone id: no system clock is read, so a
 * test can pin any instant it likes (§3.7).
 */
object LogicalDays {

    /** Local hour at which one logical day hands over to the next. */
    const val ROLLOVER_HOUR: Int = 4

    /**
     * The logical day containing [wallMs].
     *
     * Before 04:00 local time the answer is the previous calendar date, so 03:59 belongs to
     * yesterday's logical day and 04:00 starts today's.
     *
     * @param wallMs milliseconds since the Unix epoch, civil time.
     * @param zoneId the IANA zone the wall time is expressed in.
     * @return the logical day as an epoch day.
     */
    fun of(wallMs: Long, zoneId: String): Int {
        val local = Instant.ofEpochMilli(wallMs).atZone(ZoneId.of(zoneId)).toLocalDateTime()
        val date: LocalDate = local.toLocalDate()
        val logicalDate: LocalDate =
            if (local.hour < ROLLOVER_HOUR) date.minusDays(1) else date
        return Math.toIntExact(logicalDate.toEpochDay())
    }

    /**
     * The first instant of [day]: 04:00 local time on that date.
     *
     * `of(startWall(day, zone), zone) == day` for every day and zone, including days on which the
     * zone springs forward or falls back. When 04:00 itself does not exist (a zone that jumps from
     * 03:30 to 04:30), java.time resolves the gap to the first instant that does, which is still the
     * first instant of the logical day.
     *
     * @param day the logical day as an epoch day.
     * @param zoneId the IANA zone the wall time is expressed in.
     * @return milliseconds since the Unix epoch, civil time.
     */
    fun startWall(day: Int, zoneId: String): Long =
        LocalDate.ofEpochDay(day.toLong())
            .atTime(ROLLOVER_HOUR, 0)
            .atZone(ZoneId.of(zoneId))
            .toInstant()
            .toEpochMilli()
}
