package com.ramish.sadiq.core.engine

import com.ramish.sadiq.core.testing.Fixtures
import com.ramish.sadiq.core.testing.TestTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Audit-3 B-4 and AUDIT-4 A4-10: a focus that ends must reset notification id 1 and must leave no
 * surface describing a session that is gone.
 *
 * With keep-alive on — the default — the session service does not re-post the notification on its own,
 * so `Effect.Live` is the only thing that can replace a countdown whose session no longer exists. These
 * tests pin every path that ends a focus (it completed, the user ended it from the surface, the user
 * ended it from the notification) and both shapes of the effect (nothing running, or a grant running
 * underneath it), plus the FOCUS_GUARD question that outlives its session.
 */
class FocusLiveTest {

    private val now = Fixtures.EVENING

    /** A session that has reached its planned length, so the next input completes it. */
    private fun dueFocus(plannedMs: Long = 25 * MIN): FocusState = Fixtures.focus(
        id = "f1",
        plannedMs = plannedMs,
        elapsedMs = plannedMs,
        lastAccountedElapsed = now.elapsedMs,
    )

    @Test
    fun T29_aCompletedFocusClearsTheKeepAliveCountdown() {
        val rig = Rig(initial = Fixtures.state(now = now, focus = dueFocus()))

        rig.tick()

        assertNull(rig.state.focus)
        assertTrue(rig.tags().contains("FocusCompleted(0)"), "the session must complete: ${rig.tags()}")
        assertEquals(Effect.Live(LiveState.None), rig.effects.last())
    }

    @Test
    fun T29_aCompletedFocusGivesARunningGrantItsOwnCountdownBack() {
        val running = Fixtures.state(
            now = now,
            phase = Phase.GRANTED,
            foregroundPackage = Fixtures.INSTAGRAM.packageName,
            foregroundGuarded = true,
            grant = Fixtures.grant(grantedMs = 5 * MIN, consumedMs = MIN, lastAccountedElapsed = now.elapsedMs),
            focus = dueFocus(),
        )
        val rig = Rig(initial = running)

        rig.tick()

        // The grant outlives the session, so the shade must describe the grant rather than go quiet.
        assertEquals(
            Effect.Live(
                LiveState.GrantRunning(
                    packageName = Fixtures.INSTAGRAM.packageName,
                    kind = GrantKind.STAY,
                    grantedMs = 5 * MIN,
                    remainingMs = 4 * MIN,
                ),
            ),
            rig.effects.last(),
        )
        assertEquals(Phase.GRANTED, rig.state.phase)
    }

    @Test
    fun T16_endingFocusFromTheSurfaceClearsTheKeepAliveCountdown() {
        val focus = Fixtures.focus(id = "f1", plannedMs = 25 * MIN, elapsedMs = 10 * MIN, lastAccountedElapsed = now.elapsedMs)
        val rig = Rig(initial = Fixtures.state(now = now, focus = focus))
        rig.openGuarded()
        assertEquals(SurfaceKind.FOCUS_GUARD, rig.state.prompt?.kind)

        rig.choose(Choice.EndFocus)

        // A FOCUS_GUARD prompt only exists while the engine is prompting, so the state the focus is
        // closed from never holds a grant: the notification has nothing left to count.
        assertEquals(listOf("Record(FocusClosed:false)", "Live(None)", "Update(PAUSE)"), rig.tags())
        assertTrue(rig.effects.contains(Effect.Live(LiveState.None)), "the ended session left a countdown behind")
    }

    @Test
    fun T29_aFocusThatRunsOutUnderAnOpenFocusGuardRewritesTheQuestion() {
        // The session is due while the FOCUS_GUARD question is still on screen: "You're focusing" and
        // its "End focus" now describe a session that has closed, and endFocusFromPrompt would return
        // early, so the surface has to be re-resolved without the focus (AUDIT-4 §3, B-4 residual).
        val focus = Fixtures.focus(
            id = "f1",
            plannedMs = 25 * MIN,
            elapsedMs = 10 * MIN,
            lastAccountedElapsed = now.elapsedMs,
        )
        val rig = Rig(initial = Fixtures.state(now = now, focus = focus))
        rig.openGuarded()
        assertEquals(SurfaceKind.FOCUS_GUARD, rig.state.prompt?.kind)

        rig.tick(TestTime.after(now, 15))

        assertNull(rig.state.focus)
        assertEquals(Phase.PROMPTING, rig.state.phase)
        assertEquals(SurfaceKind.PAUSE, rig.state.prompt?.kind)
        assertEquals(SurfaceKind.PAUSE, rig.update().kind)
        assertEquals(
            listOf("Record(FocusClosed:true)", "FocusCompleted(0)", "Live(None)", "Update(PAUSE)"),
            rig.tags(),
        )
    }

    @Test
    fun endingAFocusUnderARunningGrantKeepsCountingTheGrant() {
        // The grant was taken on FOCUS_GUARD and outlives the session, so ending the focus from the
        // notification must leave the grant's own countdown on id 1 rather than blanking it.
        val running = Fixtures.state(
            now = now,
            phase = Phase.GRANTED,
            foregroundPackage = Fixtures.INSTAGRAM.packageName,
            foregroundGuarded = true,
            grant = Fixtures.grant(grantedMs = 5 * MIN, consumedMs = MIN, lastAccountedElapsed = now.elapsedMs),
            focus = Fixtures.focus(
                id = "f1",
                plannedMs = 25 * MIN,
                elapsedMs = 10 * MIN,
                lastAccountedElapsed = now.elapsedMs,
            ),
        )
        val rig = Rig(initial = running)

        rig.choose(Choice.EndFocus)

        assertNull(rig.state.focus)
        assertEquals(Phase.GRANTED, rig.state.phase)
        assertEquals(
            Effect.Live(
                LiveState.GrantRunning(
                    packageName = Fixtures.INSTAGRAM.packageName,
                    kind = GrantKind.STAY,
                    grantedMs = 5 * MIN,
                    remainingMs = 4 * MIN,
                ),
            ),
            rig.effects.last(),
        )
    }
}
