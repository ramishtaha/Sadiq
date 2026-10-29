package com.ramish.sadiq.core.engine

import com.ramish.sadiq.core.economy.LeaveKind
import com.ramish.sadiq.core.model.WindowKind
import com.ramish.sadiq.core.testing.Fixtures
import com.ramish.sadiq.core.testing.TestTime
import com.ramish.sadiq.core.time.TimeSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AUDIT-2 G-07: a lock and unlock must resume a held grant only over an app the grant covers.
 *
 * The F-01 fix resumed on any `ScreenOn`, so locking the phone in WhatsApp and unlocking it there
 * restarted the Sadiq grant inside WhatsApp: the budget drained while he chatted, TIME_UP appeared
 * over WhatsApp naming WhatsApp, and the DONE_EARLY star he had earned by leaving was lost. The
 * unlock now has to pass the same two tests the T02 gesture path passes — the app is guarded, and
 * the window has not changed — before it may resume.
 */
class ScreenOnResumeTest {

    /** Not guarded, not transparent: the app he leaves to, which is where the bug showed. */
    private val whatsapp = "com.whatsapp"

    @Test
    fun anUnlockInAnUnguardedAppLeavesTheGrantHeldAndDrainsNothing() {
        val now = Fixtures.EVENING
        val held = heldState(now = now, foregroundPackage = whatsapp)
        val rig = Rig(initial = held)

        rig.step(GuardInput.ScreenOn, TestTime.after(now, 1))

        assertEquals(Phase.HELD, rig.state.phase)
        assertEquals(whatsapp, rig.state.foregroundPackage)
        assertEquals(false, rig.state.foregroundGuarded)
        assertEquals(held.grant?.heldSinceElapsed, rig.state.grant?.heldSinceElapsed)
        assertEquals(9 * MIN, rig.state.budget.remainingMs)
        assertTrue(rig.effects.isEmpty())
    }

    @Test
    fun theGraceInAnUnguardedAppEndsAsAnEarlyExitAndNeverAsksAgain() {
        val now = Fixtures.EVENING
        val rig = Rig(initial = heldState(now = now, foregroundPackage = whatsapp))
        rig.step(GuardInput.ScreenOn, TestTime.after(now, 1))

        // Three minutes of chatting was the proof he left; the leave it pays is the early exit.
        rig.step(GuardInput.Tick, TestTime.after(now, 4))

        assertEquals(Phase.IDLE, rig.state.phase)
        assertNull(rig.state.grant)
        assertEquals(LeaveKind.DONE_EARLY, rig.economy.lastLeave)
        assertEquals(1, rig.state.kindles.size)
        assertNull(rig.state.prompt)
    }

    @Test
    fun anUnlockInTheGuardedAppResumesTheGrant() {
        val now = Fixtures.EVENING
        val rig = Rig(initial = heldState(now = now, foregroundPackage = Fixtures.INSTAGRAM.packageName))

        rig.step(GuardInput.ScreenOn, TestTime.after(now, 1))

        assertEquals(Phase.GRANTED, rig.state.phase)
        assertEquals(true, rig.state.foregroundGuarded)
        assertNull(rig.state.grant?.heldSinceElapsed)
        assertEquals(listOf("Live(GrantRunning)"), rig.tags())
    }

    @Test
    fun anUnlockWithNoWindowDataTrustsTheHeldGrant() {
        // No foreground was ever reported (a fresh service, or a ROM that hides package names): there
        // is nothing to judge the unlock against, so the held grant is resumed as F-01 intends.
        val now = Fixtures.EVENING
        val rig = Rig(initial = heldState(now = now, foregroundPackage = null))

        rig.step(GuardInput.ScreenOn, TestTime.after(now, 1))

        assertEquals(Phase.GRANTED, rig.state.phase)
        assertEquals(Fixtures.INSTAGRAM.packageName, rig.state.foregroundPackage)
        assertEquals(listOf("Live(GrantRunning)"), rig.tags())
    }

    @Test
    fun anUnlockInANewWindowClosesTheGrantAndAsksAgain() {
        // T02's window rule holds on the unlock path too: a Stay granted in an ordinary evening may
        // not resume itself inside the sleep gate.
        val now = Fixtures.EVENING
        val held = heldState(
            now = now,
            foregroundPackage = Fixtures.INSTAGRAM.packageName,
            windowAtStart = WindowKind.NONE,
        )
        val rig = Rig(initial = held)

        rig.step(
            GuardInput.ScreenOn,
            TestTime.after(now, 1),
            context = Fixtures.context(now = now, window = Fixtures.window(WindowKind.SLEEP_GATE)),
        )

        assertEquals(Phase.PROMPTING, rig.state.phase)
        assertEquals(SurfaceKind.SLEEP_GATE, rig.state.prompt?.kind)
        assertNull(rig.state.grant)
        assertEquals(listOf("Record(GrantClosed:LEFT)", "Duck", "Show(SLEEP_GATE)"), rig.tags())
    }

    private fun heldState(
        now: TimeSnapshot,
        foregroundPackage: String?,
        windowAtStart: WindowKind = WindowKind.NONE,
        heldForMs: Long = MIN,
    ): EngineState = Fixtures.state(
        now = now,
        phase = Phase.HELD,
        foregroundPackage = foregroundPackage,
        foregroundGuarded = false,
        grant = Fixtures.grant(
            grantedMs = 5 * MIN,
            consumedMs = MIN,
            lastAccountedElapsed = now.elapsedMs,
            heldSinceElapsed = now.elapsedMs - heldForMs,
            windowAtStart = windowAtStart,
        ),
        budget = Fixtures.budget(remainingMs = 9 * MIN),
    )
}
