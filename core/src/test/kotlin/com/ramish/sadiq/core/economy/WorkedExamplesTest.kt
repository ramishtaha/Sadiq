package com.ramish.sadiq.core.economy

import com.ramish.sadiq.core.engine.Grant
import com.ramish.sadiq.core.engine.GrantKind
import com.ramish.sadiq.core.model.ProtectedSpan
import com.ramish.sadiq.core.model.WindowKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The six worked examples of §3.8 (W1–W6), written as executable tests with the spec's own clock times.
 *
 * They are the acceptance criteria for the whole package: if these pass, the rules compose the way the
 * lead wrote them down, including the parts no single rule owns — a kindle that fades when the phone is
 * picked up, a rate limit that starts again from the *lit* star, and a day that ends at 04:00.
 */
class WorkedExamplesTest {

    private val economy: DefaultStarEconomy = DefaultStarEconomy()

    @Test
    fun w1_twoLeavesLightTwoStarsAndTheOneInBetweenIsRateLimited() {
        // 21:10 — open Instagram, Leave: a kindle, nothing lit yet.
        var day: EconomyDay = freshDay()
        val firstLeave: Pair<EconomyDay, LeaveDecision> = economy.onLeave(
            day, LeaveKind.LEFT_PROMPT, WindowKind.NONE, at(21, 10), pendingKindles = 0, grant = null,
        )
        assertEquals(LeaveDecision.Kindle(180_000L), firstLeave.second)
        day = firstLeave.first

        // 21:13 — nothing reopened, so the star lights.
        val litAt2113: Pair<EconomyDay, StarAward?> =
            economy.confirmKindle(day, kindle(LeaveKind.LEFT_PROMPT, id = "w1-a"), at(21, 13), DAY)
        day = litAt2113.first
        assertEquals(StarKind.LEAVE, assertNotNull(litAt2113.second).kind)
        assertEquals(1, day.litLeave, "one star by 21:13")

        // 21:15 — open again, Leave: only two minutes since the lit star, so no kindle.
        val tooSoon: Pair<EconomyDay, LeaveDecision> = economy.onLeave(
            day, LeaveKind.LEFT_PROMPT, WindowKind.NONE, at(21, 15), pendingKindles = 0, grant = null,
        )
        assertEquals(NoStarReason.RATE_LIMIT, assertIs<LeaveDecision.None>(tooSoon.second).reason)
        assertEquals(day, tooSoon.first)

        // 21:24 — eleven minutes after the lit star: a kindle again, lit at 21:27.
        val thirdLeave: Pair<EconomyDay, LeaveDecision> = economy.onLeave(
            day, LeaveKind.LEFT_PROMPT, WindowKind.NONE, at(21, 24), pendingKindles = 0, grant = null,
        )
        assertEquals(LeaveDecision.Kindle(180_000L), thirdLeave.second)
        val litAt2127: Pair<EconomyDay, StarAward?> =
            economy.confirmKindle(thirdLeave.first, kindle(LeaveKind.LEFT_PROMPT, id = "w1-b"), at(21, 27), DAY)

        assertEquals(2, litAt2127.first.litLeave, "two stars by 21:27")
        assertEquals(at(21, 27), litAt2127.first.lastLeaveLitWall)
        assertEquals(2, litAt2127.first.litTotal)
    }

    @Test
    fun w2_aFadedKindleIsNotAStarAndTheStayThatFollowsPaysTheKeptWord() {
        var day: EconomyDay = freshDay()

        // Continue W1: two stars, last lit at 21:27.
        day = day.copy(litLeave = 2, litTotal = 2, lastLeaveLitWall = at(21, 27))

        // 21:40 — Leave, thirteen minutes after the last lit star: a kindle.
        val leaveAt2140: Pair<EconomyDay, LeaveDecision> = economy.onLeave(
            day, LeaveKind.LEFT_PROMPT, WindowKind.NONE, at(21, 40), pendingKindles = 0, grant = null,
        )
        assertEquals(LeaveDecision.Kindle(180_000L), leaveAt2140.second)
        day = leaveAt2140.first

        // 21:41 — YouTube is opened again. The engine fades the kindle silently and shows a PAUSE; the
        // economy is never called, so nothing here changes. The star is simply never lit.
        assertEquals(
            2,
            day.litLeave,
            "a kindle that fades is not a star",
        )

        // 21:41–21:46 — Stay 5 runs to TIME_UP.
        val stay: Grant = grant(GrantKind.STAY, grantedMs = 5 * MINUTE, consumedMs = 5 * MINUTE)

        // 21:46 — Leave from TIME_UP: a KEPT_WORD kindle, lit at 21:49.
        val keptWord: Pair<EconomyDay, LeaveDecision> = economy.onLeave(
            day, LeaveKind.KEPT_WORD, WindowKind.NONE, at(21, 46), pendingKindles = 0, grant = stay,
        )
        assertEquals(LeaveDecision.Kindle(180_000L), keptWord.second)

        val litAt2149: Pair<EconomyDay, StarAward?> =
            economy.confirmKindle(keptWord.first, kindle(LeaveKind.KEPT_WORD, id = "w2-b"), at(21, 49), DAY)

        assertEquals(StarKind.KEPT_WORD, assertNotNull(litAt2149.second).kind)
        assertEquals(3, litAt2149.first.litLeave, "the kept word is the third star")
        assertEquals(3, litAt2149.first.litTotal)
    }

    @Test
    fun w3_aFortyFiveMinuteFocusPaysFiveAndTheNinetyMinuteOneIsCappedToSeven() {
        var day: EconomyDay = freshDay()

        val first: Pair<EconomyDay, List<StarAward>> = economy.onFocusEnded(
            day,
            focus(plannedMs = 45 * MINUTE, elapsedMs = 45 * MINUTE),
            completed = true,
            nowWall = at(18, 0),
            logicalDay = DAY,
        )
        day = first.first
        assertEquals(5, first.second.size, "45 completed minutes give 5 stars")

        val second: Pair<EconomyDay, List<StarAward>> = economy.onFocusEnded(
            day,
            focus(plannedMs = 90 * MINUTE, elapsedMs = 90 * MINUTE),
            completed = true,
            nowWall = at(20, 0),
            logicalDay = DAY,
        )

        assertEquals(7, second.second.size, "8 stars were earned, but only 7 fit under the cap of 12")
        assertEquals(12, second.first.litFocus)
        assertEquals(12, second.first.litTotal)
    }

    @Test
    fun w4_aNightWithOneQuickMinutePaysThreeStarsAndWithTwoPaysNone() {
        val span: ProtectedSpan = ProtectedSpan(
            kind = WindowKind.SLEEP_GATE,
            startWall = at(22, 30),
            endWall = at(22, 30) + 6 * HOUR + 51 * MINUTE,
        )
        val firstInput: Long = at(22, 30) + 6 * HOUR + 55 * MINUTE

        val oneQuickMinute: Pair<EconomyDay, List<StarAward>> = economy.onProtectedWindowEnded(
            day = freshDay(),
            span = span,
            entry = ledger(WindowKind.SLEEP_GATE, grants = 0, quickNeeds = 1),
            nowWall = firstInput,
            logicalDay = DAY,
        )
        assertEquals(StarKind.QUIET_NIGHT, oneQuickMinute.second.first().kind)
        assertEquals(3, oneQuickMinute.first.litTotal, "a quiet night pays three")

        val twoQuickMinutes: Pair<EconomyDay, List<StarAward>> = economy.onProtectedWindowEndedAwards(
            day = freshDay(),
            span = span,
            entry = ledger(WindowKind.SLEEP_GATE, grants = 0, quickNeeds = 2),
            nowWall = firstInput,
            logicalDay = DAY,
        )
        assertTrue(twoQuickMinutes.second.isEmpty(), "two quick minutes and the night pays nothing")
        assertEquals(0, twoQuickMinutes.first.litTotal)
    }

    @Test
    fun w5_freeScrollIsTheStarsOfTheDayAndItVanishesAtFour() {
        var day: EconomyDay = freshDay()
        val lit: MutableList<StarAward> = mutableListOf()

        // Nine stars lit by 18:00: a 90-minute focus (8) and a five-minute one (1).
        val long: Pair<EconomyDay, List<StarAward>> = economy.onFocusEnded(
            day, focus(plannedMs = 90 * MINUTE, elapsedMs = 90 * MINUTE), true, at(16, 0), DAY,
        )
        day = long.first
        lit += long.second
        val short: Pair<EconomyDay, List<StarAward>> = economy.onFocusEnded(
            day, focus(plannedMs = 5 * MINUTE, elapsedMs = 5 * MINUTE), false, at(18, 0), DAY,
        )
        day = short.first
        lit += short.second
        assertEquals(9, lit.size, "nine stars by 18:00")

        assertEquals(9 * MINUTE, economy.freeScrollAvailableMs(day), "nine stars, nine minutes")

        // He spends five of them.
        day = day.copy(freeUsedMs = 5 * MINUTE)
        assertEquals(4 * MINUTE, economy.freeScrollAvailableMs(day))

        // 04:00 — the day rolls over: the free minutes are gone, the stars are not.
        val nextDay: EconomyDay = economy.rollover(day, DAY + 1)
        assertEquals(0L, economy.freeScrollAvailableMs(nextDay))
        assertEquals(9, lit.size, "the sky still holds nine stars")
        assertTrue(lit.all { it.kind == StarKind.FOCUS || it.kind == StarKind.FOCUS_FINISH })
    }

    @Test
    fun w6_atThirtyStarsALeaveStopsEarning() {
        val fullDay: EconomyDay = EconomyDay(
            logicalDay = DAY,
            litLeave = 10,
            litFocus = 12,
            litTotal = 30,
            lastLeaveLitWall = at(18, 0),
        )

        val (after, decision) = economy.onLeave(
            fullDay, LeaveKind.LEFT_PROMPT, WindowKind.NONE, at(20, 0), pendingKindles = 0, grant = null,
        )

        assertEquals(NoStarReason.DAILY_CAP, assertIs<LeaveDecision.None>(decision).reason)
        assertEquals(fullDay, after, "nothing is recorded")
        assertFalse(economy.peekLeave(fullDay, WindowKind.NONE, at(20, 0), 0), "the button reads Leave")
        assertNull(
            economy.confirmKindle(fullDay, kindle(LeaveKind.LEFT_PROMPT), at(20, 3), DAY).second,
            "a kindle arriving late lights nothing either",
        )
    }
}
