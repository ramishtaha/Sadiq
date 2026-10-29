package com.ramish.sadiq.guard

/**
 * The debug-only wall-clock offset used to travel through time during a manual test.
 *
 * Only the *calendar* moves: grants, rests and focus sessions are measured in `elapsedRealtime`
 * (§3.8), so shifting this offset can change which window is active, when the next prayer is and
 * which logical day it is, but it can never lengthen a grant or shorten a rest. That asymmetry is
 * deliberate — it is what makes a time-travel test safe to run against the real engine.
 *
 * The only writer is [com.ramish.sadiq.debug.DebugCommandsReceiver], which exists in the debug
 * source set alone; a release build compiles this object but nothing ever calls [setMinutes]. The
 * value is read once per [SystemClockSource.now] (once per engine input), so a `@Volatile` field is
 * enough and cheaper than a lock on that path.
 */
object ClockOffset {

    /** The offset applied to the wall clock, in milliseconds. Zero in every ordinary run. */
    @Volatile
    var wallOffsetMs: Long = 0L
        private set

    /** The offset in minutes, for the debug dump. */
    val wallOffsetMin: Long get() = wallOffsetMs / MILLIS_PER_MINUTE

    /**
     * Move the wall clock.
     *
     * @param minutes the offset in minutes; negative travels into the past.
     */
    fun setMinutes(minutes: Long) {
        wallOffsetMs = minutes * MILLIS_PER_MINUTE
    }

    /** Return the wall clock to real time. */
    fun clear() {
        wallOffsetMs = 0L
    }

    /** The wall clock a caller should see. @param realWallMs the platform's wall reading. */
    fun applyTo(realWallMs: Long): Long = realWallMs + wallOffsetMs

    private const val MILLIS_PER_MINUTE: Long = 60_000L
}
