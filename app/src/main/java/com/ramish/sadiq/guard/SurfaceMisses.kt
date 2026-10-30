package com.ramish.sadiq.guard

import kotlinx.coroutines.flow.Flow

/**
 * The last logical day on which a question could not be put on screen, and how many times it was
 * missed that day.
 *
 * @property day the logical day (see [com.ramish.sadiq.core.time.LogicalDays]) the count belongs to.
 * @property count how many questions were missed on that day.
 */
data class SurfaceMiss(
    val day: Int,
    val count: Int,
)

/**
 * The record of questions no host could draw, which is what turns a silent miss into something the
 * user can see (AUDIT-5 A5-04).
 *
 * With the overlay host off, a launch that HyperOS refuses leaves the reel paused, the engine still
 * asking, and nothing on screen. The record is what the notification and the Sky card are built
 * from, so the miss is at least counted once and explained once, instead of disappearing.
 *
 * It is deliberately separate from [com.ramish.sadiq.contract.SettingsRepository.bumpOverlayFallback]:
 * that counter measures a fallback that *did* happen, and a miss is the case where no fallback could.
 * The two numbers feed different cards and must never be added together.
 */
interface SurfaceMisses {

    /** The most recent day with a miss and its total, or null when nothing has been missed yet. */
    val last: Flow<SurfaceMiss?>

    /**
     * Record one miss on [day].
     *
     * @param day the logical day the question was missed on.
     * @return that day's total, so the caller can post the explanation on the first of the day.
     */
    fun record(day: Int): Int
}
