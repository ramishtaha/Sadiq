package com.ramish.sadiq.ui.components

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The hold gesture is the only place Sadiq asks for a deliberate press, so its timing is pinned
 * here rather than trusted to the composable. No clocks and no Compose: every case feeds elapsed
 * milliseconds by hand.
 */
class HoldProgressTest {

    private val hold = HoldProgress(holdMs = 2_000L)

    @Test
    fun progressStartsAtZero() {
        val state = hold.start()
        assertEquals(0L, state.elapsedMs)
        assertFalse(state.completed)
        assertClose(0f, hold.fraction(state))
    }

    @Test
    fun progressIsHalfwayAtHalfTheWindow() {
        val tick = hold.advance(hold.start(), deltaMs = 1_000L)
        assertEquals(1_000L, tick.state.elapsedMs)
        assertFalse(tick.state.completed)
        assertFalse(tick.justCompleted)
        assertClose(0.5f, hold.fraction(tick.state))
    }

    @Test
    fun progressAccumulatesAcrossFrames() {
        var state = hold.start()
        repeat(4) {
            state = hold.advance(state, deltaMs = 400L).state
        }
        assertEquals(1_600L, state.elapsedMs)
        assertClose(0.8f, hold.fraction(state))
        assertFalse(state.completed)
    }

    @Test
    fun progressNeverPassesOne() {
        val tick = hold.advance(hold.start(), deltaMs = 9_999L)
        assertEquals(2_000L, tick.state.elapsedMs)
        assertTrue(tick.state.completed)
        assertTrue(tick.justCompleted)
        assertClose(1f, hold.fraction(tick.state))
    }

    @Test
    fun releasingEarlyResetsTheFill() {
        val held = hold.advance(hold.start(), deltaMs = 1_400L).state
        assertClose(0.7f, hold.fraction(held))

        val released = hold.release(held)
        assertEquals(0L, released.elapsedMs)
        assertFalse(released.completed)
        assertClose(0f, hold.fraction(released))
    }

    @Test
    fun releasingAfterCompletionKeepsTheFill() {
        val completed = hold.advance(hold.start(), deltaMs = 2_000L).state
        val released = hold.release(completed)
        assertTrue(released.completed)
        assertEquals(2_000L, released.elapsedMs)
        assertClose(1f, hold.fraction(released))
    }

    @Test
    fun completionIsReportedExactlyOnce() {
        val completed = hold.advance(hold.start(), deltaMs = 2_000L)
        assertTrue(completed.justCompleted)

        val again = hold.advance(completed.state, deltaMs = 500L)
        assertFalse(again.justCompleted)
        assertTrue(again.state.completed)
        assertEquals(2_000L, again.state.elapsedMs)
        assertClose(1f, hold.fraction(again.state))
    }

    @Test
    fun disabled_never_completes() {
        // The COST hold is disabled until the countdown is over and a reason is picked. No amount of
        // elapsed time may fire it, so a ticker that outlives the disable cannot open anything.
        var state = hold.start()
        repeat(30) {
            val tick = hold.advance(state, deltaMs = 100L, enabled = false)
            assertFalse(tick.justCompleted, "a disabled hold never reports completion")
            state = tick.state
        }
        assertEquals(0L, state.elapsedMs, "a disabled hold does not even fill")
        assertFalse(state.completed)
        assertClose(0f, hold.fraction(state))

        // One long step past the window is still nothing.
        val jumped = hold.advance(hold.start(), deltaMs = 60_000L, enabled = false)
        assertFalse(jumped.justCompleted)
        assertFalse(jumped.state.completed)

        // The same step on an enabled hold does complete, so the guard is the flag and nothing else.
        assertTrue(hold.advance(hold.start(), deltaMs = 2_000L, enabled = true).justCompleted)
    }

    @Test
    fun theCompletionFrameIsInclusive() {
        // 1 999 ms is still holding; one more millisecond confirms.
        val almost = hold.advance(hold.start(), deltaMs = 1_999L)
        assertFalse(almost.justCompleted)
        assertFalse(almost.state.completed)

        val exact = hold.advance(almost.state, deltaMs = 1L)
        assertTrue(exact.justCompleted)
        assertTrue(exact.state.completed)
    }

    @Test
    fun aBackwardsClockCannotRewindTheFill() {
        val held = hold.advance(hold.start(), deltaMs = 800L).state
        val rewound = hold.advance(held, deltaMs = -500L)
        assertEquals(800L, rewound.state.elapsedMs)
        assertFalse(rewound.justCompleted)
        assertClose(0.4f, hold.fraction(rewound.state))
    }

    @Test
    fun aZeroLengthWindowIsRejected() {
        assertFailsWith<IllegalArgumentException> { HoldProgress(holdMs = 0L) }
        assertFailsWith<IllegalArgumentException> { HoldProgress(holdMs = -1L) }
    }

    @Test
    fun theDefaultHoldIsTheEngineRule() {
        assertEquals(2_000L, HOLD_TO_CONFIRM_MS)
        assertEquals(2_000L, HoldProgress().holdMs)
    }

    private fun assertClose(expected: Float, actual: Float) {
        assertTrue(
            abs(expected - actual) < 0.0001f,
            "expected $expected but was $actual"
        )
    }
}
