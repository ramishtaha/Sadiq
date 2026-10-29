package com.ramish.sadiq.core.schedule

import com.ramish.sadiq.core.model.GuardConfig
import com.ramish.sadiq.core.model.GuardContext
import com.ramish.sadiq.core.model.PrayerConfig
import com.ramish.sadiq.core.model.PrayerDay
import com.ramish.sadiq.core.model.ProtectedSpan
import com.ramish.sadiq.core.time.TimeSnapshot

/** Computes prayer times for a local date. Implemented over Adhan in `AdhanPrayerTimesProvider`. */
interface PrayerTimesProvider {
    /**
     * Prayer times for one local day.
     *
     * @param epochDay the local date as an epoch day.
     * @param zoneId the zone the times are expressed in.
     * @param cfg location, method and madhab.
     */
    fun dayFor(epochDay: Int, zoneId: String, cfg: PrayerConfig): PrayerDay
}

/**
 * Turns the clock and the settings into a [GuardContext]: the active window, the logical day, the
 * next prayer and the protected windows that ended since the previous input (§3.7).
 */
interface ScheduleEvaluator {
    /**
     * The context at [now].
     *
     * @param now both clocks plus the zone.
     * @param config the settings in force.
     * @param lastSeenWall the wall time of the previous input, so windows that ended while nothing
     *   was running are still reported.
     */
    fun contextAt(now: TimeSnapshot, config: GuardConfig, lastSeenWall: Long): GuardContext

    /**
     * Every sleep gate or morning window that ended in `(fromWall, toWall]`.
     *
     * This is how a phone untouched all night still earns its quiet-night star: the award does not
     * depend on an input arriving inside the window (§3.7).
     *
     * @param fromWall exclusive lower bound in wall milliseconds.
     * @param toWall inclusive upper bound in wall milliseconds.
     * @param zoneId the zone the windows are computed in.
     * @param config the settings in force.
     */
    fun endedProtectedWindows(
        fromWall: Long,
        toWall: Long,
        zoneId: String,
        config: GuardConfig
    ): List<ProtectedSpan>
}
