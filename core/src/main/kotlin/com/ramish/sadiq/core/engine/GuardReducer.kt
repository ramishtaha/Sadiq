package com.ramish.sadiq.core.engine

import com.ramish.sadiq.core.model.GuardConfig
import com.ramish.sadiq.core.model.GuardContext
import com.ramish.sadiq.core.time.TimeSnapshot

/**
 * One step of the state machine: the state to keep and the effects to perform, in that order.
 *
 * The app publishes [state] before it emits any of [effects], so a screen that reacts to an effect
 * already sees the state it belongs to.
 *
 * @property state the state after the step.
 * @property effects what the app must do, in order.
 */
data class Transition(
    val state: EngineState,
    val effects: List<Effect>
)

/**
 * The pure engine: `(state, input, now, context) → Transition`.
 *
 * It performs no I/O, reads no clock and uses no randomness, which is why every row of §3.6 can be
 * tested with a fixed [TimeSnapshot] and a hand-built [GuardContext].
 */
interface GuardReducer {
    /**
     * The state a fresh install, or a failed snapshot decode, starts from.
     *
     * @param now the current clocks.
     * @param ctx the context at startup.
     */
    fun initial(now: TimeSnapshot, ctx: GuardContext): EngineState

    /**
     * Apply one input.
     *
     * @param state the state before the input.
     * @param input what happened.
     * @param now the clocks at this instant.
     * @param ctx the context at this instant, built from [GuardConfig] and the schedule.
     */
    fun reduce(
        state: EngineState,
        input: GuardInput,
        now: TimeSnapshot,
        ctx: GuardContext
    ): Transition

    /**
     * The earliest elapsed reading at which the engine has something to do by itself, or null when
     * only an input can move it. The app keeps exactly one pending timer for this value (§3.6).
     *
     * @param state the current state.
     * @param now the clocks at this instant.
     * @param ctx the context at this instant.
     */
    fun nextDeadlineElapsed(state: EngineState, now: TimeSnapshot, ctx: GuardContext): Long?
}
