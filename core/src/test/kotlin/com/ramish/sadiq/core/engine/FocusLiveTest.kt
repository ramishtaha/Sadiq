package com.ramish.sadiq.core.engine

import com.ramish.sadiq.core.testing.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Audit-3 B-4: a focus that ends must reset notification id 1.
 *
 * With keep-alive on — the default — the session service does not re-post the notification on its own,
 * so `Effect.Live` is the only thing that can replace a countdown whose session no longer exists. These
 * tests pin the two paths that end a focus (it completed, or the user ended it from the surface) and
 * both shapes of the effect (nothing running, or a grant running underneath it).
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
}
