package com.ramish.sadiq.testing

import com.ramish.sadiq.core.time.Clock
import com.ramish.sadiq.core.time.TimeSnapshot

/**
 * A [Clock] the test moves by hand.
 *
 * Starts at the same evening the core fixtures use, so an app test and a core test can describe the
 * same moment without either reading the system clock.
 *
 * @param initial the first reading; 21:10 on 29 October 2026 in Thane by default.
 */
class FakeClock(
    initial: TimeSnapshot = EVENING
) : Clock {

    /** The reading [now] returns. */
    var current: TimeSnapshot = initial
        private set

    override fun now(): TimeSnapshot = current

    /** Set both clocks. @param snapshot the new reading. */
    fun set(snapshot: TimeSnapshot) {
        current = snapshot
    }

    /**
     * Move both clocks forward.
     *
     * @param wallMinutes minutes of wall time.
     * @param elapsedMinutes minutes of elapsed time; the same by default.
     */
    fun advance(wallMinutes: Long = 0L, elapsedMinutes: Long = wallMinutes) {
        current = current.copy(
            wallMs = current.wallMs + wallMinutes * 60_000L,
            elapsedMs = current.elapsedMs + elapsedMinutes * 60_000L
        )
    }

    /**
     * Simulate a reboot: the wall clock moves, elapsed time restarts and the boot counter increments.
     *
     * @param wallMinutes minutes that passed while the phone was off.
     */
    fun reboot(wallMinutes: Long) {
        current = current.copy(
            wallMs = current.wallMs + wallMinutes * 60_000L,
            elapsedMs = 60_000L,
            bootCount = current.bootCount + 1
        )
    }

    companion object {
        /**
         * 21:10 on 29 October 2026 in Thane, the plan's worked-example evening.
         *
         * `elapsedMs` is milliseconds since local midnight on 1 January 2026, the same elapsed epoch
         * `TestTime` in the core module uses, so an app test and a core test share one instant.
         */
        val EVENING: TimeSnapshot = TimeSnapshot(
            wallMs = 1_793_288_400_000L,
            elapsedMs = 26_082_600_000L,
            bootCount = 1,
            zoneId = "Asia/Kolkata"
        )
    }
}
