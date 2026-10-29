package com.ramish.sadiq.core.engine

import com.ramish.sadiq.core.time.TimeSnapshot

/**
 * The elapsed-time deadlines of §3.6, in one place.
 *
 * `GuardReducer.nextDeadlineElapsed` returns the earliest of: grant expiry (only while he is actually
 * in the grant, awake), grace end, paused expiry, kindle confirmation, focus end and rest end.
 * Everything else — window boundaries, budget refill, day rollover, protected-window awards — is
 * evaluated lazily on the next input, which is what keeps the design alive when HyperOS kills timers.
 *
 * A value in the past is returned as-is: the scheduler fires immediately, which is exactly what a
 * boundary crossed while the process was dead needs to happen.
 */
object Deadlines {

    /**
     * The earliest elapsed deadline for the current state, or `null` when nothing is pending.
     *
     * [now] is part of the signature because the caller hands the same snapshot to every step; the
     * arithmetic itself is clock-free.
     */
    @Suppress("UNUSED_PARAMETER")
    fun next(state: EngineState, now: TimeSnapshot): Long? {
        val candidates = mutableListOf<Long>()
        val grant = state.grant

        if (state.phase == Phase.GRANTED && state.screenOn && state.foregroundGuarded && grant != null) {
            candidates += grantExpiryElapsed(grant)
        }
        if (state.phase == Phase.HELD && grant?.heldSinceElapsed != null) {
            candidates += grant.heldSinceElapsed + EngineRules.HOLD_GRACE_MS
        }
        if (state.phase == Phase.PAUSED && grant?.pausedSinceElapsed != null) {
            candidates += grant.pausedSinceElapsed + EngineRules.PAUSED_MAX_MS
        }
        kindleConfirmElapsed(state)?.let { candidates += it }
        state.focus?.let { candidates += focusEndElapsed(it) }
        state.rest?.let { candidates += restEndElapsed(it) }

        return candidates.minOrNull()
    }

    /** Elapsed time at which the grant runs out if it keeps being used. */
    fun grantExpiryElapsed(grant: Grant): Long =
        grant.lastAccountedElapsed + Accounting.grantRemainingMs(grant)

    /** Elapsed time at which the focus session reaches its planned length. */
    fun focusEndElapsed(focus: FocusState): Long =
        focus.lastAccountedElapsed + (focus.plannedMs - focus.elapsedMs).coerceAtLeast(0L)

    /** Elapsed time at which the current rest ends. */
    fun restEndElapsed(rest: Rest): Long = rest.lastAccountedElapsed + rest.remainingMs

    /** Elapsed time at which the pending kindle confirms, if one is pending. */
    fun kindleConfirmElapsed(state: EngineState): Long? = state.kindles.minOfOrNull { it.confirmAtElapsed }
}
