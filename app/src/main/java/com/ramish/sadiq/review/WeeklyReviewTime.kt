package com.ramish.sadiq.review

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * When the weekly review is due: the next Sunday at 11:00 local time, strictly after now.
 *
 * Pure, so the one calculation that decides whether the review arrives at all is unit-tested rather
 * than trusted to an alarm. Sunday 11:00 is the plan's moment: late enough for a Saturday night to be
 * over, early enough to still shape the day.
 *
 * The zone comes from the caller's [com.ramish.sadiq.core.time.Clock] reading, so a phone that
 * travels produces the review at 11:00 where the phone is, not at 11:00 in the zone it was set up in.
 */
object WeeklyReviewTime {

    /** The local hour the review is due. */
    const val FIRE_HOUR: Int = 11

    private const val FIRE_MINUTE: Int = 0

    /**
     * The wall time of the next Sunday 11:00.
     *
     * @param nowWall milliseconds since the Unix epoch, civil time.
     * @param zoneId the zone the wall time is expressed in.
     * @return milliseconds since the Unix epoch of the next firing, always in the future.
     */
    fun nextFireWall(nowWall: Long, zoneId: String): Long {
        val zone: ZoneId = runCatching { ZoneId.of(zoneId) }.getOrDefault(ZoneId.systemDefault())
        val now = Instant.ofEpochMilli(nowWall).atZone(zone)
        val sunday = now.toLocalDate().with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
        val today = sunday.atTime(FIRE_HOUR, FIRE_MINUTE).atZone(zone)
        val next = if (today.toInstant().isAfter(now.toInstant())) today else today.plusWeeks(1)
        return next.toInstant().toEpochMilli()
    }
}
