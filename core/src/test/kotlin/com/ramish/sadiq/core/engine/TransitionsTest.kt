package com.ramish.sadiq.core.engine

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
            listOf("Record(FocusClosed:false)", "Record(StarLit)", "Update(PAUSE)"),
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
            listOf("Record(FocusClosed:true)", "Record(StarLit)", "FocusCompleted(1)"),
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
}

/** One minute in milliseconds, for readable state fixtures. */
internal const val MIN: Long = 60_000L

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
