package com.ramish.sadiq.testing

import com.ramish.sadiq.contract.GuardController
import com.ramish.sadiq.core.economy.EconomyDay
import com.ramish.sadiq.core.engine.Budget
import com.ramish.sadiq.core.engine.Effect
import com.ramish.sadiq.core.engine.EngineState
import com.ramish.sadiq.core.engine.GuardInput
import com.ramish.sadiq.core.engine.Phase
import com.ramish.sadiq.core.model.Mode
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A [GuardController] that records what a screen submitted and lets the test publish whatever state
 * and effects it wants next.
 *
 * The state is a real [EngineState] rather than a mock, so a ViewModel test exercises the same fields
 * the engine would produce. Effects are a shared flow with no replay: a test collects them before it
 * acts, which is exactly how the real controller behaves.
 *
 * @param initialState the state the flow starts with; an idle, full-budget evening by default.
 */
class FakeGuardController(
    initialState: EngineState = IDLE
) : GuardController {

    private val mutableState = MutableStateFlow(initialState)
    private val mutableEffects = MutableSharedFlow<Effect>(extraBufferCapacity = EFFECT_BUFFER)

    override val state: StateFlow<EngineState> = mutableState.asStateFlow()
    override val effects: SharedFlow<Effect> = mutableEffects.asSharedFlow()

    /** Every input submitted, in order. */
    val submitted: MutableList<GuardInput> = mutableListOf()

    override fun submit(input: GuardInput) {
        submitted += input
    }

    /** Publish a new state, as the engine would. @param state the new state. */
    fun emitState(state: EngineState) {
        mutableState.value = state
    }

    /** Publish an effect to collectors. @param effect the effect. */
    suspend fun emitEffect(effect: Effect) {
        mutableEffects.emit(effect)
    }

    /** Publish an effect without suspending; false when the buffer is full. @param effect the effect. */
    fun tryEmitEffect(effect: Effect): Boolean = mutableEffects.tryEmit(effect)

    /** The last input submitted, or null. */
    fun lastInput(): GuardInput? = submitted.lastOrNull()

    companion object {
        private const val EFFECT_BUFFER = 64

        /** An idle state on a full Balanced budget, with no window running. */
        val IDLE: EngineState = EngineState(
            schema = 1,
            phase = Phase.IDLE,
            foregroundPackage = null,
            foregroundGuarded = false,
            screenOn = true,
            prompt = null,
            grant = null,
            budget = Budget(
                remainingMs = Mode.BALANCED.budgetMin * 60_000L,
                lastGuardedUseElapsed = null
            ),
            rest = null,
            focus = null,
            kindles = emptyList(),
            recentOpensWall = emptyList(),
            lastReflectWall = null,
            ledger = emptyList(),
            economy = EconomyDay(logicalDay = 0),
            testArm = null,
            bootCount = 1,
            seq = 0L,
            lastSeenWall = 0L,
            lastSeenElapsed = 0L
        )
    }
}
