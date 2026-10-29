package com.ramish.sadiq.contract

import com.ramish.sadiq.core.engine.Effect
import com.ramish.sadiq.core.engine.EngineState
import com.ramish.sadiq.core.engine.GuardInput
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The one way the app talks to the engine.
 *
 * Every input — an accessibility event, a notification action, a tap on a surface, the Focus tab, a
 * timer — goes through [submit]. The implementation serialises them onto a single consumer, so the
 * reducer never sees two inputs at once, and publishes the resulting state and effects.
 *
 * [state] changes before the effects of the same transition are emitted, so a screen reacting to an
 * effect already sees the state that produced it.
 */
interface GuardController {
    /** The engine's current state. Always has a value: the controller starts from `initial()` or a saved snapshot. */
    val state: StateFlow<EngineState>

    /** Effects to perform, in the order the reducer produced them. */
    val effects: SharedFlow<Effect>

    /** Queue an input. Never blocks the caller; safe from the main thread and from a service. */
    fun submit(input: GuardInput)
}
