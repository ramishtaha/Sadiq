package com.ramish.sadiq.core.time

import kotlinx.serialization.Serializable

/**
 * One reading of every clock the engine is allowed to see.
 *
 * Two clocks, on purpose. [wallMs] is civil time: prayer times, windows and the logical day are
 * derived from it, and the user or the network can move it. [elapsedMs] is
 * `SystemClock.elapsedRealtime()`: it counts awake time only and restarts at zero after a reboot.
 * Grants, budgets, rests and focus sessions are measured in elapsed time, so changing the wall
 * clock can never lengthen a grant or shorten a rest (§3.8).
 *
 * @property wallMs milliseconds since the Unix epoch, civil time.
 * @property elapsedMs milliseconds since boot.
 * @property bootCount `Settings.Global.BOOT_COUNT`; a different value means [elapsedMs] restarted.
 * @property zoneId the IANA zone id the wall time is expressed in, e.g. `Asia/Kolkata`.
 */
@Serializable
data class TimeSnapshot(
    val wallMs: Long,
    val elapsedMs: Long,
    val bootCount: Int,
    val zoneId: String
)

/**
 * The only way the engine and its services read time.
 *
 * Implemented once in the app layer over the platform clocks; tests use a fake. Nothing in `core`
 * may call the system clock directly, which is what makes every engine test deterministic.
 */
interface Clock {
    /** The current reading of both clocks. */
    fun now(): TimeSnapshot
}
