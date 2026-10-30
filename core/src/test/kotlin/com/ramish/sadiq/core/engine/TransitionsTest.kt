package com.ramish.sadiq.core.engine

import com.ramish.sadiq.core.economy.DefaultStarEconomy
import com.ramish.sadiq.core.economy.EconomyRules
import com.ramish.sadiq.core.economy.LeaveDecision
import com.ramish.sadiq.core.insight.ReflectionReason
import com.ramish.sadiq.core.model.GuardContext
import com.ramish.sadiq.core.model.Mode
import com.ramish.sadiq.core.model.WindowKind
import com.ramish.sadiq.core.sound.SoundKind
import com.ramish.sadiq.core.testing.FakeReflectionPolicy
import com.ramish.sadiq.core.testing.FakeStarEconomy
import com.ramish.sadiq.core.testing.FakeSuggester
import com.ramish.sadiq.core.testing.Fixtures
import com.ramish.sadiq.core.testing.TestTime
import com.ramish.sadiq.core.time.TimeSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One test per row of §3.6, T01–T35.
 *
 * The shared [Rig] and the effect-tag helpers live in this file because WP-06 owns exactly six test
 * files; the other five suites in this package use them. A tag is the compact shape of an effect
 * (`Show(PAUSE)`, `Record(Open:LEFT)`), so an order assertion reads like the row it tests.
 */
class TransitionsTest {

    // ── T01 ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun T01_guardedOpenInIdleResolvesAndShowsTheSurface() {
        val rig = Rig()
        rig.openGuarded()

        assertEquals(Phase.PROMPTING, rig.state.phase)
        assertEquals(SurfaceKind.PAUSE, rig.state.prompt?.kind)
        assertEquals(listOf("Duck", "Show(PAUSE)", "Record(Open:DISMISSED)"), rig.tags())
        assertEquals(listOf(Fixtures.EVENING.wallMs), rig.state.recentOpensWall)
        assertEquals(Fixtures.EVENING.elapsedMs, rig.state.budget.lastGuardedUseElapsed)
    }

    // ── T02 / T03 ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun T02_returnInsideTheGraceAndSameWindowResumesTheGrant() {
        val now = Fixtures.EVENING
        val held = Fixtures.state(
            now = now,
            phase = Phase.HELD,
            grant = Fixtures.grant(
                grantedMs = 5 * MIN,
                consumedMs = MIN,
                lastAccountedElapsed = now.elapsedMs,
                heldSinceElapsed = now.elapsedMs - MIN,
            ),
            budget = Fixtures.budget(remainingMs = 9 * MIN),
        )
        val rig = Rig(initial = held)
        rig.openGuarded(at = TestTime.after(now, 1))

        assertEquals(Phase.GRANTED, rig.state.phase)
        assertEquals(listOf("Live(GrantRunning)"), rig.tags())
        assertNull(rig.state.grant?.heldSinceElapsed)
    }

    @Test
    fun T03_returnAfterTheGraceClosesTheGrantAndAsksAgain() {
        val now = Fixtures.EVENING
        val held = Fixtures.state(
            now = now,
            phase = Phase.HELD,
            grant = Fixtures.grant(
                consumedMs = MIN,
                lastAccountedElapsed = now.elapsedMs,
                heldSinceElapsed = now.elapsedMs - 4 * MIN,
            ),
        )
        val rig = Rig(initial = held)
        rig.openGuarded(at = TestTime.after(now, 4))

        assertEquals(Phase.PROMPTING, rig.state.phase)
        assertEquals(SurfaceKind.PAUSE, rig.state.prompt?.kind)
        assertEquals(listOf("Record(GrantClosed:LEFT)", "Duck", "Show(PAUSE)"), rig.tags())
        assertNull(rig.state.grant)
    }

    // ── T04 / T05 ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun T04_pausedOpenWithASoftSurfaceOffersTheSavedTimeBack() {
        val now = Fixtures.EVENING
        val paused = Fixtures.state(
            now = now,
            phase = Phase.PAUSED,
            grant = Fixtures.grant(
                grantedMs = 5 * MIN,
                consumedMs = MIN,
                lastAccountedElapsed = now.elapsedMs,
                pausedSinceElapsed = now.elapsedMs - MIN,
            ),
        )
        val rig = Rig(initial = paused)
        rig.openGuarded()

        assertEquals(Phase.PROMPTING, rig.state.phase)
        assertEquals(SurfaceKind.PAUSE, rig.state.prompt?.kind)
        assertEquals(4 * MIN, rig.ui().resumeRemainingMs)
        assertEquals(listOf("Duck", "Show(PAUSE)"), rig.tags())
        assertNotNull(rig.state.grant)
    }

    @Test
    fun T05_pausedOpenWithAStrongerWindowSupersedesTheGrant() {
        val now = Fixtures.EVENING
        val paused = Fixtures.state(
            now = now,
            phase = Phase.PAUSED,
            grant = Fixtures.grant(
                consumedMs = MIN,
                lastAccountedElapsed = now.elapsedMs,
                pausedSinceElapsed = now.elapsedMs - MIN,
            ),
        )
        val rig = Rig(initial = paused)
        val bed = Fixtures.context(now = now, window = Fixtures.window(WindowKind.SLEEP_GATE))
        rig.openGuarded(context = bed)

        assertEquals(SurfaceKind.SLEEP_GATE, rig.state.prompt?.kind)
        assertEquals(listOf("Record(GrantClosed:SUPERSEDED)", "Show(SLEEP_GATE)"), rig.tags())
        assertNull(rig.state.grant)
    }

    // ── T06 ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun T06_leaveDismissesGoesHomeAndAsksTheEconomy() {
        val rig = Rig()
        rig.openGuarded()
        rig.choose(Choice.Leave, TestTime.after(rig.now, 1))

        assertEquals(Phase.IDLE, rig.state.phase)
        assertEquals(
            listOf("Dismiss", "Home", "Release", "Record(Open:LEFT)"),
            rig.tags(),
        )
        assertEquals("LEFT_PROMPT", rig.economy.lastLeave?.name)
        assertEquals(1, rig.state.kindles.size)
        assertEquals(1, rig.effects.filterIsInstance<Effect.Record>().size)
    }

    // ── T07 ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun T07_anotherAppDismissesWithoutGoingHome() {
        val rig = Rig()
        rig.openGuarded()
        rig.openGuarded(pkg = "com.example.news")

        assertEquals(Phase.IDLE, rig.state.phase)
        assertEquals(listOf("Dismiss", "Release", "Record(Open:LEFT_GESTURE)"), rig.tags())
        assertEquals("com.example.news", rig.state.foregroundPackage)
        assertEquals("LEFT_GESTURE", rig.economy.lastLeave?.name)
    }

    // ── T08 ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun T08_screenOffWhilePromptingCountsAsALeave() {
        val rig = Rig()
        rig.openGuarded()
        rig.step(GuardInput.ScreenOff, TestTime.after(rig.now, 1))

        assertEquals(Phase.IDLE, rig.state.phase)
        assertEquals(listOf("Dismiss", "Release", "Record(Open:SCREEN_OFF)"), rig.tags())
        assertEquals(false, rig.state.screenOn)
        assertEquals("SCREEN_OFF", rig.economy.lastLeave?.name)
    }


    // ── F01 ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun F01_screenOnInIdleWithForegroundGuardedRepromptsAndKindleNeverLights() {
        val rig = Rig()
        rig.openGuarded() // This will put it into PROMPTING
        rig.step(GuardInput.ScreenOff, TestTime.after(rig.now, 1)) // This will put it into IDLE and clear prompt

        // Now, screen on while the same app is in foreground
        rig.step(GuardInput.ScreenOn, TestTime.after(rig.now, 2))

        assertEquals(Phase.PROMPTING, rig.state.phase)
        assertNotNull(rig.state.prompt)
        assertEquals(0, rig.state.kindles.size) // Kindle should not be lit
        assertEquals(listOf("Duck", "Show(PAUSE)", "Record(Open:DISMISSED)"), rig.tags()) // Reprompting effects
    }

    @Test
    fun F01_screenOnInHeldWithForegroundGuardedResumesConsumption() {
        val now = Fixtures.EVENING
        val held = Fixtures.state(
            now = now,
            phase = Phase.HELD,
            grant = Fixtures.grant(
                grantedMs = 5 * MIN,
                consumedMs = MIN,
                lastAccountedElapsed = now.elapsedMs,
                heldSinceElapsed = now.elapsedMs - MIN,
            ),
            budget = Fixtures.budget(remainingMs = 9 * MIN),
        )
        val rig = Rig(initial = held)
        rig.step(GuardInput.ScreenOn, TestTime.after(now, 1)) // ScreenOn while held

        assertEquals(Phase.GRANTED, rig.state.phase)
        assertNull(rig.state.grant?.heldSinceElapsed)
        assertEquals(listOf("Live(GrantRunning)"), rig.tags())
    }


    @Test
    fun T09_stayGrantsTheChosenMinutesAndReleasesTheSurface() {
        val rig = Rig()
        rig.openGuarded()
        rig.choose(Choice.Stay(5), TestTime.after(rig.now, MIN / MIN))

        assertEquals(Phase.GRANTED, rig.state.phase)
        assertEquals(listOf("Dismiss", "Release", "Live(GrantRunning)", "Record(Open:STAYED)"), rig.tags())
        assertEquals(GrantKind.STAY, rig.state.grant?.kind)
        assertEquals(5 * MIN, rig.state.grant?.grantedMs)
        assertEquals("g1", rig.state.grant?.id)
    }

    @Test
    fun T09_stayOutsideTheChipsIsIgnored() {
        val rig = Rig()
        rig.openGuarded()
        rig.choose(Choice.Stay(7))

        assertEquals(Phase.PROMPTING, rig.state.phase)
        assertTrue(rig.effects.isEmpty())
    }

    // ── T10 ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun T10_resumeContinuesTheSavedGrant() {
        val now = Fixtures.EVENING
        val paused = Fixtures.state(
            now = now,
            phase = Phase.PAUSED,
            grant = Fixtures.grant(id = "g7", consumedMs = MIN, lastAccountedElapsed = now.elapsedMs, pausedSinceElapsed = now.elapsedMs - MIN),
        )
        val rig = Rig(initial = paused)
        rig.openGuarded()
        rig.choose(Choice.Resume)

        assertEquals(Phase.GRANTED, rig.state.phase)
        assertEquals("g7", rig.state.grant?.id)
        assertNull(rig.state.grant?.pausedSinceElapsed)
        assertEquals(listOf("Dismiss", "Release", "Live(GrantRunning)", "Record(Open:RESUMED)"), rig.tags())
    }

    // ── T11 ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun T11_freeScrollSpendsTodayBalanceInsteadOfTheBudget() {
        val rig = Rig(economy = FakeStarEconomy(freeScrollMs = 10 * MIN))
        rig.openGuarded()
        val promptUi = rig.ui()
        val before = rig.state.budget.remainingMs
        rig.choose(Choice.FreeScroll(5))

        assertEquals(GrantKind.FREE, rig.state.grant?.kind)
        assertEquals(10 * MIN, promptUi.freeScrollAvailableMs)
        assertEquals(listOf(5, 10), promptUi.freeScrollOptionsMin)
        assertEquals(before, rig.state.budget.remainingMs)
        assertEquals(5 * MIN, rig.state.economy.freeUsedMs)
        assertEquals(
            listOf("Dismiss", "Release", "Live(GrantRunning)", "Record(Open:FREE_SCROLL)"),
            rig.tags(),
        )
    }

    @Test
    fun T11_freeScrollBeyondTheBalanceIsIgnored() {
        val rig = Rig(economy = FakeStarEconomy(freeScrollMs = 3 * MIN))
        rig.openGuarded()
        rig.choose(Choice.FreeScroll(5))

        assertEquals(Phase.PROMPTING, rig.state.phase)
    }

    // ── T12 ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun T12_quickNeedIsOneMinuteAndCountsAgainstTheDay() {
        val now = Fixtures.EVENING
        val resting = Fixtures.state(
            now = now,
            rest = Fixtures.rest(remainingMs = 5 * MIN, lastAccountedElapsed = now.elapsedMs),
            budget = Fixtures.budget(remainingMs = 0L, lastGuardedUseElapsed = now.elapsedMs),
        )
        val rig = Rig(initial = resting)
        rig.openGuarded()
        assertEquals(SurfaceKind.COOLDOWN, rig.state.prompt?.kind)
        val promptUi = rig.ui()

        rig.choose(Choice.QuickNeed)

        assertEquals(GrantKind.QUICK, rig.state.grant?.kind)
        assertEquals(EngineRules.QUICK_NEED_MS, rig.state.grant?.grantedMs)
        assertEquals(1, rig.state.economy.quickNeedsUsed)
        assertEquals(3, promptUi.quickNeedsLeft)
        assertEquals(
            listOf("Dismiss", "Release", "Live(GrantRunning)", "Record(Open:QUICK_NEED)"),
            rig.tags(),
        )
    }

    @Test
    fun T12_quickNeedInTheSleepGateMarksTheLedger() {
        val now = Fixtures.EVENING
        val bed = Fixtures.context(now = now, window = Fixtures.window(WindowKind.SLEEP_GATE))
        val rig = Rig(ctx = bed)
        rig.openGuarded(context = bed)
        assertEquals(SurfaceKind.SLEEP_GATE, rig.state.prompt?.kind)

        rig.choose(Choice.QuickNeed, context = bed)

        val entry = rig.state.ledger.single()
        assertEquals(1, entry.quickNeeds)
        assertEquals(WindowKind.SLEEP_GATE, entry.kind)
    }

    // ── T13 ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun T13_startFocusNowFromMorningStartsTheSessionAndLeaves() {
        val now = Fixtures.EVENING
        val morning = Fixtures.context(now = now, window = Fixtures.window(WindowKind.MORNING))
        val rig = Rig(ctx = morning)
        rig.openGuarded(context = morning)
        assertEquals(SurfaceKind.MORNING, rig.state.prompt?.kind)

        rig.choose(Choice.StartFocusNow(5), context = morning)

        assertEquals(Phase.IDLE, rig.state.phase)
        assertEquals(5 * MIN, rig.state.focus?.plannedMs)
        assertEquals(
            listOf("Dismiss", "Home", "Release", "Live(FocusRunning)", "Record(Open:FOCUS_STARTED)"),
            rig.tags(),
        )
    }

    // ── T14 ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun T14_windDownPlaysRainForTwentyMinutes() {
        val now = Fixtures.EVENING
        val bed = Fixtures.context(now = now, window = Fixtures.window(WindowKind.SLEEP_GATE))
        val rig = Rig(ctx = bed)
        rig.openGuarded(context = bed)
        rig.choose(Choice.WindDown, context = bed)

        assertEquals(Phase.IDLE, rig.state.phase)
        assertEquals(
            listOf("Dismiss", "Home", "Sound(RAIN,1200000)", "Record(Open:WIND_DOWN)"),
            rig.tags(),
        )
        assertTrue(rig.economy.calls.none { it == "onLeave" })
    }

    // ── T15 ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun T15_backToFocusLeavesWithoutAStar() {
        val now = Fixtures.EVENING
        val focusing = Fixtures.state(
            now = now,
            focus = Fixtures.focus(id = "f1", plannedMs = 25 * MIN, lastAccountedElapsed = now.elapsedMs),
        )
        val rig = Rig(initial = focusing)
        rig.openGuarded()
        assertEquals(SurfaceKind.FOCUS_GUARD, rig.state.prompt?.kind)

        rig.choose(Choice.BackToFocus)

        assertEquals(Phase.IDLE, rig.state.phase)
        assertEquals(listOf("Dismiss", "Home", "Release"), rig.tags())
        assertTrue(rig.economy.calls.none { it == "onLeave" })
        assertTrue(rig.state.kindles.isEmpty())
    }

    // ── T16 ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun T16_endingFocusFromTheSurfaceAsksTheNormalQuestion() {
        val now = Fixtures.EVENING
        val star = Fixtures.star()
        val economy = FakeStarEconomy(focusAwards = listOf(star))
        val focusing = Fixtures.state(
            now = now,
            focus = Fixtures.focus(id = "f1", plannedMs = 25 * MIN, elapsedMs = 10 * MIN, lastAccountedElapsed = now.elapsedMs),
        )
        val rig = Rig(economy = economy, initial = focusing)
        rig.openGuarded()
        rig.choose(Choice.EndFocus)

        assertEquals(Phase.PROMPTING, rig.state.phase)
        assertNull(rig.state.focus)
        assertEquals(SurfaceKind.PAUSE, rig.state.prompt?.kind)
        assertEquals(
            listOf("Record(FocusClosed:false)", "Record(StarLit)", "Live(None)", "Update(PAUSE)"),
            rig.tags(),
        )
        assertEquals(false, economy.lastFocusCompleted)
    }

    // ── T17 ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun T17_reflectionAnswerRecordsTheReasonAndShowsTheResponse() {
        val rig = Rig(reflection = FakeReflectionPolicy(opens = 6, reflect = true))
        rig.openGuarded()
        assertEquals(SurfaceKind.REFLECT, rig.state.prompt?.kind)

        rig.choose(Choice.Reflect(ReflectionReason.TIRED))

        assertEquals(ReflectionReason.TIRED, rig.state.prompt?.reflectReason)
        assertEquals(ReflectionReason.TIRED, rig.update().reflectReason)
        assertEquals(listOf("Record(Reflected)", "Update(REFLECT)"), rig.tags())
        assertEquals(Fixtures.EVENING.wallMs, rig.state.lastReflectWall)
    }

    // ── T18 ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun T18_anotherGuardedAppKeepsTheKindAndChangesTheChip() {
        val rig = Rig(ctx = Fixtures.context(now = Fixtures.EVENING, config = BOTH_APPS))
        rig.openGuarded()
        rig.openGuarded(pkg = Fixtures.YOUTUBE.packageName, at = TestTime.after(rig.now, 1))

        assertEquals(Phase.PROMPTING, rig.state.phase)
        assertEquals(SurfaceKind.PAUSE, rig.state.prompt?.kind)
        assertEquals(Fixtures.YOUTUBE.packageName, rig.update().packageName)
        assertEquals(listOf("Update(PAUSE)"), rig.tags())
    }

    // ── T19 ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun T19_ourOwnPackageIsTransparent() {
        val rig = Rig()
        rig.openGuarded()
        val before = rig.state
        rig.openGuarded(pkg = Guardedness.OWN_PACKAGE)

        assertEquals(before.prompt, rig.state.prompt)
        assertEquals(before.foregroundPackage, rig.state.foregroundPackage)
        assertTrue(rig.effects.isEmpty())
    }

    // ── T20 ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun T20_unGuardingTheAppUnderneathDismissesTheSurface() {
        val rig = Rig()
        rig.openGuarded()
        val unguarded = Fixtures.context(now = rig.now, config = Fixtures.config(apps = emptyList()))
        rig.tick(context = unguarded)

        assertEquals(Phase.IDLE, rig.state.phase)
        assertEquals(listOf("Dismiss", "Release"), rig.tags())
    }

    // ── T21 ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun T21_spentGrantComesBackAsTimeUp() {
        val now = Fixtures.EVENING
        val running = Fixtures.state(
            now = now,
            phase = Phase.GRANTED,
            foregroundPackage = Fixtures.INSTAGRAM.packageName,
            foregroundGuarded = true,
            grant = Fixtures.grant(grantedMs = 5 * MIN, consumedMs = 5 * MIN, lastAccountedElapsed = now.elapsedMs),
        )
        val rig = Rig(initial = running)
        rig.tick()

        assertEquals(Phase.PROMPTING, rig.state.phase)
        assertEquals(SurfaceKind.TIME_UP, rig.state.prompt?.kind)
        assertEquals(GrantKind.STAY, rig.ui().timeUpKind)
        assertEquals(
            listOf("Duck", "Show(TIME_UP)", "Live(None)", "Record(GrantClosed:EXPIRED)"),
            rig.tags(),
        )
        assertNull(rig.state.rest)
    }

    @Test
    fun T21_spendingTheLastStayMinuteStartsTheRest() {
        val now = Fixtures.EVENING
        val running = Fixtures.state(
            now = now,
            phase = Phase.GRANTED,
            foregroundGuarded = true,
            budget = Fixtures.budget(remainingMs = 40_000L, lastGuardedUseElapsed = now.elapsedMs),
            grant = Fixtures.grant(grantedMs = MIN, consumedMs = MIN, lastAccountedElapsed = now.elapsedMs),
        )
        val rig = Rig(initial = running)
        rig.tick()

        assertNotNull(rig.state.rest)
        assertEquals(Mode.BALANCED.cooldownMin * MIN, rig.state.rest?.remainingMs)
    }

    // ── T22 / T23 ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun T22_secondGuardedAppStaysInsideTheSameGrant() {
        val now = Fixtures.EVENING
        val ctx = Fixtures.context(now = now, config = BOTH_APPS)
        val running = Fixtures.state(
            now = now,
            phase = Phase.GRANTED,
            foregroundGuarded = true,
            grant = Fixtures.grant(grantedMs = 5 * MIN, lastAccountedElapsed = now.elapsedMs),
        )
        val rig = Rig(initial = running, ctx = ctx)
        rig.openGuarded(pkg = Fixtures.YOUTUBE.packageName, context = ctx)

        assertEquals(Phase.GRANTED, rig.state.phase)
        assertEquals(Fixtures.YOUTUBE.packageName, rig.state.foregroundPackage)
        assertEquals(listOf("Live(GrantRunning)"), rig.tags())
    }

    @Test
    fun T23_leavingMidGrantHoldsIt() {
        val now = Fixtures.EVENING
        val running = Fixtures.state(
            now = now,
            phase = Phase.GRANTED,
            foregroundGuarded = true,
            grant = Fixtures.grant(grantedMs = 5 * MIN, lastAccountedElapsed = now.elapsedMs),
        )
        val rig = Rig(initial = running)
        rig.openGuarded(pkg = "com.example.news", at = TestTime.after(now, 1))

        assertEquals(Phase.HELD, rig.state.phase)
        assertEquals(listOf("Live(GrantHeld)"), rig.tags())
        assertEquals(TestTime.after(now, 1).elapsedMs, rig.state.grant?.heldSinceElapsed)
    }

    // ── T24 / T25 ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun T24_doneEarlyLightsTheStarAtOnce() {
        val now = Fixtures.EVENING
        val star = Fixtures.star()
        val economy = FakeStarEconomy(leaveDecision = LeaveDecision.LitNow(star))
        val running = Fixtures.state(
            now = now,
            phase = Phase.GRANTED,
            foregroundGuarded = true,
            grant = Fixtures.grant(grantedMs = 5 * MIN, consumedMs = MIN, lastAccountedElapsed = now.elapsedMs),
        )
        val rig = Rig(economy = economy, initial = running)
        rig.choose(Choice.DoneEarly)

        assertEquals(Phase.IDLE, rig.state.phase)
        assertEquals("DONE_EARLY", economy.lastLeave?.name)
        assertEquals(
            listOf("Home", "Live(None)", "Record(GrantClosed:DONE_EARLY)", "Record(StarLit)"),
            rig.tags(),
        )
        assertNull(rig.state.grant)
    }

    @Test
    fun T24_doneEarlyWhileHeldDoesNotGoHome() {
        val now = Fixtures.EVENING
        val held = Fixtures.state(
            now = now,
            phase = Phase.HELD,
            grant = Fixtures.grant(consumedMs = MIN, lastAccountedElapsed = now.elapsedMs, heldSinceElapsed = now.elapsedMs),
        )
        val rig = Rig(initial = held)
        rig.choose(Choice.DoneEarly)

        assertEquals(listOf("Live(None)", "Record(GrantClosed:DONE_EARLY)"), rig.tags())
    }

    @Test
    fun T25_pauseSavesTheStayForLater() {
        val now = Fixtures.EVENING
        val running = Fixtures.state(
            now = now,
            phase = Phase.GRANTED,
            foregroundGuarded = true,
            grant = Fixtures.grant(grantedMs = 5 * MIN, consumedMs = MIN, lastAccountedElapsed = now.elapsedMs),
        )
        val rig = Rig(initial = running)
        rig.choose(Choice.PauseGrant)

        assertEquals(Phase.PAUSED, rig.state.phase)
        assertNotNull(rig.state.grant?.pausedSinceElapsed)
        assertEquals(listOf("Home", "Live(GrantHeld)"), rig.tags())
    }

    @Test
    fun T25_pauseIsOnlyForStayGrants() {
        val now = Fixtures.EVENING
        val quick = Fixtures.state(
            now = now,
            phase = Phase.GRANTED,
            foregroundGuarded = true,
            grant = Fixtures.grant(kind = GrantKind.QUICK, grantedMs = MIN, consumedMs = 0L, lastAccountedElapsed = now.elapsedMs),
        )
        val rig = Rig(initial = quick)
        rig.choose(Choice.PauseGrant)

        assertEquals(Phase.GRANTED, rig.state.phase)
    }

    // ── T26 / T27 ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun T26_graceRunningOutClosesTheGrantAndLightsTheProof() {
        val now = Fixtures.EVENING
        val star = Fixtures.star()
        val economy = FakeStarEconomy(leaveDecision = LeaveDecision.LitNow(star))
        val held = Fixtures.state(
            now = now,
            phase = Phase.HELD,
            grant = Fixtures.grant(
                grantedMs = 10 * MIN,
                consumedMs = 2 * MIN,
                lastAccountedElapsed = now.elapsedMs,
                heldSinceElapsed = now.elapsedMs - 3 * MIN,
            ),
        )
        val rig = Rig(economy = economy, initial = held)
        rig.tick()

        assertEquals(Phase.IDLE, rig.state.phase)
        assertEquals("DONE_EARLY", economy.lastLeave?.name)
        assertEquals(
            listOf("Live(None)", "Record(GrantClosed:LEFT)", "Record(StarLit)"),
            rig.tags(),
        )
    }

    @Test
    fun T27_pausedExpiryAndDiscardBothEndTheGrant() {
        val now = Fixtures.EVENING
        val expired = Fixtures.state(
            now = now,
            phase = Phase.PAUSED,
            grant = Fixtures.grant(consumedMs = MIN, lastAccountedElapsed = now.elapsedMs, pausedSinceElapsed = now.elapsedMs - 60 * MIN),
        )
        val expiredRig = Rig(initial = expired)
        expiredRig.tick()
        assertEquals(Phase.IDLE, expiredRig.state.phase)
        assertEquals(listOf("Live(None)", "Record(GrantClosed:PAUSE_EXPIRED)"), expiredRig.tags())

        val discard = Fixtures.state(
            now = now,
            phase = Phase.PAUSED,
            grant = Fixtures.grant(consumedMs = MIN, lastAccountedElapsed = now.elapsedMs, pausedSinceElapsed = now.elapsedMs - MIN),
        )
        val discardRig = Rig(initial = discard)
        discardRig.choose(Choice.DiscardPaused)
        assertEquals(listOf("Live(None)", "Record(GrantClosed:DISCARDED)"), discardRig.tags())
    }

    // ── T28 / T29 ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun T28_startFocusPlaysTheSoundAndClosesAnyGrant() {
        val now = Fixtures.EVENING
        val held = Fixtures.state(
            now = now,
            phase = Phase.HELD,
            grant = Fixtures.grant(consumedMs = MIN, lastAccountedElapsed = now.elapsedMs, heldSinceElapsed = now.elapsedMs),
        )
        val rig = Rig(initial = held)
        rig.step(GuardInput.StartFocus(plannedMin = 25, sound = SoundKind.RAIN))

        assertEquals(Phase.IDLE, rig.state.phase)
        assertEquals(25 * MIN, rig.state.focus?.plannedMs)
        assertNull(rig.state.grant)
        assertEquals(
            listOf("Record(GrantClosed:SUPERSEDED)", "Live(FocusRunning)", "Sound(RAIN,null)"),
            rig.tags(),
        )
    }

    @Test
    fun T29_focusCompletingClosesTheSessionWithItsStars() {
        val now = Fixtures.EVENING
        val star = Fixtures.star()
        val economy = FakeStarEconomy(focusAwards = listOf(star))
        val focusing = Fixtures.state(
            now = now,
            focus = Fixtures.focus(plannedMs = 25 * MIN, elapsedMs = 24 * MIN, lastAccountedElapsed = now.elapsedMs),
        )
        val rig = Rig(economy = economy, initial = focusing)
        rig.tick(TestTime.after(now, MIN / MIN))

        assertNull(rig.state.focus)
        assertEquals(true, economy.lastFocusCompleted)
        assertEquals(
            listOf("Record(FocusClosed:true)", "Record(StarLit)", "FocusCompleted(1)", "Live(None)"),
            rig.tags(),
        )
    }

    // ── T30 / T31 ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun T30_endedProtectedWindowIsHandedToTheEconomyOnce() {
        val now = Fixtures.EVENING
        val span = Fixtures.span()
        val star = Fixtures.star()
        val economy = FakeStarEconomy(protectedAwards = listOf(star))
        val ctx = Fixtures.context(now = now, endedProtected = listOf(span))
        val state = Fixtures.state(now = now, ledger = listOf(Fixtures.ledgerEntry()))
        val rig = Rig(economy = economy, initial = state, ctx = ctx)
        rig.tick()

        assertEquals(span, economy.lastSpan)
        assertEquals(listOf("Record(StarLit)"), rig.tags())
        assertTrue(rig.state.ledger.isEmpty())
    }

    @Test
    fun T31_aNewLogicalDayRollsTheEconomy() {
        val now = Fixtures.EVENING
        val ctx = Fixtures.context(now = now, logicalDay = Fixtures.EPOCH_DAY + 1)
        val yesterday = Fixtures.state(now = now, logicalDay = Fixtures.EPOCH_DAY)
        val rig = Rig(initial = yesterday, ctx = ctx)
        rig.tick(context = ctx)

        assertTrue(rig.economy.calls.contains("rollover"))
        assertEquals(Fixtures.EPOCH_DAY + 1, rig.state.economy.logicalDay)
    }

    // ── T32 ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun T32_reconcileChargesTheBudgetAndFlagsProtectedUsage() {
        val now = Fixtures.EVENING
        val bed = Fixtures.context(
            now = now,
            window = Fixtures.window(
                kind = WindowKind.SLEEP_GATE,
                startWall = now.wallMs - 30 * MIN,
                endWall = now.wallMs + 30 * MIN,
            ),
        )
        val state = Fixtures.state(
            now = now,
            budget = Fixtures.budget(remainingMs = 10 * MIN, lastGuardedUseElapsed = now.elapsedMs),
        )
        val rig = Rig(initial = state, ctx = bed)
        rig.step(GuardInput.Reconcile(listOf(Fixtures.usageSpan(startWall = now.wallMs - 5 * MIN, endWall = now.wallMs))))

        assertEquals(5 * MIN, rig.state.budget.remainingMs)
        assertEquals(1, rig.state.ledger.size)
        assertTrue(rig.state.ledger.single().reconciledUsage)
        assertTrue(rig.effects.isEmpty())
    }

    // ── T33a / T33b ───────────────────────────────────────────────────────────────────────────

    @Test
    fun T33a_selfTestArmsWithoutASurface() {
        val rig = Rig()
        rig.step(GuardInput.SelfTest("com.example.news", "health"))

        assertEquals("com.example.news", rig.state.testArm?.packageName)
        assertEquals("health", rig.state.testArm?.returnRoute)
        assertEquals(Phase.IDLE, rig.state.phase)
        assertTrue(rig.effects.isEmpty())
    }

    @Test
    fun T33b_armedSelfTestShowsTheTestSurfaceForAnyApp() {
        val rig = Rig()
        rig.step(GuardInput.SelfTest("com.example.news", "health"))
        rig.openGuarded(pkg = "com.example.news")

        assertEquals(Phase.PROMPTING, rig.state.phase)
        assertEquals(SurfaceKind.TEST, rig.state.prompt?.kind)
        assertEquals(true, rig.ui().testMode)
        assertNull(rig.state.testArm)
        assertEquals(listOf("Duck", "Show(TEST)"), rig.tags())

        rig.choose(Choice.Leave)
        assertEquals(listOf("Dismiss", "OpenSadiq(health)", "Release", "Record(Open:LEFT)"), rig.tags())
        assertTrue(rig.economy.calls.none { it == "onLeave" })
    }

    // ── T34 / T35 ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun T34_aMatureKindleLights() {
        val now = Fixtures.EVENING
        val star = Fixtures.star()
        val economy = FakeStarEconomy(kindleAward = star)
        val state = Fixtures.state(
            now = now,
            kindles = listOf(Fixtures.kindle(confirmAtElapsed = now.elapsedMs - 1)),
        )
        val rig = Rig(economy = economy, initial = state)
        rig.tick()

        assertTrue(economy.calls.contains("confirmKindle"))
        assertTrue(rig.state.kindles.isEmpty())
        assertEquals(listOf("Record(StarLit)"), rig.tags())
    }

    @Test
    fun T35_aGuardedOpenFadesAPendingKindleSilently() {
        val now = Fixtures.EVENING
        val economy = FakeStarEconomy()
        val state = Fixtures.state(
            now = now,
            kindles = listOf(Fixtures.kindle(confirmAtElapsed = now.elapsedMs + 2 * MIN)),
        )
        val rig = Rig(economy = economy, initial = state)
        rig.openGuarded()

        assertTrue(rig.state.kindles.isEmpty())
        assertTrue(economy.calls.none { it == "confirmKindle" })
        assertEquals(SurfaceKind.PAUSE, rig.state.prompt?.kind)
    }

    // ── T36 / T37 / T37x / T21 ────────────────────────────────────────────────────────────────

    /**
     * The door exists on the wall that has no yes of its own: a rest, with free scroll already gone.
     * Arming switches the surface to its confirm view and starts the wait, and that is all it does.
     */
    @Test
    fun T36_arm_on_cooldown_without_free_scroll() {
        val rig = cooldownRig()
        val entered = rig.ui()
        assertEquals(SurfaceKind.COOLDOWN, entered.kind)
        assertEquals(EngineRules.OVERRIDE_DAY_CAP, entered.overrideLeft)
        assertEquals(EngineRules.OVERRIDE_WAIT_MS, entered.overrideWaitMs)
        assertNull(entered.overrideArmedElapsed)
        assertNull(entered.overrideStarsAtStake)
        assertNull(entered.overrideBackAtWall)

        val before = rig.state.economy
        val armedAt = rig.now
        rig.choose(Choice.ArmOverride)

        assertEquals(listOf("Update(COOLDOWN)"), rig.tags())
        assertEquals(armedAt.elapsedMs, rig.state.prompt?.overrideArmedElapsed)
        assertEquals(armedAt.elapsedMs, rig.update().overrideArmedElapsed)
        assertEquals(Phase.PROMPTING, rig.state.phase)
        // Arming is not opening: no grant, no record, no economy call, no cap (T36).
        assertNull(rig.state.grant)
        assertEquals(before, rig.state.economy)
        assertTrue(rig.effects.none { it is Effect.Record })
    }

    /** Free scroll is the earned way out and is always easier, so the door never competes with it. */
    @Test
    fun T36_ignored_when_free_scroll_is_offered() {
        val rig = cooldownRig(freeScrollMs = 15 * MIN)
        assertEquals(SurfaceKind.COOLDOWN, rig.state.prompt?.kind)
        assertTrue(rig.ui().freeScrollOptionsMin.isNotEmpty())
        assertNull(rig.ui().overrideLeft)

        rig.choose(Choice.ArmOverride)
        assertTrue(rig.effects.isEmpty())
        assertNull(rig.state.prompt?.overrideArmedElapsed)
    }

    /** "Yes, I need it" is already the honest yes; a second one next to it would make it pointless. */
    @Test
    fun T36_ignored_on_PAUSE() {
        val rig = Rig()
        rig.openGuarded()
        assertEquals(SurfaceKind.PAUSE, rig.state.prompt?.kind)
        assertNull(rig.ui().overrideLeft)

        rig.choose(Choice.ArmOverride)
        assertTrue(rig.effects.isEmpty())
        assertNull(rig.state.prompt?.overrideArmedElapsed)
    }

    @Test
    fun T36_ignored_on_REFLECT() {
        val rig = Rig(reflection = FakeReflectionPolicy(reflect = true))
        rig.openGuarded()
        assertEquals(SurfaceKind.REFLECT, rig.state.prompt?.kind)
        assertNull(rig.ui().overrideLeft)

        rig.choose(Choice.ArmOverride)
        assertTrue(rig.effects.isEmpty())
        assertNull(rig.state.prompt?.overrideArmedElapsed)
    }

    /** Never at prayer time: there is nothing to get past, and Stay is always offered (§A.4). */
    @Test
    fun T36_ignored_on_PRAYER() {
        val now = Fixtures.EVENING
        val prayer = Fixtures.context(now = now, window = Fixtures.maghribWindow(now = now.wallMs))
        val rig = Rig(now = now, ctx = prayer)
        rig.openGuarded(context = prayer)
        assertEquals(SurfaceKind.PRAYER, rig.state.prompt?.kind)
        assertNull(rig.ui().overrideLeft)

        rig.choose(Choice.ArmOverride, context = prayer)
        assertTrue(rig.effects.isEmpty())
        assertNull(rig.state.prompt?.overrideArmedElapsed)
    }

    /** "Hold for 2 minutes" is always there in the morning, so the door is not. */
    @Test
    fun T36_ignored_on_MORNING() {
        val now = Fixtures.EVENING
        val morning = Fixtures.context(
            now = now,
            window = Fixtures.window(
                kind = WindowKind.MORNING,
                startWall = now.wallMs - 10 * MIN,
                endWall = now.wallMs + 20 * MIN,
            ),
        )
        val rig = Rig(now = now, ctx = morning)
        rig.openGuarded(context = morning)
        assertEquals(SurfaceKind.MORNING, rig.state.prompt?.kind)
        assertNull(rig.ui().overrideLeft)

        rig.choose(Choice.ArmOverride, context = morning)
        assertTrue(rig.effects.isEmpty())
        assertNull(rig.state.prompt?.overrideArmedElapsed)
    }

    /** "End focus" is one tap. A door under it would pay focus stars for scrolling. */
    @Test
    fun T36_ignored_on_FOCUS_GUARD() {
        val now = Fixtures.EVENING
        val focusing = Fixtures.state(now = now, focus = Fixtures.focus(lastAccountedElapsed = now.elapsedMs))
        val rig = Rig(now = now, initial = focusing)
        rig.openGuarded()
        assertEquals(SurfaceKind.FOCUS_GUARD, rig.state.prompt?.kind)
        assertNull(rig.ui().overrideLeft)

        rig.choose(Choice.ArmOverride)
        assertTrue(rig.effects.isEmpty())
        assertNull(rig.state.prompt?.overrideArmedElapsed)
    }

    /** The self-test proves the interception, not the door. */
    @Test
    fun T36_ignored_on_TEST() {
        val rig = Rig()
        rig.step(GuardInput.SelfTest("com.example.news", "health"))
        rig.openGuarded(pkg = "com.example.news")
        assertEquals(SurfaceKind.TEST, rig.state.prompt?.kind)
        assertNull(rig.ui().overrideLeft)

        rig.choose(Choice.ArmOverride)
        assertTrue(rig.effects.isEmpty())
        assertNull(rig.state.prompt?.overrideArmedElapsed)
    }

    /** A rest inside the morning window has the door, and it states the two morning stars (§A.3). */
    @Test
    fun T36_morning_rest_states_the_two_morning_stars() {
        val now = TestTime.at("2026-10-30T06:40")
        val morning = Fixtures.context(
            now = now,
            window = Fixtures.window(
                kind = WindowKind.MORNING,
                startWall = TestTime.at("2026-10-30T05:41").wallMs,
                endWall = TestTime.at("2026-10-30T09:30").wallMs,
            ),
        )
        val rig = cooldownRig(now = now, ctx = morning)

        assertEquals(SurfaceKind.COOLDOWN, rig.ui().kind)
        assertEquals(EconomyRules.PROTECTED_MORNING_STARS, rig.ui().overrideStarsAtStake)
        assertEquals(EngineRules.OVERRIDE_WAIT_MS, rig.ui().overrideWaitMs)

        // It is a grant inside the morning, so the ledger records it and the two stars are gone.
        rig.openAnyway()
        assertEquals(1, rig.state.ledger.single { it.kind == WindowKind.MORNING }.grants)
        assertEquals(
            0,
            rig.state.ledger.single { it.kind == WindowKind.MORNING }.overrides,
            "the morning has no cap of its own",
        )
    }

    /** T37: thirty seconds of waiting, and then the ten minutes. */
    @Test
    fun T37_opens_after_30s() {
        val rig = cooldownRig()
        val opened = rig.openAnyway(ReflectionReason.TIRED)

        assertEquals(Phase.GRANTED, rig.state.phase)
        val grant = rig.state.grant
        assertNotNull(grant)
        assertEquals(GrantKind.OVERRIDE, grant.kind)
        assertEquals(EngineRules.OVERRIDE_MS, grant.grantedMs)
        assertEquals(
            listOf(
                "Dismiss",
                "Release",
                "Live(GrantRunning)",
                "Record(Open:OVERRIDE)",
                "Record(Reflected)",
            ),
            rig.tags(),
        )
        assertEquals(1, opened.economy.overridesUsed)

        // The record carries the surface, the grant and a decision time that includes the wait (A.7).
        val open = rig.effects.filterIsInstance<Effect.Record>()
            .map { it.event }
            .filterIsInstance<RecordEvent.Open>()
            .single()
        assertEquals(OpenOutcome.OVERRIDE, open.outcome)
        assertEquals(SurfaceKind.COOLDOWN, open.surface)
        assertEquals(grant.id, open.grantId)
        assertEquals(EngineRules.OVERRIDE_WAIT_MS, open.decisionMs)

        val reflected = rig.effects.filterIsInstance<Effect.Record>()
            .map { it.event }
            .filterIsInstance<RecordEvent.Reflected>()
            .single()
        assertEquals(ReflectionReason.TIRED, reflected.reason)
        assertEquals(Fixtures.INSTAGRAM.packageName, reflected.packageName)
    }

    /** T37 at Bedtime: the slowest ladder, the rarest cap, and the three stars named in numbers. */
    @Test
    fun T37_bedtime_needs_60s() {
        val gateStart = TestTime.at("2026-10-29T22:30").wallMs
        val gateEnd = TestTime.at("2026-10-30T05:21").wallMs
        val now = TestTime.at("2026-10-29T23:10")
        val gate = Fixtures.context(
            now = now,
            window = Fixtures.window(kind = WindowKind.SLEEP_GATE, startWall = gateStart, endWall = gateEnd),
        )
        val rig = Rig(now = now, ctx = gate)
        rig.openGuarded(context = gate)

        val door = rig.ui()
        assertEquals(SurfaceKind.SLEEP_GATE, door.kind)
        assertEquals(EngineRules.OVERRIDE_NIGHT_CAP, door.overrideLeft)
        assertEquals(EngineRules.OVERRIDE_WAIT_NIGHT_MS, door.overrideWaitMs)
        assertEquals(EconomyRules.QUIET_NIGHT_STARS, door.overrideStarsAtStake)

        rig.choose(Choice.ArmOverride, context = gate)
        // 59 s is not 60: the night door does not open a second early.
        rig.choose(Choice.Override(ReflectionReason.TIRED), now.afterMs(59_000), context = gate)
        assertEquals(Phase.PROMPTING, rig.state.phase)
        assertNull(rig.state.grant)

        rig.choose(Choice.Override(ReflectionReason.TIRED), now.afterMs(60_000), context = gate)
        assertEquals(Phase.GRANTED, rig.state.phase)
        assertEquals(GrantKind.OVERRIDE, rig.state.grant?.kind)
        assertEquals(EngineRules.OVERRIDE_MS, rig.state.grant?.grantedMs)

        val row = rig.state.ledger.single { it.kind == WindowKind.SLEEP_GATE }
        assertEquals(1, row.grants)
        assertEquals(1, row.overrides)
        assertEquals(1, rig.state.economy.overridesUsed)
    }

    @Test
    fun T37_rejected_at_29s() {
        val rig = cooldownRig()
        val armedAt = rig.now
        rig.choose(Choice.ArmOverride)
        rig.choose(Choice.Override(ReflectionReason.BORED), armedAt.afterMs(29_000))

        assertEquals(Phase.PROMPTING, rig.state.phase)
        assertNull(rig.state.grant)
        assertTrue(rig.effects.isEmpty(), "a disabled button is not a corrected screen")
        assertEquals(0, rig.state.economy.overridesUsed)
        assertEquals(armedAt.elapsedMs, rig.state.prompt?.overrideArmedElapsed, "the wait keeps running")
    }

    @Test
    fun T37_rejected_unarmed() {
        val rig = cooldownRig()
        rig.choose(Choice.Override(ReflectionReason.BORED), rig.now.afterMs(5 * MIN))

        assertEquals(Phase.PROMPTING, rig.state.phase)
        assertNull(rig.state.grant)
        assertTrue(rig.effects.isEmpty())
        assertEquals(0, rig.state.economy.overridesUsed)
    }

    /**
     * The adhan lands mid-wait. The surface becomes the prayer surface and the arm goes with it, so a
     * hold that arrives at the same instant opens nothing and is not a second interception either.
     */
    @Test
    fun T37_rejected_when_adhan_arrives_while_armed() {
        val rig = cooldownRig()
        val armedAt = rig.now
        rig.choose(Choice.ArmOverride)
        assertEquals(armedAt.elapsedMs, rig.state.prompt?.overrideArmedElapsed)

        val at = armedAt.afterMs(EngineRules.OVERRIDE_WAIT_MS)
        val prayer = Fixtures.context(now = at, window = Fixtures.maghribWindow(now = at.wallMs))
        rig.choose(Choice.Override(ReflectionReason.TIRED), at, context = prayer)

        assertEquals(SurfaceKind.PRAYER, rig.state.prompt?.kind)
        assertNull(rig.state.prompt?.overrideArmedElapsed)
        assertNull(rig.state.grant)
        assertEquals(Phase.PROMPTING, rig.state.phase)
        assertEquals(0, rig.state.economy.overridesUsed)
        assertEquals(listOf("Update(PRAYER)"), rig.tags())
    }

    /** The amend T21: an override that runs out comes back as a Time's up that names it. */
    @Test
    fun T21_anOverrideGrantExpiresIntoATimeUpThatNamesIt() {
        val rig = cooldownRig()
        val openedAt = rig.now.afterMs(EngineRules.OVERRIDE_WAIT_MS)
        rig.openAnyway()
        rig.tick(openedAt.afterMs(EngineRules.OVERRIDE_MS))

        assertEquals(SurfaceKind.TIME_UP, rig.state.prompt?.kind)
        assertEquals(
            listOf("Duck", "Show(TIME_UP)", "Live(None)", "Record(GrantClosed:EXPIRED)"),
            rig.tags(),
        )
        val ui = rig.ui()
        assertEquals(GrantKind.OVERRIDE, ui.timeUpKind)
        assertTrue(ui.stayOptionsMin.isEmpty(), "an override grants no extra minutes")
        assertFalse(ui.leaveEarnsStar, "the Time's up after an override lights nothing")
        // The door appears again only if a cap allows it — one of the two is gone.
        assertEquals(1, ui.overrideLeft)

        rig.choose(Choice.Leave)
        assertEquals("KEPT_WORD", rig.economy.lastLeave?.name)
        assertEquals(Phase.IDLE, rig.state.phase)
    }

    // ── A4-04 ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Rule zero at the end of a Stay: the TIME_UP that lands inside a prayer window keeps its answer,
     * and the reducer accepts exactly the chips the surface showed. A chip above the prayer cap is not
     * a chip the surface offers, so it must be refused rather than granted.
     */
    @Test
    fun A4_04_aTimeUpInAPrayerWindowStillStaysWithinThePrayerCap() {
        val now = Fixtures.EVENING
        val prayer = Fixtures.context(now = now, window = Fixtures.maghribWindow(now = now.wallMs))
        val spent = Fixtures.state(
            now = now,
            phase = Phase.GRANTED,
            foregroundPackage = Fixtures.INSTAGRAM.packageName,
            foregroundGuarded = true,
            grant = Fixtures.grant(kind = GrantKind.STAY, grantedMs = 5 * MIN, consumedMs = 5 * MIN),
            budget = Fixtures.budget(remainingMs = 10 * MIN),
        )
        val rig = Rig(initial = spent)

        rig.tick(at = now, context = prayer)
        assertEquals(SurfaceKind.TIME_UP, rig.state.prompt?.kind)
        assertEquals(listOf(2, 5), rig.ui().stayOptionsMin)

        rig.choose(Choice.Stay(15), at = now, context = prayer)
        assertEquals(Phase.PROMPTING, rig.state.phase, "a chip above the prayer cap must be refused")
        assertTrue(rig.effects.isEmpty())

        rig.choose(Choice.Stay(5), at = now, context = prayer)
        assertEquals(Phase.GRANTED, rig.state.phase)
        assertEquals(GrantKind.STAY, rig.state.grant?.kind)
        assertEquals(EngineRules.PRAYER_STAY_MAX_MIN * MIN, rig.state.grant?.grantedMs)
    }

    // ── A4-09 ─────────────────────────────────────────────────────────────────────────────────

    /**
     * E1 excludes FOCUS_GUARD, and the surface says so (`leaveEarnsStar` is false there). Back,
     * gesture and screen-off are three different doors into the same leave, so all three are run
     * against the real economy, where the star is observed where it actually lands: `litLeave` after
     * the kindle's three quiet minutes. A normal prompt keeps paying on those same three doors.
     */
    @Test
    fun A4_09_everyExitFromTheFocusSurfaceLeavesTheStarUnlit() {
        for (exit in Exit.entries) {
            val paid = leaveAndWait(SurfaceKind.FOCUS_GUARD, exit)
            assertEquals(0, paid.economy.litLeave, "leaving FOCUS_GUARD by $exit must pay nothing")
            assertTrue(paid.kindles.isEmpty(), "leaving FOCUS_GUARD by $exit must not wait on a kindle")
        }
        for (exit in Exit.entries) {
            val paid = leaveAndWait(SurfaceKind.PAUSE, exit)
            assertEquals(1, paid.economy.litLeave, "leaving PAUSE by $exit still earns its star")
        }
    }

    /** The three ways a surface can be left without answering it. */
    private enum class Exit { BACK, GESTURE, SCREEN_OFF }

    /**
     * Shows [surface], leaves it by [exit], then waits out the kindle.
     *
     * @return the state after the three quiet minutes, so the caller can read `economy.litLeave`.
     */
    private fun leaveAndWait(surface: SurfaceKind, exit: Exit): EngineState {
        val now = Fixtures.EVENING
        val ctx = Fixtures.context(now = now)
        val engine = GuardEngine(DefaultStarEconomy(), FakeReflectionPolicy(), FakeSuggester())
        val focus = if (surface == SurfaceKind.FOCUS_GUARD) {
            Fixtures.focus(plannedMs = 25 * MIN, lastAccountedElapsed = now.elapsedMs)
        } else {
            null
        }
        var state = Fixtures.state(now = now, focus = focus)
        state = engine.reduce(state, GuardInput.ForegroundChanged(Fixtures.INSTAGRAM.packageName), now, ctx).state
        assertEquals(surface, state.prompt?.kind, "the setup must actually show $surface")

        val leaveAt = TestTime.after(now, 1)
        val leaving = when (exit) {
            Exit.BACK -> GuardInput.Chose(Choice.Leave)
            Exit.GESTURE -> GuardInput.ForegroundChanged("com.example.news")
            Exit.SCREEN_OFF -> GuardInput.ScreenOff
        }
        state = engine.reduce(state, leaving, leaveAt, ctx).state
        assertEquals(Phase.IDLE, state.phase, "the $exit leave must end the surface")

        return engine.reduce(state, GuardInput.Tick, TestTime.after(leaveAt, 4), ctx).state
    }
}

/** One minute in milliseconds, for readable state fixtures. */
internal const val MIN: Long = 60_000L

/**
 * The same clocks advanced by [ms] milliseconds.
 *
 * [TestTime.after] moves in minutes, and the door's wait is measured in seconds: 29 s must fail and
 * 30 s must open, so the tests need a clock that can be moved by a fraction of a minute.
 */
internal fun TimeSnapshot.afterMs(ms: Long): TimeSnapshot =
    copy(wallMs = wallMs + ms, elapsedMs = elapsedMs + ms)

/**
 * A rig standing on the daytime wall of §A.1: a rest, with free scroll already spent.
 *
 * The rest is long enough that a grant or two cannot end it, so the surface stays COOLDOWN for the
 * whole test; the budget is at zero because that is what a running rest means.
 *
 * @param now the clocks.
 * @param ctx the context, when the test is inside a window.
 * @param freeScrollMs the free-scroll balance the fake economy reports.
 * @param restMs how much rest is left.
 */
internal fun cooldownRig(
    now: TimeSnapshot = Fixtures.EVENING,
    ctx: GuardContext = Fixtures.context(now = now),
    freeScrollMs: Long = 0L,
    restMs: Long = 120 * MIN,
): Rig {
    val resting = Fixtures.state(
        now = now,
        rest = Fixtures.rest(remainingMs = restMs, lastAccountedElapsed = now.elapsedMs),
        budget = Fixtures.budget(remainingMs = 0L, lastGuardedUseElapsed = now.elapsedMs),
    )
    val rig = Rig(economy = FakeStarEconomy(freeScrollMs = freeScrollMs), now = now, ctx = ctx, initial = resting)
    rig.openGuarded(at = now, context = ctx)
    return rig
}

/**
 * Arms the door and completes the hold once the wait the surface asked for has passed.
 *
 * @param reason the chip the confirm view sends with the hold.
 */
internal fun Rig.openAnyway(reason: ReflectionReason = ReflectionReason.TIRED): EngineState {
    choose(Choice.ArmOverride)
    val wait = update().overrideWaitMs ?: error("the door was not offered on ${state.prompt?.kind}")
    return choose(Choice.Override(reason), now.afterMs(wait))
}

/** A config with both fixture apps guarded, for the two-app transitions (T18, T22). */
private val BOTH_APPS: com.ramish.sadiq.core.model.GuardConfig =
    Fixtures.config(apps = listOf(Fixtures.INSTAGRAM, Fixtures.YOUTUBE))

/**
 * The shared driver for every engine test: a fake economy, reflection policy and suggester, a clock
 * that only moves when a test says so, and the last effect list.
 *
 * It lives in this file because WP-06 owns exactly six test files; the suites in this package share it.
 *
 * @property economy the economy fake, inspected through its `calls` list.
 * @property reflection the reflection fake, whose `reflect` flag decides check-ins.
 * @property suggester the suggester fake.
 * @property now the clock; move it with [step]'s `at`.
 * @property ctx the context; replace it with [step]'s `context`.
 */
internal class Rig(
    val economy: FakeStarEconomy = FakeStarEconomy(),
    val reflection: FakeReflectionPolicy = FakeReflectionPolicy(),
    val suggester: FakeSuggester = FakeSuggester(),
    var now: TimeSnapshot = Fixtures.EVENING,
    var ctx: GuardContext = Fixtures.context(now = now),
    initial: EngineState? = null,
) {
    val engine: GuardEngine = GuardEngine(economy, reflection, suggester)

    var state: EngineState = initial ?: engine.initial(now, ctx)
    var effects: List<Effect> = emptyList()

    fun step(
        input: GuardInput,
        at: TimeSnapshot = now,
        context: GuardContext = ctx,
    ): EngineState {
        now = at
        ctx = context
        val transition = engine.reduce(state, input, at, context)
        state = transition.state
        effects = transition.effects
        return state
    }

    fun tick(at: TimeSnapshot = now, context: GuardContext = ctx): EngineState =
        step(GuardInput.Tick, at, context)

    fun openGuarded(
        pkg: String = Fixtures.INSTAGRAM.packageName,
        domain: String? = null,
        surface: AppSurface = AppSurface.APP,
        at: TimeSnapshot = now,
        context: GuardContext = ctx,
    ): EngineState = step(GuardInput.ForegroundChanged(pkg, domain, surface), at, context)

    fun choose(choice: Choice, at: TimeSnapshot = now, context: GuardContext = ctx): EngineState =
        step(GuardInput.Chose(choice), at, context)

    fun ui(): InterceptUi = effects.filterIsInstance<Effect.ShowSurface>().last().ui

    fun update(): InterceptUi = effects.filterIsInstance<Effect.UpdateSurface>().last().ui

    fun tags(): List<String> = effects.map { it.tag() }
}

/** The compact shape of an effect, for order assertions that read like the §3.6 row. */
internal fun Effect.tag(): String = when (this) {
    is Effect.ShowSurface -> "Show(${ui.kind})"
    is Effect.UpdateSurface -> "Update(${ui.kind})"
    is Effect.DismissSurface -> "Dismiss"
    is Effect.GoHome -> "Home"
    is Effect.OpenSadiq -> "OpenSadiq($route)"
    is Effect.DuckMedia -> "Duck"
    is Effect.ReleaseMedia -> "Release"
    is Effect.Live -> "Live(${state::class.simpleName})"
    is Effect.PlaySound -> "Sound($kind,$durationMs)"
    is Effect.StopSound -> "StopSound"
    is Effect.FocusCompleted -> "FocusCompleted($stars)"
    is Effect.Record -> "Record(${event.tag()})"
}

private fun RecordEvent.tag(): String = when (this) {
    is RecordEvent.Open -> "Open:$outcome"
    is RecordEvent.GrantClosed -> "GrantClosed:$end"
    is RecordEvent.StarLit -> "StarLit"
    is RecordEvent.FocusClosed -> "FocusClosed:$completed"
    is RecordEvent.Reflected -> "Reflected"
}
