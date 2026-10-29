package com.ramish.sadiq.system

/**
 * The rule that decides whether a binding nobody has heard from may be called connected.
 *
 * `PermissionSnapshot.accessibilityConnected` is a boolean, and for the first seconds of a process
 * neither value is strictly true: the system binds the accessibility service some time *after* the
 * process starts, so at t = 1 s there is no report to read yet. Answering `false` there would turn
 * Health red on every cold start; answering `true` for ever is the A4-14 fault, where a process that
 * HyperOS killed before the service reconnected kept claiming it was guarding.
 *
 * The age of the process settles it, because that is what says whether a report was owed yet:
 *
 * - younger than [STARTUP_GRACE_MS] and unreported: **connected** — the "unknown" of a boolean
 *   contract, chosen so a Health check opened one second after launch is not a false alarm;
 * - older than that and still unreported: **not connected**, which is exactly the state left behind
 *   when the service dies with the process and never comes back;
 * - reported at all: the report wins in both directions, whatever the age.
 *
 * Pure on purpose — no `Process`, no `SystemClock` — so all three states are provable in a JVM test
 * rather than only on a phone that has just been killed.
 */
internal object AccessibilityConnection {

    /**
     * How long after process start an unreported binding is still believed.
     *
     * Ten seconds is AUDIT-4's number. The system binds `GuardAccessibilityService` within a second
     * or two of a cold start, so a screen opened straight after launch must not read "On but not
     * running" before the service has had its chance — and a minute of belief would be long enough
     * for Sadiq to look trustworthy while it is dead.
     */
    const val STARTUP_GRACE_MS: Long = 10_000L

    /**
     * Whether the binding should be reported as connected.
     *
     * @param reported whether the service has reported anything since this process started.
     * @param connected the last report, meaningless while [reported] is false.
     * @param processStartElapsedMs `Process.getStartElapsedRealtime()` for this process. Zero or
     *   negative means the platform could not date the process; the window then starts at
     *   [nowElapsedMs], because an absent start is not evidence that the service is gone.
     * @param nowElapsedMs `SystemClock.elapsedRealtime()` now.
     */
    fun isConnected(
        reported: Boolean,
        connected: Boolean,
        processStartElapsedMs: Long,
        nowElapsedMs: Long,
    ): Boolean = if (reported) connected else ageMs(processStartElapsedMs, nowElapsedMs) < STARTUP_GRACE_MS

    /**
     * How much of the grace window is left.
     *
     * The provider posts its one end-of-window re-read with this, so a surface that only collects
     * the snapshot stops showing the cold-start answer without waiting for a resume.
     *
     * @param processStartElapsedMs the process start, exactly as [isConnected] takes it.
     * @param nowElapsedMs `SystemClock.elapsedRealtime()` now.
     * @return milliseconds until an unreported binding stops being believed; zero once it has.
     */
    fun graceRemainingMs(processStartElapsedMs: Long, nowElapsedMs: Long): Long =
        (STARTUP_GRACE_MS - ageMs(processStartElapsedMs, nowElapsedMs)).coerceAtLeast(0L)

    /**
     * Age of the process, never negative.
     *
     * A start later than "now" is a clock that cannot be trusted, and reading it as an ancient
     * process would fail closed on a service that is very likely running. An unknown start (zero or
     * less) reads as a process born now, which keeps the whole window.
     */
    private fun ageMs(processStartElapsedMs: Long, nowElapsedMs: Long): Long {
        val start = if (processStartElapsedMs > 0L) processStartElapsedMs else nowElapsedMs
        return (nowElapsedMs - start).coerceAtLeast(0L)
    }
}
