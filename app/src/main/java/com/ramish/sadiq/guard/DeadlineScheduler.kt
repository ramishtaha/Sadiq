package com.ramish.sadiq.guard

import com.ramish.sadiq.core.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps exactly one pending timer for the next deadline the engine cares about (§3.6).
 *
 * The engine is lazy about everything it can be — window boundaries, budget refill, the day
 * rollover and the protected-window awards are all evaluated on the next input. Only six instants
 * need a wake-up by themselves: grant expiry, grace end, paused expiry, kindle confirmation, focus
 * end and rest end. The reducer answers them through `nextDeadlineElapsed`, and this class turns
 * that answer into one `delay`.
 *
 * Arming a new deadline cancels the old one, so a state change can never leave a stale timer that
 * fires an input the reducer no longer needs. The scheduler is not thread-safe on purpose: it is
 * driven from the controller's single input coroutine, which is the only caller.
 *
 * An inexact `AlarmManager` backup for the focus end lives in the session service (WP-16), not
 * here: this class owns the in-process timer only, and a killed process has no timer to keep.
 *
 * @param clock the clocks the wait is measured against.
 * @param scope the controller's single-thread scope; the timer runs there so the input it produces
 *   is queued on the same dispatcher that will process it.
 * @param onDeadline invoked when the deadline is reached. It must not block: on the engine's
 *   dispatcher it is a channel send.
 */
class DeadlineScheduler(
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val onDeadline: () -> Unit,
) {

    private var pending: Job? = null

    /** The elapsed value the pending timer is armed for, for the idempotence check below. */
    private var armedElapsed: Long? = null

    /** The last elapsed value this scheduler woke the engine for. */
    private var lastFiredElapsed: Long? = null

    /** Bumped on every arm and cancel, so a continuation that lost the race does nothing. */
    private var generation: Long = 0L

    /**
     * Arm the timer for [deadlineElapsed], or drop any pending timer when it is null.
     *
     * A deadline in the past fires at once, which is exactly what a boundary crossed while the
     * process was dead needs to happen.
     *
     * @param deadlineElapsed the elapsed reading to wake at, from
     *   [com.ramish.sadiq.core.engine.GuardReducer.nextDeadlineElapsed].
     */
    fun schedule(deadlineElapsed: Long?) {
        if (deadlineElapsed == null) {
            cancel()
            return
        }
        // Already armed for this instant: re-arming would restart the same wait from a later clock
        // reading and let a grant outlive its granted time.
        if (deadlineElapsed == armedElapsed && pending?.isActive == true) return

        // The engine was already woken for this instant and asked for the same one again, which is
        // only possible once that instant is behind us. Re-arming a past deadline would spin the
        // input loop; the next real input re-evaluates everything anyway.
        if (deadlineElapsed == lastFiredElapsed && deadlineElapsed <= clock.now().elapsedMs) return

        cancel()
        armedElapsed = deadlineElapsed
        val token = generation
        pending = scope.launch {
            val waitMs = (deadlineElapsed - clock.now().elapsedMs).coerceAtLeast(0L)
            if (waitMs > 0L) delay(waitMs)
            if (token != generation) return@launch
            lastFiredElapsed = deadlineElapsed
            armedElapsed = null
            pending = null
            onDeadline()
        }
    }

    /** Drop any pending timer without waking the engine. */
    fun cancel() {
        generation += 1
        pending?.cancel()
        pending = null
        armedElapsed = null
    }
}
