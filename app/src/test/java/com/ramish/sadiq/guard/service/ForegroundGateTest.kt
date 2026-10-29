package com.ramish.sadiq.guard.service

import com.ramish.sadiq.core.engine.AppSurface
import com.ramish.sadiq.core.engine.GuardInput.ForegroundChanged
import com.ramish.sadiq.guard.service.ForegroundResolver.Result
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * AUDIT-2 G-01, the de-duplication half: the second open of the same app must be submitted again.
 *
 * Instagram → the question → "No, take me home" → launcher → Instagram. Without the fix the
 * resolver returned null for the launcher, so the last submission was still Instagram and the
 * reopening was dropped as a duplicate: he never saw the question twice in one evening. The rule is
 * that Sadiq's own window, which is what is in front while the question shows, ends the run.
 */
class ForegroundGateTest {

    private val instagram = "com.instagram.android"
    private val launcher = "com.miui.home"

    @Test
    fun theSameAppTwiceIsSubmittedOnce() {
        val gate = ForegroundGate()

        assertEquals(app(instagram), gate.submitFor(Result.App(instagram, AppSurface.APP)))
        assertNull(gate.submitFor(Result.App(instagram, AppSurface.APP)))
    }

    @Test
    fun goingHomeIsAForegroundChangeEvenThoughTheLauncherIsNeverGuarded() {
        val gate = ForegroundGate()
        gate.submitFor(Result.App(instagram, AppSurface.APP))

        assertEquals(app(launcher), gate.submitFor(Result.App(launcher, AppSurface.APP)))
    }

    @Test
    fun sadiqOnTopMakesTheSameAppANewOpen() {
        val gate = ForegroundGate()
        gate.submitFor(Result.App(instagram, AppSurface.APP))

        // The question is up: Sadiq's own window is in front.
        assertNull(gate.submitFor(Result.OwnWindow))
        // He leaves from the surface and reopens the same app: it must reach the engine again.
        assertEquals(app(instagram), gate.submitFor(Result.App(instagram, AppSurface.APP)))
    }

    @Test
    fun anUnreadableStackDoesNotForgetTheLastApp() {
        // An empty or locked window stack says nothing about what is in front, so a repeat of the
        // app that is still there must not be submitted twice.
        val gate = ForegroundGate()
        gate.submitFor(Result.App(instagram, AppSurface.APP))

        assertNull(gate.submitFor(Result.Unknown))
        assertNull(gate.submitFor(Result.App(instagram, AppSurface.APP)))
    }

    @Test
    fun aResetForgetsTheLastApp() {
        val gate = ForegroundGate()
        gate.submitFor(Result.App(instagram, AppSurface.APP))

        gate.reset()

        // The next read of the same app is submitted again: this is what screen off does.
        assertEquals(app(instagram), gate.submitFor(Result.App(instagram, AppSurface.APP)))
    }

    @Test
    fun aSurfaceChangeIsSubmittedAgain() {
        // The same package, but now in a PiP window: the surface is part of the key, because the
        // engine resolves Shorts-only apps and PiP differently.
        val gate = ForegroundGate()
        gate.submitFor(Result.App(instagram, AppSurface.APP))

        assertEquals(
            ForegroundChanged(instagram, surface = AppSurface.PIP),
            gate.submitFor(Result.App(instagram, AppSurface.PIP)),
        )
    }

    private fun app(packageName: String): ForegroundChanged =
        ForegroundChanged(packageName = packageName, surface = AppSurface.APP)
}
