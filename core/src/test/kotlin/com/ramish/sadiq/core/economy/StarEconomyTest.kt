package com.ramish.sadiq.core.economy

import com.ramish.sadiq.core.engine.EngineRules
import com.ramish.sadiq.core.engine.FocusState
import com.ramish.sadiq.core.engine.Grant
import com.ramish.sadiq.core.engine.GrantKind
import com.ramish.sadiq.core.engine.Kindle
import com.ramish.sadiq.core.engine.LedgerEntry
import com.ramish.sadiq.core.model.ProtectedSpan
import com.ramish.sadiq.core.model.WindowKind
import com.ramish.sadiq.core.sound.SoundKind
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The earning rules of §3.8 one by one — E1–E9, every cap, and every anti-gaming rule — driven through
 * the public [StarEconomy] interface with hand-built state and fixed wall clocks.
 *
 * Times are real Instants in a fixed zone so the numbers in the tests read like the spec's examples
 * (21:10, 21:13 …) while staying completely deterministic: nothing here reads a clock.
 */
class StarEconomyTest {

    private val economy: DefaultStarEconomy = DefaultStarEconomy()

    // ── E1 LEAVE ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun e1_aPromptLeaveHoldsTheStarForThreeMinutesThenLightsIt() {
        val start: EconomyDay = freshDay()

        val (afterLeave, decision) = economy.onLeave(
            day = start,
            leave = LeaveKind.LEFT_PROMPT,
            window = WindowKind.NONE,
            nowWall = at(21, 10),
            pendingKindles = 0,
            grant = null,
        )

        assertEquals(LeaveDecision.Kindle(180_000L), decision, "E1 waits three minutes before lighting")
        assertEquals(start, afterLeave, "a kindle changes nothing until it lights")

        val (afterConfirm, award) = economy.confirmKindle(
            day = afterLeave,
            kindle = kindle(LeaveKind.LEFT_PROMPT, id = "k1"),
            nowWall = at(21, 13),
            logicalDay = DAY,
        )

        val lit: StarAward = assertNotNull(award)
        assertEquals(StarKind.LEAVE, lit.kind)
        assertEquals(at(21, 13), lit.litWall)
        assertEquals(DAY, lit.logicalDay)
        assertEquals(WindowKind.NONE, lit.window)
        assertEquals(PKG, lit.sourcePackage)
        assertEquals("k1", lit.sourceRef)
        assertEquals(1, afterConfirm.litLeave)
        assertEquals(1, afterConfirm.litTotal)
        assertEquals(at(21, 13), afterConfirm.lastLeaveLitWall)
    }

    @Test
    fun e1_aGestureAwayOrAScreenOffLeavesForTheSameStar() {
        for (leave in listOf(LeaveKind.LEFT_GESTURE, LeaveKind.SCREEN_OFF)) {
            val (_, decision) = economy.onLeave(
                freshDay(), leave, WindowKind.NONE, at(22, 5), 0, null,
            )
            assertEquals(
                LeaveDecision.Kindle(180_000L),
                decision,
                "$leave leaves for the E1 star",
            )
        }
    }

    @Test
    fun e1_noLeaveStarInsideTheSleepGate() {
        val start: EconomyDay = freshDay()
        val (after, decision) = economy.onLeave(
            start, LeaveKind.LEFT_PROMPT, WindowKind.SLEEP_GATE, at(2, 0), 0, null,
        )

        assertEquals(NoStarReason.SLEEP_GATE, assertIs<LeaveDecision.None>(decision).reason)
        assertEquals(start, after)
        assertFalse(economy.peekLeave(start, WindowKind.SLEEP_GATE, at(2, 0), 0))
    }

    @Test
    fun e1_leavingAFreeScrollGrantEarnsNothing() {
        val (_, decision) = economy.onLeave(
            freshDay(),
            LeaveKind.LEFT_PROMPT,
            WindowKind.NONE,
            at(21, 10),
            0,
            grant(GrantKind.FREE, grantedMs = 5 * MINUTE, consumedMs = MINUTE),
        )
        assertEquals(NoStarReason.NOT_ELIGIBLE, assertIs<LeaveDecision.None>(decision).reason)
    }

    // ── E2 KEPT_WORD ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun e2_givingBackAStayLightsTheKeptWordStar() {
        val stay: Grant = grant(GrantKind.STAY, grantedMs = 5 * MINUTE, consumedMs = 5 * MINUTE)

        val (afterLeave, decision) = economy.onLeave(
            freshDay(), LeaveKind.KEPT_WORD, WindowKind.NONE, at(21, 46), 0, stay,
        )
        assertEquals(LeaveDecision.Kindle(180_000L), decision)

        val (afterConfirm, award) = economy.confirmKindle(
            afterLeave, kindle(LeaveKind.KEPT_WORD, id = "k2"), at(21, 49), DAY,
        )
        assertEquals(StarKind.KEPT_WORD, assertNotNull(award).kind)
        assertEquals(1, afterConfirm.litLeave)
    }

    @Test
    fun e2_keptWordNeedsAPlainStayGrant() {
        val cases: List<Grant?> = listOf(
            null,
            grant(GrantKind.FREE, grantedMs = 5 * MINUTE),
            grant(GrantKind.PRAYER_STAY, grantedMs = 5 * MINUTE),
            grant(GrantKind.QUICK, grantedMs = MINUTE),
        )
        for (case in cases) {
            val (_, decision) = economy.onLeave(
                freshDay(), LeaveKind.KEPT_WORD, WindowKind.NONE, at(21, 46), 0, case,
            )
            assertEquals(
                NoStarReason.NOT_ELIGIBLE,
                assertIs<LeaveDecision.None>(decision).reason,
                "KEPT_WORD only closes a STAY grant, got ${case?.kind}",
            )
        }
    }

    @Test
    fun E2_never_for_override() {
        // Giving back the ten discouraged minutes is not a kept word: the door was already discouraged,
        // and its grant is not the promise E2 pays for (§A.3).
        val override: Grant = overrideGrant()
        val start: EconomyDay = freshDay()

        val (after, decision) = economy.onLeave(
            start, LeaveKind.KEPT_WORD, WindowKind.NONE, at(15, 0), 0, override,
        )
        assertEquals(NoStarReason.NOT_ELIGIBLE, assertIs<LeaveDecision.None>(decision).reason)
        assertEquals(start, after, "a refused leave touches nothing")
        assertEquals(0, after.litLeave)

        // The only difference from a paid leave is the grant's kind, so a STAY of the same shape pays.
        val stay: Grant = grant(GrantKind.STAY, grantedMs = 10 * MINUTE, consumedMs = 10 * MINUTE)
        assertIs<LeaveDecision.Kindle>(
            economy.onLeave(freshDay(), LeaveKind.KEPT_WORD, WindowKind.NONE, at(15, 0), 0, stay).second,
        )
    }

    // ── E3 DONE_EARLY ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun e3_doneEarlyLightsAtOnceWithHalfTheStayLeft() {
        val stay: Grant = grant(GrantKind.STAY, grantedMs = 10 * MINUTE, consumedMs = 5 * MINUTE)

        val (after, decision) = economy.onLeave(
            freshDay(), LeaveKind.DONE_EARLY, WindowKind.NONE, at(21, 15), 0, stay,
        )

        val award: StarAward = assertIs<LeaveDecision.LitNow>(decision).award
        assertEquals(StarKind.DONE_EARLY, award.kind)
        assertEquals(at(21, 15), award.litWall)
        assertEquals(1, after.litLeave)
        assertEquals(1, after.litTotal)
        assertEquals(at(21, 15), after.lastLeaveLitWall)
    }

    @Test
    fun e3_doneEarlyIsRefusedWhenTooMuchOfTheStayWasUsed() {
        val stay: Grant = grant(GrantKind.STAY, grantedMs = 10 * MINUTE, consumedMs = 6 * MINUTE)
        val (_, decision) = economy.onLeave(
            freshDay(), LeaveKind.DONE_EARLY, WindowKind.NONE, at(21, 15), 0, stay,
        )
        assertEquals(NoStarReason.NOT_ELIGIBLE, assertIs<LeaveDecision.None>(decision).reason)
    }

    @Test
    fun e3_theTwoMinuteFloorProtectsShortStays() {
        val exactlyTwoLeft: Grant = grant(GrantKind.STAY, grantedMs = 3 * MINUTE, consumedMs = MINUTE)
        val tooShort: Grant = grant(GrantKind.STAY, grantedMs = 3 * MINUTE, consumedMs = MINUTE + 30_000L)

        assertIs<LeaveDecision.LitNow>(
            economy.onLeave(
                freshDay(), LeaveKind.DONE_EARLY, WindowKind.NONE, at(21, 15), 0, exactlyTwoLeft,
            ).second,
        )
        assertEquals(
            NoStarReason.NOT_ELIGIBLE,
            assertIs<LeaveDecision.None>(
                economy.onLeave(
                    freshDay(), LeaveKind.DONE_EARLY, WindowKind.NONE, at(21, 15), 0, tooShort,
                ).second,
            ).reason,
        )
    }

    @Test
    fun e3_doneEarlyNeedsAStayGrant() {
        for (case in listOf<Grant?>(null, grant(GrantKind.FREE, grantedMs = 10 * MINUTE))) {
            val (_, decision) = economy.onLeave(
                freshDay(), LeaveKind.DONE_EARLY, WindowKind.NONE, at(21, 15), 0, case,
            )
            assertEquals(NoStarReason.NOT_ELIGIBLE, assertIs<LeaveDecision.None>(decision).reason)
        }
    }

    @Test
    fun E3_never_for_override() {
        // "Open anyway" then "I'm done" is the farming route A.3 rules out: the remainder on an OVERRIDE
        // grant is real, but E3 only ever pays out of a STAY, so the door cannot mint a star.
        val override: Grant = overrideGrant(consumedMs = MINUTE)
        val start: EconomyDay = freshDay()

        val (after, decision) = economy.onLeave(
            start, LeaveKind.DONE_EARLY, WindowKind.NONE, at(15, 0), 0, override,
        )
        assertEquals(NoStarReason.NOT_ELIGIBLE, assertIs<LeaveDecision.None>(decision).reason)
        assertEquals(start, after, "no star, no lit count, no leave watermark")
        assertEquals(0, after.litTotal)

        // The same remainder on a STAY lights at once, so the grant kind is the whole of the refusal.
        val stay: Grant = grant(GrantKind.STAY, grantedMs = 10 * MINUTE, consumedMs = MINUTE)
        assertEquals(
            StarKind.DONE_EARLY,
            assertIs<LeaveDecision.LitNow>(
                economy.onLeave(freshDay(), LeaveKind.DONE_EARLY, WindowKind.NONE, at(15, 0), 0, stay).second,
            ).award.kind,
        )
    }

    // ── E4 / E5 / E6 FOCUS ──────────────────────────────────────────────────────────────────────────

    @Test
    fun e4_focusLightsItsFirstStarAtFiveMinutes() {
        val (afterFive, awards) = economy.onFocusEnded(
            freshDay(), focus(plannedMs = 45 * MINUTE, elapsedMs = 5 * MINUTE), completed = false,
            nowWall = at(10, 0), logicalDay = DAY,
        )
        assertEquals(1, awards.size, "five minutes is worth exactly one star")
        assertEquals(StarKind.FOCUS, awards.single().kind)
        assertEquals(1, afterFive.litFocus)
        assertEquals(1, afterFive.litTotal)

        val (_, none) = economy.onFocusEnded(
            freshDay(), focus(plannedMs = 45 * MINUTE, elapsedMs = 5 * MINUTE - 1), completed = false,
            nowWall = at(10, 0), logicalDay = DAY,
        )
        assertTrue(none.isEmpty(), "one millisecond short of five minutes pays nothing")
    }

    @Test
    fun e5_everyFullFifteenMinuteBlockAddsAStar() {
        val cases: List<Pair<Long, Int>> = listOf(
            5 * MINUTE to 1,
            14 * MINUTE to 1,
            15 * MINUTE to 2,
            30 * MINUTE to 3,
            44 * MINUTE + 59_000L to 3,
            45 * MINUTE to 4,
        )
        for ((elapsed, expected) in cases) {
            val (_, awards) = economy.onFocusEnded(
                freshDay(), focus(plannedMs = 90 * MINUTE, elapsedMs = elapsed), completed = false,
                nowWall = at(10, 0), logicalDay = DAY,
            )
            assertEquals(expected, awards.size, "elapsed $elapsed")
            assertTrue(awards.all { it.kind == StarKind.FOCUS })
        }
    }

    @Test
    fun e6_completingAPlannedTwentyFiveMinutesAddsTheFinishStar() {
        val (after, awards) = economy.onFocusEnded(
            freshDay(), focus(plannedMs = 25 * MINUTE, elapsedMs = 25 * MINUTE), completed = true,
            nowWall = at(10, 25), logicalDay = DAY,
        )
        assertEquals(3, awards.size, "25 completed minutes give 3 stars")
        assertEquals(StarKind.FOCUS_FINISH, awards.last().kind)
        assertEquals(2, awards.count { it.kind == StarKind.FOCUS })
        assertEquals(3, after.litFocus)
    }

    @Test
    fun e6_noFinishStarUnderTwentyFivePlannedMinutes() {
        val (_, awards) = economy.onFocusEnded(
            freshDay(), focus(plannedMs = 15 * MINUTE, elapsedMs = 15 * MINUTE), completed = true,
            nowWall = at(10, 15), logicalDay = DAY,
        )
        assertEquals(2, awards.size)
        assertTrue(awards.none { it.kind == StarKind.FOCUS_FINISH })
    }

    @Test
    fun e6_endingEarlyKeepsOnlyTheStarsTheMinutesEarned() {
        val (_, awards) = economy.onFocusEnded(
            freshDay(), focus(plannedMs = 45 * MINUTE, elapsedMs = 20 * MINUTE), completed = false,
            nowWall = at(10, 20), logicalDay = DAY,
        )
        assertEquals(2, awards.size, "a 45-minute session ended at 20 minutes gives 2")
    }

    // ── E7 QUIET_NIGHT / E8 PROTECTED_MORNING ──────────────────────────────────────────────────────

    @Test
    fun e7_aQuietNightPaysThreeStars() {
        val span: ProtectedSpan = ProtectedSpan(WindowKind.SLEEP_GATE, at(22, 30), at(22, 30) + 6 * HOUR + 51 * MINUTE)

        val (after, awards) = economy.onProtectedWindowEnded(
            day = freshDay(),
            span = span,
            entry = ledger(WindowKind.SLEEP_GATE, grants = 0, quickNeeds = 1),
            nowWall = at(22, 30) + 6 * HOUR + 55 * MINUTE,
            logicalDay = DAY,
        )

        assertEquals(StarKind.QUIET_NIGHT, awards.first().kind)
        assertEquals(3, after.litTotal, "a quiet night is worth three stars")
        assertEquals(0, after.litLeave, "quiet-night stars are not leave stars")
        assertEquals(0, after.litFocus)

        val (_, perStar) = economy.onProtectedWindowEndedAwards(
            day = freshDay(),
            span = span,
            entry = ledger(WindowKind.SLEEP_GATE, grants = 0, quickNeeds = 1),
            nowWall = at(22, 30) + 6 * HOUR + 55 * MINUTE,
            logicalDay = DAY,
        )
        assertEquals(3, perStar.size, "one award per star so the sky can light all three")
        assertTrue(perStar.all { it.kind == StarKind.QUIET_NIGHT })
        assertTrue(perStar.all { it.window == WindowKind.SLEEP_GATE })
    }

    @Test
    fun e7_twoQuickMinutesDisqualifyTheNight() {
        val span: ProtectedSpan = ProtectedSpan(WindowKind.SLEEP_GATE, at(22, 30), at(22, 30) + 6 * HOUR)
        val start: EconomyDay = freshDay()

        val (after, awards) = economy.onProtectedWindowEnded(
            start, span, ledger(WindowKind.SLEEP_GATE, quickNeeds = 2), at(5, 21), DAY,
        )

        assertTrue(awards.isEmpty())
        assertEquals(start, after, "a night that was not quiet records nothing")
    }

    @Test
    fun e7_aGrantOrReconciledUsageDisqualifiesTheNight() {
        val span: ProtectedSpan = ProtectedSpan(WindowKind.SLEEP_GATE, at(22, 30), at(22, 30) + 6 * HOUR)

        val withGrant: Pair<EconomyDay, List<StarAward>> = economy.onProtectedWindowEnded(
            freshDay(), span, ledger(WindowKind.SLEEP_GATE, grants = 1), at(5, 21), DAY,
        )
        val withUsage: Pair<EconomyDay, List<StarAward>> = economy.onProtectedWindowEnded(
            freshDay(), span, ledger(WindowKind.SLEEP_GATE, reconciled = true), at(5, 21), DAY,
        )

        assertTrue(withGrant.second.isEmpty(), "a stay in the gate is not a quiet night")
        assertTrue(withUsage.second.isEmpty(), "reconciled guarded usage is not a quiet night")
    }

    @Test
    fun E7_lost_with_one_override() {
        // The night he opened anyway: the engine books the OVERRIDE as a grant on the gate's ledger entry
        // (A.3), so the window is no longer quiet and its three stars are gone. Not a fine — a consequence,
        // and the only place the door costs anything (§A.0).
        val span: ProtectedSpan = ProtectedSpan(WindowKind.SLEEP_GATE, at(22, 30), at(22, 30) + 6 * HOUR + 51 * MINUTE)
        val fajr: Long = at(22, 30) + 6 * HOUR + 55 * MINUTE

        val (afterUsed, awardsUsed) = economy.onProtectedWindowEnded(
            day = freshDay(),
            span = span,
            entry = ledger(WindowKind.SLEEP_GATE, grants = 1, overrides = 1),
            nowWall = fajr,
            logicalDay = DAY,
        )

        assertTrue(awardsUsed.isEmpty(), "one override loses the quiet-night stars")
        assertEquals(0, afterUsed.litTotal, "the day records nothing for a night that was not quiet")

        // The same night with the door untouched still pays all three, so the loss is the override itself.
        val (afterQuiet, quietAwards) = economy.onProtectedWindowEnded(
            day = freshDay(),
            span = span,
            entry = ledger(WindowKind.SLEEP_GATE, grants = 0, quickNeeds = 1, overrides = 0),
            nowWall = fajr,
            logicalDay = DAY,
        )

        assertEquals(3, quietAwards.size)
        assertTrue(quietAwards.all { award -> award.kind == StarKind.QUIET_NIGHT })
        assertEquals(3, afterQuiet.litTotal)
    }

    @Test
    fun e7_aNightWithoutALedgerEntryIsQuietByDefinition() {
        val span: ProtectedSpan = ProtectedSpan(WindowKind.SLEEP_GATE, at(22, 30), at(22, 30) + 6 * HOUR)
        val (after, awards) = economy.onProtectedWindowEnded(freshDay(), span, null, at(5, 21), DAY)

        assertEquals(StarKind.QUIET_NIGHT, awards.first().kind)
        assertEquals(3, after.litTotal)
    }

    @Test
    fun e7_onlyProtectedWindowsPay() {
        for (kind in listOf(WindowKind.PRAYER, WindowKind.NONE)) {
            val (after, awards) = economy.onProtectedWindowEnded(
                freshDay(),
                ProtectedSpan(kind, at(13, 0), at(13, 20)),
                ledger(kind),
                at(13, 20),
                DAY,
            )
            assertTrue(awards.isEmpty(), "$kind pays no protected-window star")
            assertEquals(0, after.litTotal)
        }
    }

    @Test
    fun e8_aQuietMorningPaysTwoStars() {
        val span: ProtectedSpan = ProtectedSpan(WindowKind.MORNING, at(6, 0), at(10, 0))

        val (after, awards) = economy.onProtectedWindowEnded(
            freshDay(), span, ledger(WindowKind.MORNING), at(10, 0), DAY,
        )
        assertEquals(StarKind.PROTECTED_MORNING, awards.first().kind)
        assertEquals(2, after.litTotal, "a quiet morning is worth two stars")

        val (_, notQuiet) = economy.onProtectedWindowEnded(
            freshDay(), span, ledger(WindowKind.MORNING, grants = 2), at(10, 0), DAY,
        )
        assertTrue(notQuiet.isEmpty(), "a morning with a stay is not protected")
    }

    // ── E9 WELCOME ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun e9_theWelcomeStarLightsAtOnceAndCountsTowardNothing() {
        val fullDay: EconomyDay = EconomyDay(
            logicalDay = DAY,
            litLeave = 12,
            litFocus = 12,
            litTotal = 30,
            lastLeaveLitWall = at(21, 0),
        )

        val (after, decision) = economy.onLeave(
            fullDay, LeaveKind.WELCOME, WindowKind.SLEEP_GATE, at(21, 5), pendingKindles = 1, grant = null,
        )

        val award: StarAward = assertIs<LeaveDecision.LitNow>(decision).award
        assertEquals(StarKind.WELCOME, award.kind)
        assertEquals(at(21, 5), award.litWall)
        assertEquals(fullDay, after, "E9 is outside every cap, including the sleep-gate rule")
    }

    // ── caps ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun leaveCap_stopsTheThirteenthLeaveStar() {
        val day: EconomyDay = EconomyDay(
            logicalDay = DAY,
            litLeave = 12,
            litTotal = 12,
            lastLeaveLitWall = at(21, 0),
        )

        val (after, decision) = economy.onLeave(
            day, LeaveKind.LEFT_PROMPT, WindowKind.NONE, at(21, 30), 0, null,
        )

        assertEquals(NoStarReason.LEAVE_CAP, assertIs<LeaveDecision.None>(decision).reason)
        assertEquals(day, after)
        assertFalse(economy.peekLeave(day, WindowKind.NONE, at(21, 30), 0))
    }

    @Test
    fun focusCap_stopsFocusStarsAtTwelvePerDay() {
        val day: EconomyDay = EconomyDay(logicalDay = DAY, litFocus = 12, litTotal = 12)
        val (after, awards) = economy.onFocusEnded(
            day, focus(plannedMs = 90 * MINUTE, elapsedMs = 90 * MINUTE), completed = true,
            nowWall = at(19, 0), logicalDay = DAY,
        )

        assertTrue(awards.isEmpty(), "focus stars stop at the focus cap")
        assertEquals(day, after)
    }

    @Test
    fun dailyCap_stopsEverySource() {
        val day: EconomyDay = EconomyDay(
            logicalDay = DAY,
            litFocus = 12,
            litTotal = 30,
            lastLeaveLitWall = at(18, 0),
        )
        val span: ProtectedSpan = ProtectedSpan(WindowKind.SLEEP_GATE, at(22, 30), at(22, 30) + 6 * HOUR)

        assertEquals(
            NoStarReason.DAILY_CAP,
            assertIs<LeaveDecision.None>(
                economy.onLeave(day, LeaveKind.LEFT_PROMPT, WindowKind.NONE, at(20, 0), 0, null).second,
            ).reason,
        )
        assertTrue(
            economy.onFocusEnded(
                day, focus(plannedMs = 25 * MINUTE, elapsedMs = 25 * MINUTE), true, at(20, 0), DAY,
            ).second.isEmpty(),
        )
        assertTrue(economy.onProtectedWindowEnded(day, span, null, at(5, 21), DAY).second.isEmpty())
    }

    @Test
    fun dailyCap_dropsTheStarsPastItSilently() {
        val day: EconomyDay = EconomyDay(logicalDay = DAY, litTotal = 29)
        val span: ProtectedSpan = ProtectedSpan(WindowKind.SLEEP_GATE, at(22, 30), at(22, 30) + 6 * HOUR)

        val (after, awards) = economy.onProtectedWindowEndedAwards(day, span, null, at(5, 21), DAY)

        assertEquals(1, awards.size, "only the last daily slot is filled")
        assertEquals(30, after.litTotal)
    }

    // ── anti-gaming ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun rateLimit_tenMinutesIsTheExactBoundary() {
        val day: EconomyDay = EconomyDay(
            logicalDay = DAY,
            litLeave = 1,
            litTotal = 1,
            lastLeaveLitWall = at(21, 13),
        )

        assertEquals(
            NoStarReason.RATE_LIMIT,
            assertIs<LeaveDecision.None>(
                economy.onLeave(day, LeaveKind.LEFT_PROMPT, WindowKind.NONE, at(21, 13) + 10 * MINUTE - 1, 0, null).second,
            ).reason,
        )
        assertIs<LeaveDecision.Kindle>(
            economy.onLeave(day, LeaveKind.LEFT_PROMPT, WindowKind.NONE, at(21, 13) + 10 * MINUTE, 0, null).second,
        )
    }

    @Test
    fun pending_onlyOneKindleMayWait() {
        val day: EconomyDay = freshDay()

        assertEquals(
            NoStarReason.PENDING,
            assertIs<LeaveDecision.None>(
                economy.onLeave(day, LeaveKind.LEFT_PROMPT, WindowKind.NONE, at(21, 20), 1, null).second,
            ).reason,
        )
        assertFalse(economy.peekLeave(day, WindowKind.NONE, at(21, 20), 1))

        // A done-early star is lit on the spot, so a waiting kindle does not block it.
        assertIs<LeaveDecision.LitNow>(
            economy.onLeave(
                day,
                LeaveKind.DONE_EARLY,
                WindowKind.NONE,
                at(21, 20),
                1,
                grant(GrantKind.STAY, grantedMs = 10 * MINUTE, consumedMs = MINUTE),
            ).second,
        )
    }

    @Test
    fun rateLimit_isSharedAcrossLeaveKeptWordAndDoneEarly() {
        // A leave star lit at 21:13 — whatever kind it was — blocks both of the other leave rules.
        val afterLitStar: EconomyDay = EconomyDay(
            logicalDay = DAY,
            litLeave = 1,
            litTotal = 1,
            lastLeaveLitWall = at(21, 13),
        )
        val stay: Grant = grant(GrantKind.STAY, grantedMs = 10 * MINUTE, consumedMs = 5 * MINUTE)

        assertEquals(
            NoStarReason.RATE_LIMIT,
            assertIs<LeaveDecision.None>(
                economy.onLeave(afterLitStar, LeaveKind.LEFT_PROMPT, WindowKind.NONE, at(21, 18), 0, null).second,
            ).reason,
        )
        assertEquals(
            NoStarReason.RATE_LIMIT,
            assertIs<LeaveDecision.None>(
                economy.onLeave(afterLitStar, LeaveKind.KEPT_WORD, WindowKind.NONE, at(21, 18), 0, stay).second,
            ).reason,
        )
        assertEquals(
            NoStarReason.RATE_LIMIT,
            assertIs<LeaveDecision.None>(
                economy.onLeave(afterLitStar, LeaveKind.DONE_EARLY, WindowKind.NONE, at(21, 18), 0, stay).second,
            ).reason,
        )
    }

    @Test
    fun rateLimit_aClockTurnedBackCannotUnlockAStar() {
        val day: EconomyDay = EconomyDay(
            logicalDay = DAY,
            litLeave = 1,
            litTotal = 1,
            lastLeaveLitWall = at(21, 13),
        )

        assertEquals(
            NoStarReason.RATE_LIMIT,
            assertIs<LeaveDecision.None>(
                economy.onLeave(day, LeaveKind.LEFT_PROMPT, WindowKind.NONE, at(20, 0), 0, null).second,
            ).reason,
            "the wall clock only ever lengthens the wait",
        )
    }

    @Test
    fun antiGaming_aQuickNeedDoesNotHandOutLeaveStars() {
        val (_, decision) = economy.onLeave(
            freshDay(),
            LeaveKind.LEFT_PROMPT,
            WindowKind.NONE,
            at(21, 10),
            0,
            grant(GrantKind.QUICK, grantedMs = MINUTE, consumedMs = MINUTE),
        )
        assertEquals(NoStarReason.NOT_ELIGIBLE, assertIs<LeaveDecision.None>(decision).reason)
    }

    @Test
    fun peekLeave_predictsExactlyWhatOnLeaveDecides() {
        val cases: List<Pair<EconomyDay, Long>> = listOf(
            freshDay() to at(21, 0),
            EconomyDay(logicalDay = DAY, litLeave = 1, litTotal = 1, lastLeaveLitWall = at(20, 55)) to at(21, 0),
            EconomyDay(logicalDay = DAY, litLeave = 12, litTotal = 12) to at(21, 0),
            EconomyDay(logicalDay = DAY, litTotal = 30) to at(21, 0),
        )

        for ((day, now) in cases) {
            val peeked: Boolean = economy.peekLeave(day, WindowKind.NONE, now, 0)
            val decided: LeaveDecision = economy.onLeave(day, LeaveKind.LEFT_PROMPT, WindowKind.NONE, now, 0, null).second
            assertEquals(peeked, decided is LeaveDecision.Kindle, "peek and decision must agree for $day")
        }
    }

    // ── rollover ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun rollover_resetsTheDayCountersButKeepsTheStars() {
        val litToday: MutableList<StarAward> = mutableListOf()
        val day: EconomyDay = EconomyDay(
            logicalDay = DAY,
            litLeave = 12,
            litFocus = 12,
            litTotal = 24,
            lastLeaveLitWall = at(23, 0),
            freeUsedMs = 5 * MINUTE,
            quickNeedsUsed = 2,
        )
        litToday += StarAward(
            kind = StarKind.LEAVE,
            litWall = at(21, 13),
            logicalDay = DAY,
            window = WindowKind.NONE,
            sourcePackage = PKG,
            sourceRef = "k1",
        )

        val next: EconomyDay = economy.rollover(day, DAY + 1)

        assertEquals(DAY + 1, next.logicalDay)
        assertEquals(0, next.litLeave)
        assertEquals(0, next.litFocus)
        assertEquals(0, next.litTotal)
        assertNull(next.lastLeaveLitWall)
        assertEquals(0L, next.freeUsedMs)
        assertEquals(0, next.quickNeedsUsed)
        assertEquals(0L, economy.freeScrollAvailableMs(next), "free minutes vanish at 04:00")
        assertEquals(3, economy.quickNeedsLeft(next))
        assertEquals(1, litToday.size, "the stars already lit are in the sky, not in EconomyDay")
        assertEquals(StarKind.LEAVE, litToday.single().kind)
    }

    @Test
    fun rollover_toTheSameOrAnEarlierDayChangesNothing() {
        val day: EconomyDay = EconomyDay(logicalDay = DAY, litLeave = 3, litTotal = 3)

        assertSame(day, economy.rollover(day, DAY))
        assertSame(day, economy.rollover(day, DAY - 1), "a clock turned back must not refill the caps")
    }

    @Test
    fun rollover_handsTheNewDayAFreshSetOfCaps() {
        val spent: EconomyDay = EconomyDay(
            logicalDay = DAY,
            litLeave = 12,
            litTotal = 30,
            lastLeaveLitWall = at(23, 0),
        )
        val next: EconomyDay = economy.rollover(spent, DAY + 1)

        assertIs<LeaveDecision.Kindle>(
            economy.onLeave(next, LeaveKind.LEFT_PROMPT, WindowKind.NONE, at(9, 0), 0, null).second,
            "the leave cap and the rate limit are yesterday's",
        )
        assertEquals(0L, economy.freeScrollAvailableMs(next))
    }

    // ── free scroll and quick needs ────────────────────────────────────────────────────────────────

    @Test
    fun freeScroll_isOneMinutePerStarTodayCappedAtTwenty() {
        assertEquals(9 * MINUTE, economy.freeScrollAvailableMs(EconomyDay(logicalDay = DAY, litTotal = 9)))
        assertEquals(
            20 * MINUTE,
            economy.freeScrollAvailableMs(EconomyDay(logicalDay = DAY, litTotal = 25)),
            "free scroll is capped at twenty minutes",
        )
        assertEquals(
            4 * MINUTE,
            economy.freeScrollAvailableMs(EconomyDay(logicalDay = DAY, litTotal = 9, freeUsedMs = 5 * MINUTE)),
        )
        assertEquals(
            0L,
            economy.freeScrollAvailableMs(EconomyDay(logicalDay = DAY, litTotal = 1, freeUsedMs = 30 * MINUTE)),
            "a spent balance never goes negative",
        )
        assertEquals(0L, economy.freeScrollAvailableMs(freshDay()))
    }

    @Test
    fun quickNeeds_countDownFromThreeAndNeverGoNegative() {
        assertEquals(3, economy.quickNeedsLeft(freshDay()))
        assertEquals(2, economy.quickNeedsLeft(EconomyDay(logicalDay = DAY, quickNeedsUsed = 1)))
        assertEquals(1, economy.quickNeedsLeft(EconomyDay(logicalDay = DAY, quickNeedsUsed = 2)))
        assertEquals(0, economy.quickNeedsLeft(EconomyDay(logicalDay = DAY, quickNeedsUsed = 3)))
        assertEquals(0, economy.quickNeedsLeft(EconomyDay(logicalDay = DAY, quickNeedsUsed = 9)))
    }
}

// ── test fixtures ────────────────────────────────────────────────────────────────────────────────────

internal val ZONE: ZoneId = ZoneId.of("Asia/Kolkata")
internal const val DAY: Int = 20_693
internal const val MINUTE: Long = 60_000L
internal const val HOUR: Long = 60L * MINUTE
internal const val PKG: String = "com.instagram.android"

/** A wall clock at [hour]:[minute] on 29 October 2026 in Thane, computed once and never read again. */
internal fun at(hour: Int, minute: Int): Long =
    LocalDate.of(2026, 10, 29).atTime(hour, minute).atZone(ZONE).toInstant().toEpochMilli()

internal fun freshDay(): EconomyDay = EconomyDay(logicalDay = DAY)

internal fun grant(
    kind: GrantKind,
    grantedMs: Long = 5 * MINUTE,
    consumedMs: Long = 0L,
): Grant = Grant(
    id = "g-${kind.name}",
    kind = kind,
    grantedMs = grantedMs,
    consumedMs = consumedMs,
    startedWall = at(21, 40),
    lastAccountedElapsed = 1_000L,
    heldSinceElapsed = null,
    pausedSinceElapsed = null,
    windowAtStart = WindowKind.NONE,
    firstPackage = PKG,
)

/** The ten discouraged minutes of "Open anyway" (§A.3), as the engine hands them out. */
internal fun overrideGrant(consumedMs: Long = 0L): Grant = grant(
    kind = GrantKind.OVERRIDE,
    grantedMs = EngineRules.OVERRIDE_MS,
    consumedMs = consumedMs,
)

internal fun kindle(
    leave: LeaveKind,
    id: String = "k1",
    window: WindowKind = WindowKind.NONE,
): Kindle = Kindle(
    id = id,
    leave = leave,
    createdElapsed = 1_000L,
    confirmAtElapsed = 1_000L + 180_000L,
    sourcePackage = PKG,
    window = window,
)

internal fun focus(plannedMs: Long, elapsedMs: Long): FocusState = FocusState(
    id = "f1",
    plannedMs = plannedMs,
    elapsedMs = elapsedMs,
    lastAccountedElapsed = elapsedMs,
    startedWall = at(9, 0),
    sound = SoundKind.NONE,
)

internal fun ledger(
    kind: WindowKind,
    grants: Int = 0,
    quickNeeds: Int = 0,
    reconciled: Boolean = false,
    overrides: Int = 0,
): LedgerEntry = LedgerEntry(
    kind = kind,
    startWall = at(22, 30),
    grants = grants,
    quickNeeds = quickNeeds,
    reconciledUsage = reconciled,
    overrides = overrides,
)
