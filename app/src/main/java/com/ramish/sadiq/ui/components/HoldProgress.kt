package com.ramish.sadiq.ui.components

// CONTRACT-GAP: HOLD_TO_CONFIRM_MS is specified in PLAN §3.6 as an EngineRules constant owned by
// WP-04, which does not exist yet. This is the smallest local copy so the component can ship and
// the timing can be tested; when EngineRules lands, screens should pass its value or this constant
// should be replaced by an import.

/**
 * How long a hold must last before it confirms. The value is the engine's own rule (2 000 ms), kept
 * here so the kit does not depend on a work package that has not landed.
 */
const val HOLD_TO_CONFIRM_MS: Long = 2_000L

/**
 * The immutable state of one hold gesture.
 *
 * [elapsedMs] never exceeds the hold window and never goes backwards; [completed] latches once the
 * window is reached and stays true until [HoldProgress.start] begins a new gesture.
 */
data class HoldState(
    val elapsedMs: Long = 0L,
    val completed: Boolean = false
)

/**
 * The result of one advance step. [justCompleted] is true only on the step that crosses the window,
 * so a caller can fire its completion action exactly once without comparing previous states.
 */
data class HoldTick(
    val state: HoldState,
    val justCompleted: Boolean
)

/**
 * Pure hold-to-confirm timing. No clocks, no Compose, no Android: the caller feeds elapsed time and
 * this decides the fraction and the one-and-only completion.
 *
 * The rule the UI promises is exact: progress is `elapsed / holdMs`, a release before the window
 * resets to zero, and a release after it keeps the completed state (the action has already run).
 * Frame time is supplied by `withFrameNanos` in the button, so a dropped frame shortens neither the
 * hold nor the animation.
 */
class HoldProgress(val holdMs: Long = HOLD_TO_CONFIRM_MS) {

    init {
        require(holdMs > 0L) { "holdMs must be positive, was $holdMs" }
    }

    /** A fresh gesture at zero. */
    fun start(): HoldState = HoldState()

    /** Progress in `0f..1f`. */
    fun fraction(state: HoldState): Float {
        val raw = state.elapsedMs.toFloat() / holdMs.toFloat()
        return raw.coerceIn(0f, 1f)
    }

    /**
     * Adds [deltaMs] to [state]. A non-positive delta is ignored, so a clock that steps backwards
     * cannot rewind a fill that is already on screen. A completed hold is never re-advanced.
     */
    fun advance(state: HoldState, deltaMs: Long): HoldTick {
        if (state.completed) {
            return HoldTick(state = state, justCompleted = false)
        }
        val step = if (deltaMs > 0L) deltaMs else 0L
        val elapsed = (state.elapsedMs + step).coerceAtMost(holdMs)
        val completed = elapsed >= holdMs
        return HoldTick(
            state = HoldState(elapsedMs = elapsed, completed = completed),
            justCompleted = completed
        )
    }

    /**
     * Ends the gesture. Before the window this resets to zero; after it, the completed state is
     * kept so the pill does not snap back while the confirmed action is already running.
     */
    fun release(state: HoldState): HoldState = if (state.completed) state else HoldState()
}
