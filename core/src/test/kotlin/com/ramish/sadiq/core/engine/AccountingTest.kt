package com.ramish.sadiq.core.engine

import com.ramish.sadiq.core.model.Mode
import com.ramish.sadiq.core.model.WindowKind
import com.ramish.sadiq.core.testing.FakeStarEconomy
import com.ramish.sadiq.core.testing.Fixtures
import com.ramish.sadiq.core.testing.TestTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The accounting of §3.6: what consumes budget, what stops it, what survives a reboot and what a
 * reconciled span does to it.
 *
 * Every test drives the reducer with a real input, because accounting only ever runs as the first
 * stage of a transition — testing it in isolation would prove a function nobody calls that way.
 */
class AccountingTest {

    private val now = Fixtures.EVENING

    private fun running(
        grantedMs: Long = 15 * MIN,
        consumedMs: Long = 0L,
        kind: GrantKind = GrantKind.STAY,
        budgetMs: Long = 10 * MIN,
        screenOn: Boolean = true,
    ): EngineState = Fixtures.state(
        now = now,
        phase = Phase.GRANTED,
        foregroundPackage = Fixtures.INSTAGRAM.packageName,
        foregroundGuarded = true,
        screenOn = screenOn,
        budget = Fixtures.budget(remainingMs = budgetMs, lastGuardedUseElapsed = now.elapsedMs),
        grant = Fixtures.grant(
            kind = kind,
            grantedMs = grantedMs,
            consumedMs = consumedMs,
            lastAccountedElapsed = now.elapsedMs,
        ),
    )

    // ── grants ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun aGrantInFrontAwakeConsumesBothItselfAndTheBudget() {
        val rig = Rig(initial = running(grantedMs = 15 * MIN))
        rig.tick(TestTime.after(now, 5))

        assertEquals(5 * MIN, rig.state.grant?.consumedMs)
        assertEquals(5 * MIN, rig.state.budget.remainingMs)
    }

    @Test
    fun screenOffStopsConsumption() {
        val rig = Rig(initial = running(screenOn = false))
        rig.tick(TestTime.after(now, 5))

        assertEquals(0L, rig.state.grant?.consumedMs)
        assertEquals(10 * MIN, rig.state.budget.remainingMs)
    }

    @Test
    fun leavingTheAppStopsConsumption() {
        val held = Fixtures.state(
            now = now,
            phase = Phase.HELD,
            grant = Fixtures.grant(consumedMs = MIN, lastAccountedElapsed = now.elapsedMs, heldSinceElapsed = now.elapsedMs),
            budget = Fixtures.budget(remainingMs = 9 * MIN),
        )
        val rig = Rig(initial = held)
        rig.tick(TestTime.after(now, 2))

        assertEquals(MIN, rig.state.grant?.consumedMs)
        assertEquals(9 * MIN, rig.state.budget.remainingMs)
    }

    @Test
    fun aRebootDoesNotConsumeTheGrant() {
        val rig = Rig(initial = running(grantedMs = 15 * MIN))
        rig.tick(TestTime.reboot(now, wallMinutes = 30))

        assertEquals(0L, rig.state.grant?.consumedMs)
        assertEquals(10 * MIN, rig.state.budget.remainingMs)
        assertEquals(now.bootCount + 1, rig.state.bootCount)
    }

    @Test
    fun freeAndQuickGrantsDrainNothingFromTheBudget() {
        val free = Rig(initial = running(grantedMs = 5 * MIN, kind = GrantKind.FREE))
        free.tick(TestTime.after(now, 5))
        assertEquals(5 * MIN, free.state.grant?.consumedMs)
        assertEquals(10 * MIN, free.state.budget.remainingMs)

        val quick = Rig(initial = running(grantedMs = MIN, kind = GrantKind.QUICK))
        quick.tick(TestTime.after(now, 1))
        assertEquals(MIN, quick.state.grant?.consumedMs)
        assertEquals(10 * MIN, quick.state.budget.remainingMs)
    }

    /**
     * An "Open anyway" grant is the one grant that costs nothing it did not already cost at the
     * moment it was handed out (§A.3).
     *
     * It counts down like any grant, but it takes nothing out of the budget, nothing out of free
     * scroll, no quick minute, and it does not pause the rest that put the wall in front of him: a
     * moving "Rest ends at" line would be new accounting to protect a few minutes the caps already
     * bound. The day cap was charged when the grant started and is not charged again here.
     */
    @Test
    fun Accounting_override_drains_nothing_and_rest_keeps_running() {
        val resting = Fixtures.state(
            now = now,
            phase = Phase.GRANTED,
            foregroundPackage = Fixtures.INSTAGRAM.packageName,
            foregroundGuarded = true,
            budget = Fixtures.budget(remainingMs = 3 * MIN, lastGuardedUseElapsed = now.elapsedMs),
            rest = Fixtures.rest(remainingMs = 20 * MIN, lastAccountedElapsed = now.elapsedMs),
            economy = Fixtures.economyDay(overridesUsed = 1),
            grant = Fixtures.grant(
                kind = GrantKind.OVERRIDE,
                grantedMs = EngineRules.OVERRIDE_MS,
                consumedMs = 0L,
                lastAccountedElapsed = now.elapsedMs,
            ),
        )
        val rig = Rig(initial = resting)
        rig.tick(TestTime.after(now, 4))

        assertEquals(4 * MIN, rig.state.grant?.consumedMs, "the ten minutes count down like any grant")
        assertEquals(3 * MIN, rig.state.budget.remainingMs, "the budget is untouched")
        assertEquals(16 * MIN, rig.state.rest?.remainingMs, "the rest keeps counting down")
        assertEquals(1, rig.state.economy.overridesUsed, "the cap was charged at the start, once")
        assertEquals(0L, rig.state.economy.freeUsedMs)
        assertEquals(0, rig.state.economy.quickNeedsUsed)
        assertTrue(rig.effects.isEmpty())
    }

    // ── rest ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun restDecaysByElapsedInsideABoot() {
        val resting = Fixtures.state(
            now = now,
            rest = Fixtures.rest(remainingMs = 10 * MIN, lastAccountedElapsed = now.elapsedMs),
        )
        val rig = Rig(initial = resting)
        rig.tick(TestTime.after(now, 4))

        assertEquals(6 * MIN, rig.state.rest?.remainingMs)
    }

    @Test
    fun aRebootedRestUsesTheWallDeltaAndStillEnds() {
        val resting = Fixtures.state(
            now = now,
            rest = Fixtures.rest(remainingMs = 10 * MIN, lastAccountedElapsed = now.elapsedMs),
            budget = Fixtures.budget(remainingMs = 0L, lastGuardedUseElapsed = now.elapsedMs),
        )
        val rig = Rig(initial = resting)
        // The phone was off for 30 minutes: elapsed restarted at one minute, wall moved 30.
        rig.tick(TestTime.reboot(now, wallMinutes = 30))

        assertNull(rig.state.rest)
        assertEquals(10 * MIN, rig.state.budget.remainingMs)
    }

    @Test
    fun aShortRebootKeepsTheRestRunning() {
        val resting = Fixtures.state(
            now = now,
            rest = Fixtures.rest(remainingMs = 10 * MIN, lastAccountedElapsed = now.elapsedMs),
        )
        val rig = Rig(initial = resting)
        rig.tick(TestTime.reboot(now, wallMinutes = 3))

        assertEquals(7 * MIN, rig.state.rest?.remainingMs)
    }

    // ── budget ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun theSixtySecondEpsilonIsTheExhaustionLine() {
        assertTrue(Accounting.isBudgetExhausted(Fixtures.budget(remainingMs = 59_999L)))
        assertFalse(Accounting.isBudgetExhausted(Fixtures.budget(remainingMs = 60_000L)))
        assertFalse(Accounting.isBudgetExhausted(Fixtures.budget(remainingMs = 5 * MIN)))
    }

    @Test
    fun anIdleCooldownRefillsTheBudget() {
        val idle = Fixtures.state(
            now = now,
            budget = Fixtures.budget(remainingMs = 2 * MIN, lastGuardedUseElapsed = now.elapsedMs - 30 * MIN),
        )
        val rig = Rig(initial = idle)
        rig.tick()

        assertEquals(10 * MIN, rig.state.budget.remainingMs)
    }

    @Test
    fun theBudgetDoesNotRefillBeforeTheCooldown() {
        val idle = Fixtures.state(
            now = now,
            budget = Fixtures.budget(remainingMs = 2 * MIN, lastGuardedUseElapsed = now.elapsedMs - 29 * MIN),
        )
        val rig = Rig(initial = idle)
        rig.tick()

        assertEquals(2 * MIN, rig.state.budget.remainingMs)
    }

    @Test
    fun aRunningGrantBlocksTheIdleRefill() {
        val rig = Rig(
            initial = running(grantedMs = 15 * MIN, budgetMs = 2 * MIN).copy(
                budget = Fixtures.budget(remainingMs = 2 * MIN, lastGuardedUseElapsed = now.elapsedMs - 40 * MIN),
            ),
        )
        rig.tick()

        assertEquals(2 * MIN, rig.state.budget.remainingMs)
    }

    @Test
    fun theBudgetNeverGoesNegative() {
        val rig = Rig(initial = running(grantedMs = 15 * MIN, budgetMs = 3 * MIN))
        rig.tick(TestTime.after(now, 10))

        assertEquals(0L, rig.state.budget.remainingMs)
        assertEquals(10 * MIN, rig.state.grant?.consumedMs)
    }

    // ── reconciliation ────────────────────────────────────────────────────────────────────────

    @Test
    fun reconciledSpansChargeTheBudget() {
        val idle = Fixtures.state(
            now = now,
            budget = Fixtures.budget(remainingMs = 10 * MIN, lastGuardedUseElapsed = now.elapsedMs),
        )
        val rig = Rig(initial = idle)
        rig.step(
            GuardInput.Reconcile(
                listOf(Fixtures.usageSpan(startWall = now.wallMs - 4 * MIN, endWall = now.wallMs)),
            ),
        )

        assertEquals(6 * MIN, rig.state.budget.remainingMs)
    }

    @Test
    fun reconciledSpansChargeTheRestWhenOneIsRunning() {
        val resting = Fixtures.state(
            now = now,
            rest = Fixtures.rest(remainingMs = 10 * MIN, lastAccountedElapsed = now.elapsedMs),
            budget = Fixtures.budget(remainingMs = 0L, lastGuardedUseElapsed = now.elapsedMs),
        )
        val rig = Rig(initial = resting)
        rig.step(
            GuardInput.Reconcile(
                listOf(Fixtures.usageSpan(startWall = now.wallMs - 4 * MIN, endWall = now.wallMs)),
            ),
        )

        assertEquals(6 * MIN, rig.state.rest?.remainingMs)

        // A span longer than the rest ends it and hands the full budget back.
        rig.step(
            GuardInput.Reconcile(
                listOf(Fixtures.usageSpan(startWall = now.wallMs - 30 * MIN, endWall = now.wallMs)),
            ),
        )
        assertNull(rig.state.rest)
        assertEquals(10 * MIN, rig.state.budget.remainingMs)
    }

    @Test
    fun reconciledSpansOfUnguardedAppsCostNothing() {
        val idle = Fixtures.state(now = now, budget = Fixtures.budget(remainingMs = 10 * MIN))
        val rig = Rig(initial = idle)
        rig.step(
            GuardInput.Reconcile(
                listOf(Fixtures.usageSpan(packageName = "com.example.news", startWall = now.wallMs - 30 * MIN, endWall = now.wallMs)),
            ),
        )

        assertEquals(10 * MIN, rig.state.budget.remainingMs)
        assertTrue(rig.state.ledger.isEmpty())
    }

    @Test
    fun reconciledUsageInsideTheSleepGateDisqualifiesTheQuietNight() {
        val bed = Fixtures.context(
            now = now,
            window = Fixtures.window(
                kind = WindowKind.SLEEP_GATE,
                startWall = now.wallMs - 60 * MIN,
                endWall = now.wallMs + 60 * MIN,
            ),
        )
        val rig = Rig(initial = Fixtures.state(now = now), ctx = bed)
        rig.step(
            GuardInput.Reconcile(
                listOf(Fixtures.usageSpan(startWall = now.wallMs - 10 * MIN, endWall = now.wallMs)),
            ),
            context = bed,
        )

        val entry = rig.state.ledger.single()
        assertEquals(WindowKind.SLEEP_GATE, entry.kind)
        assertTrue(entry.reconciledUsage)
    }

    @Test
    fun aGrantInsideAProtectedWindowIsCountedOnTheLedger() {
        val bed = Fixtures.context(now = now, window = Fixtures.window(WindowKind.SLEEP_GATE))
        val rig = Rig(ctx = bed)
        rig.openGuarded(context = bed)
        rig.choose(Choice.QuickNeed, context = bed)

        val entry = rig.state.ledger.single()
        assertEquals(0, entry.grants)
        assertEquals(1, entry.quickNeeds)
        assertFalse(entry.reconciledUsage)
    }

    @Test
    fun aStayInsideTheMorningCountsAsAGrant() {
        val morning = Fixtures.context(now = now, window = Fixtures.window(WindowKind.MORNING))
        val rig = Rig(ctx = morning)
        rig.openGuarded(context = morning)
        assertEquals(SurfaceKind.MORNING, rig.state.prompt?.kind)
        rig.choose(Choice.Stay(2), context = morning)

        val entry = rig.state.ledger.single()
        assertEquals(1, entry.grants)
        assertEquals(WindowKind.MORNING, entry.kind)
    }

    @Test
    fun aQuietNightWithNoUseLeavesNoLedgerRow() {
        val bed = Fixtures.context(now = now, window = Fixtures.window(WindowKind.SLEEP_GATE))
        val rig = Rig(ctx = bed)
        rig.tick(context = bed)

        assertTrue(rig.state.ledger.isEmpty())
    }

    // ── focus ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun focusAccruesEvenWithTheScreenOff() {
        val focusing = Fixtures.state(
            now = now,
            screenOn = false,
            focus = Fixtures.focus(plannedMs = 25 * MIN, elapsedMs = 0L, lastAccountedElapsed = now.elapsedMs),
        )
        val rig = Rig(initial = focusing)
        rig.tick(TestTime.after(now, 10))

        assertEquals(10 * MIN, rig.state.focus?.elapsedMs)
    }

    @Test
    fun focusAcrossARebootUsesTheWallDelta() {
        val star = Fixtures.star()
        val focusing = Fixtures.state(
            now = now,
            focus = Fixtures.focus(plannedMs = 25 * MIN, elapsedMs = 5 * MIN, lastAccountedElapsed = now.elapsedMs),
        )
        val rig = Rig(economy = FakeStarEconomy(focusAwards = listOf(star)), initial = focusing)
        rig.tick(TestTime.reboot(now, wallMinutes = 30))

        // 5 + 30 minutes is past the 25 planned, so the session completes on the spot.
        assertNull(rig.state.focus)
        assertEquals(true, rig.economy.lastFocusCompleted)
        assertTrue(rig.tags().contains("Record(StarLit)"))
    }

    @Test
    fun focusIsCappedAtItsPlannedLength() {
        val focusing = Fixtures.state(
            now = now,
            focus = Fixtures.focus(plannedMs = 25 * MIN, elapsedMs = 20 * MIN, lastAccountedElapsed = now.elapsedMs),
        )
        val rig = Rig(initial = focusing)
        rig.tick(TestTime.after(now, 10))

        assertNull(rig.state.focus)
    }

    @Test
    fun aHardcoreBudgetRefillsOnlyWhenTheRestEnds() {
        val hardcore = Fixtures.state(
            now = now,
            rest = Fixtures.rest(remainingMs = 20 * MIN, lastAccountedElapsed = now.elapsedMs),
            budget = Fixtures.budget(remainingMs = 0L, lastGuardedUseElapsed = now.elapsedMs),
        )
        val ctx = Fixtures.context(now = now, config = Fixtures.config(mode = Mode.HARDCORE))
        val rig = Rig(initial = hardcore, ctx = ctx)
        rig.tick(TestTime.after(now, 21), context = ctx)

        assertNull(rig.state.rest)
        assertEquals(Mode.HARDCORE.budgetMin * MIN, rig.state.budget.remainingMs)
    }
}
