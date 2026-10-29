package com.ramish.sadiq.core.engine

import com.ramish.sadiq.core.testing.FakeReflectionPolicy
import com.ramish.sadiq.core.testing.FakeStarEconomy
import com.ramish.sadiq.core.testing.FakeSuggester
import com.ramish.sadiq.core.testing.Fixtures
import com.ramish.sadiq.core.testing.TestTime
import com.ramish.sadiq.core.time.TimeSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What `nextDeadlineElapsed` promises the scheduler (§3.6 "Timers").
 *
 * The controller keeps exactly one pending timer for this value, so the only thing worth testing is
 * which instant wins and when there is nothing to wait for at all.
 */
class DeadlinesTest {

    private val now = Fixtures.EVENING
    private val engine = GuardEngine(
        economy = FakeStarEconomy(),
        reflection = FakeReflectionPolicy(),
        suggester = FakeSuggester(),
    )
    private val ctx = Fixtures.context(now = now)

    private fun deadline(state: EngineState, at: TimeSnapshot = now): Long? =
        engine.nextDeadlineElapsed(state, at, ctx)

    @Test
    fun anIdleEngineHasNothingToWaitFor() {
        assertNull(deadline(Fixtures.state(now = now)))
    }

    @Test
    fun aRunningGrantExpiresWhenItsRemainingTimeIsGone() {
        val state = Fixtures.state(
            now = now,
            phase = Phase.GRANTED,
            foregroundGuarded = true,
            grant = Fixtures.grant(
                grantedMs = 5 * MIN,
                consumedMs = 2 * MIN,
                lastAccountedElapsed = now.elapsedMs,
            ),
        )
        assertEquals(now.elapsedMs + 3 * MIN, deadline(state))
    }

    @Test
    fun aGrantIsNotATimerWhileItIsNotInFront() {
        val state = Fixtures.state(
            now = now,
            phase = Phase.GRANTED,
            foregroundGuarded = false,
            grant = Fixtures.grant(grantedMs = 5 * MIN, consumedMs = 2 * MIN, lastAccountedElapsed = now.elapsedMs),
        )
        assertNull(deadline(state))
    }

    @Test
    fun aHeldGrantWaitsForTheGraceToEnd() {
        val state = Fixtures.state(
            now = now,
            phase = Phase.HELD,
            grant = Fixtures.grant(lastAccountedElapsed = now.elapsedMs, heldSinceElapsed = now.elapsedMs - MIN),
        )
        assertEquals(now.elapsedMs - MIN + EngineRules.HOLD_GRACE_MS, deadline(state))
    }

    @Test
    fun aPausedGrantWaitsForItsHour() {
        val state = Fixtures.state(
            now = now,
            phase = Phase.PAUSED,
            grant = Fixtures.grant(lastAccountedElapsed = now.elapsedMs, pausedSinceElapsed = now.elapsedMs - MIN),
        )
        assertEquals(now.elapsedMs - MIN + EngineRules.PAUSED_MAX_MS, deadline(state))
    }

    @Test
    fun aPendingKindleWaitsForItsConfirmation() {
        val state = Fixtures.state(
            now = now,
            kindles = listOf(Fixtures.kindle(confirmAtElapsed = now.elapsedMs + 2 * MIN)),
        )
        assertEquals(now.elapsedMs + 2 * MIN, deadline(state))
    }

    @Test
    fun aFocusWaitsForItsPlannedEnd() {
        val state = Fixtures.state(
            now = now,
            focus = Fixtures.focus(plannedMs = 25 * MIN, elapsedMs = 5 * MIN, lastAccountedElapsed = now.elapsedMs),
        )
        assertEquals(now.elapsedMs + 20 * MIN, deadline(state))
    }

    @Test
    fun aRestWaitsForItsEnd() {
        val state = Fixtures.state(
            now = now,
            rest = Fixtures.rest(remainingMs = 7 * MIN, lastAccountedElapsed = now.elapsedMs),
        )
        assertEquals(now.elapsedMs + 7 * MIN, deadline(state))
    }

    @Test
    fun theEarliestOfEverythingWins() {
        val state = Fixtures.state(
            now = now,
            rest = Fixtures.rest(remainingMs = 7 * MIN, lastAccountedElapsed = now.elapsedMs),
            focus = Fixtures.focus(plannedMs = 25 * MIN, elapsedMs = 22 * MIN, lastAccountedElapsed = now.elapsedMs),
            kindles = listOf(Fixtures.kindle(confirmAtElapsed = now.elapsedMs + 3 * MIN)),
        )
        assertEquals(now.elapsedMs + 3 * MIN, deadline(state))
    }

    @Test
    fun aDeadlineThatHasAlreadyPassedIsReturnedAsIs() {
        val state = Fixtures.state(
            now = now,
            rest = Fixtures.rest(remainingMs = MIN, lastAccountedElapsed = now.elapsedMs - 5 * MIN),
        )
        assertEquals(now.elapsedMs - 4 * MIN, deadline(state))
    }

    @Test
    fun theDeadlineDoesNotDependOnTheContext() {
        val state = Fixtures.state(
            now = now,
            rest = Fixtures.rest(remainingMs = 4 * MIN, lastAccountedElapsed = now.elapsedMs),
        )
        val other = Fixtures.context(now = TestTime.after(now, 90), endedProtected = listOf(Fixtures.span()))
        assertEquals(deadline(state), engine.nextDeadlineElapsed(state, now, other))
    }
}
