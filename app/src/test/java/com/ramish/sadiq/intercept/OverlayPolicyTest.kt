package com.ramish.sadiq.intercept

import com.ramish.sadiq.intercept.OverlayPolicy.Action
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The fallback's decision rule, row by row (AUDIT-3 G-13).
 *
 * These are the cases that decide whether the question appears at all on a phone whose OEM refuses
 * background activity starts, so each one is written as the situation it stands for rather than as a
 * row of a truth table. Two of them are the ones that must never regress: a working device never gets
 * an overlay (the activity path is untouched), and a question that is over always loses its window.
 */
class OverlayPolicyTest {

    @Test
    fun aMissedActivityLaunchShowsTheOverlay() {
        // The presenter asked, the engine is still asking, the service is bound, nothing is up.
        assertEquals(Action.SHOW, decide(requested = true))
    }

    @Test
    fun theActivityWinningTheRaceNeverShowsTheOverlay() {
        // `overlayRequested` stays false when the activity reports itself visible inside 600 ms, and
        // this is what keeps the fallback off every device where the activity works.
        assertEquals(Action.NONE, decide(requested = false))
    }

    @Test
    fun anIdleEngineDrawsNothing() {
        assertEquals(
            Action.NONE,
            decide(hasSurface = false, requested = false, prompting = false),
        )
    }

    @Test
    fun aSecondQuestionRedrawsTheWindowThatIsAlreadyUp() {
        // T17's check-in or T18's app chip: one window, a new value, no remove-and-add.
        assertEquals(Action.UPDATE, decide(requested = true, showing = true))
    }

    @Test
    fun answeringTheQuestionRemovesTheWindow() {
        // T06/T09: the engine left PROMPTING, so nothing may stay over the app underneath.
        assertEquals(
            Action.REMOVE,
            decide(requested = true, prompting = false, showing = true),
        )
    }

    @Test
    fun theScreenGoingOffRemovesTheWindow() {
        // T08 answers ScreenOff by ending PROMPTING; the same rule takes the overlay down.
        assertEquals(
            Action.REMOVE,
            decide(requested = true, prompting = false, showing = true),
        )
    }

    @Test
    fun aWithdrawnSurfaceRemovesTheWindow() {
        // `DismissSurface` nulls the holder: there is nothing left to draw.
        assertEquals(
            Action.REMOVE,
            decide(hasSurface = false, requested = true, showing = true),
        )
    }

    @Test
    fun anUnboundServiceAddsNoWindow() {
        // No accessibility service, no `TYPE_ACCESSIBILITY_OVERLAY`: there is nothing to cover with.
        assertEquals(Action.NONE, decide(requested = true, serviceBound = false))
    }

    @Test
    fun anUnboundServiceAlsoRemovesAWindowItStillHolds() {
        // The service was destroyed with the question up. The window must not outlive its binding.
        assertEquals(
            Action.REMOVE,
            decide(requested = true, serviceBound = false, showing = true),
        )
    }

    @Test
    fun aRequestThatWasAlreadySettledDrawsNothing() {
        // A late `overlayRequested` cleared by `reportGone`: no window, no surface, no work.
        assertEquals(
            Action.NONE,
            decide(hasSurface = false, requested = false, prompting = false, showing = false),
        )
    }

    /** One row of the rule, with everything else in its "the question is live" position. */
    private fun decide(
        hasSurface: Boolean = true,
        requested: Boolean = true,
        prompting: Boolean = true,
        serviceBound: Boolean = true,
        showing: Boolean = false,
    ): Action = OverlayPolicy.decide(
        hasSurface = hasSurface,
        requested = requested,
        prompting = prompting,
        serviceBound = serviceBound,
        showing = showing,
    )
}
