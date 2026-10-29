package com.ramish.sadiq.core.engine

import com.ramish.sadiq.core.economy.EconomyRules
import com.ramish.sadiq.core.economy.LeaveDecision
import com.ramish.sadiq.core.economy.NoStarReason
import com.ramish.sadiq.core.economy.StarKind
import com.ramish.sadiq.core.model.GuardContext
import com.ramish.sadiq.core.model.WindowKind
import com.ramish.sadiq.core.testing.FakeStarEconomy
import com.ramish.sadiq.core.testing.Fixtures
import com.ramish.sadiq.core.testing.TestTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One night, 21:00 → 06:00, with every effect asserted in order.
 *
 * The transitions are tested one row at a time above; this is the same machine running as a night:
 * a leave that pays after three minutes, a Stay that runs out, a kept word, a quick minute inside the
 * sleep gate, and the quiet night that pays once at Fajr. The effect lists are exact, so a change in
 * ordering — the thing that decides whether the surface, the sound and the record land in the right
 * order — breaks this test rather than reaching the device.
 *
 * The fake economy is told to refuse a leave star inside the gate, which is what the real economy
 * does (§3.8: no leave stars at 2 AM; the night pays once, at Fajr).
 */
class NightScenarioTest {

    private val gateStart: Long = TestTime.at("2026-10-29T22:30").wallMs
    private val gateEnd: Long = TestTime.at("2026-10-30T05:21").wallMs
    private val fajrWindow: GuardContext by lazy {
        Fixtures.context(
            now = TestTime.at("2026-10-30T05:25"),
            window = Fixtures.window(
                kind = WindowKind.PRAYER,
                startWall = TestTime.at("2026-10-30T05:21").wallMs,
                endWall = TestTime.at("2026-10-30T05:41").wallMs,
            ),
            logicalDay = Fixtures.EPOCH_DAY + 1,
            endedProtected = listOf(Fixtures.span(kind = WindowKind.SLEEP_GATE, startWall = gateStart, endWall = gateEnd)),
        )
    }

    private fun gateWindow(atIso: String): GuardContext = Fixtures.context(
        now = TestTime.at(atIso),
        window = Fixtures.window(kind = WindowKind.SLEEP_GATE, startWall = gateStart, endWall = gateEnd),
        logicalDay = Fixtures.EPOCH_DAY,
    )

    @Test
    fun theNightFromNineToSix() {
        val economy = FakeStarEconomy(
            leaveDecision = LeaveDecision.Kindle(EconomyRules.KINDLE_CONFIRM_MS),
            kindleAward = Fixtures.star(kind = StarKind.LEAVE),
            protectedAwards = listOf(Fixtures.star(kind = StarKind.QUIET_NIGHT)),
            freeScrollMs = 0L,
            quickNeeds = 3,
        )
        val rig = Rig(economy = economy)

        // 21:00 — he opens the feed. The question, the ducked audio, the record.
        rig.openGuarded(at = TestTime.at("2026-10-29T21:00"))
        assertEquals(listOf("Duck", "Show(PAUSE)", "Record(Open:DISMISSED)"), rig.tags())

        // 21:02 — he puts it down. A kindle is waiting for three quiet minutes.
        rig.choose(Choice.Leave, at = TestTime.at("2026-10-29T21:02"))
        assertEquals(
            listOf("Dismiss", "Home", "Release", "Record(Open:LEFT)"),
            rig.tags(),
        )
        assertEquals("LEFT_PROMPT", economy.lastLeave?.name)
        assertEquals(1, rig.state.kindles.size)

        // 21:05 — nothing reopened, so the star lights.
        rig.tick(at = TestTime.at("2026-10-29T21:05"))
        assertEquals(listOf("Record(StarLit)"), rig.tags())

        // 21:40 — back again, same question.
        rig.openGuarded(at = TestTime.at("2026-10-29T21:40"))
        assertEquals(listOf("Duck", "Show(PAUSE)", "Record(Open:DISMISSED)"), rig.tags())

        // 21:41 — five minutes, chosen freely.
        rig.choose(Choice.Stay(5), at = TestTime.at("2026-10-29T21:41"))
        assertEquals(
            listOf("Dismiss", "Release", "Live(GrantRunning)", "Record(Open:STAYED)"),
            rig.tags(),
        )
        assertEquals(GrantKind.STAY, rig.state.grant?.kind)
        assertEquals(5 * MIN, rig.state.grant?.grantedMs)

        // 21:46 — the five minutes are his, and now they are gone.
        rig.tick(at = TestTime.at("2026-10-29T21:46"))
        assertEquals(
            listOf("Duck", "Show(TIME_UP)", "Live(None)", "Record(GrantClosed:EXPIRED)"),
            rig.tags(),
        )
        val timeUp = rig.ui()
        assertEquals(SurfaceKind.TIME_UP, timeUp.kind)
        assertEquals(GrantKind.STAY, timeUp.timeUpKind)
        assertEquals(listOf(2, 5), timeUp.stayOptionsMin)
        assertEquals(5 * MIN, rig.state.budget.remainingMs)

        // 21:47 — he leaves a Stay time-up: a kept word, which pays after three minutes.
        rig.choose(Choice.Leave, at = TestTime.at("2026-10-29T21:47"))
        assertEquals(listOf("Dismiss", "Home", "Release", "Record(Open:LEFT)"), rig.tags())
        assertEquals("KEPT_WORD", economy.lastLeave?.name)
        assertEquals(1, rig.state.kindles.size)

        // 21:50 — the kept word lights.
        rig.tick(at = TestTime.at("2026-10-29T21:50"))
        assertEquals(listOf("Record(StarLit)"), rig.tags())

        // 22:31 — the sleep gate is open. The night surface is dark and offers no free scroll.
        val gate = gateWindow("2026-10-29T22:31")
        rig.openGuarded(at = TestTime.at("2026-10-29T22:31"), context = gate)
        assertEquals(listOf("Duck", "Show(SLEEP_GATE)", "Record(Open:DISMISSED)"), rig.tags())
        val sleep = rig.ui()
        assertTrue(sleep.forceDark)
        assertTrue(sleep.freeScrollOptionsMin.isEmpty())
        assertTrue(sleep.stayOptionsMin.isEmpty())
        assertEquals(3, sleep.quickNeedsLeft)

        // 22:32 — one minute, held. It is the only soft way out of the gate.
        rig.choose(Choice.QuickNeed, at = TestTime.at("2026-10-29T22:32"), context = gate)
        assertEquals(
            listOf("Dismiss", "Release", "Live(GrantRunning)", "Record(Open:QUICK_NEED)"),
            rig.tags(),
        )
        assertEquals(GrantKind.QUICK, rig.state.grant?.kind)
        assertEquals(1, rig.state.ledger.single().quickNeeds)

        // 22:33 — the minute is over. In the gate there is nothing to stay with.
        rig.tick(at = TestTime.at("2026-10-29T22:33"), context = gate)
        assertEquals(
            listOf("Duck", "Show(TIME_UP)", "Live(None)", "Record(GrantClosed:EXPIRED)"),
            rig.tags(),
        )
        val quickTimeUp = rig.ui()
        assertEquals(GrantKind.QUICK, quickTimeUp.timeUpKind)
        assertTrue(quickTimeUp.stayOptionsMin.isEmpty())

        // 22:34 — no star at 2 AM; the night pays once, at Fajr (§3.8).
        economy.leaveDecision = LeaveDecision.None(NoStarReason.SLEEP_GATE)
        rig.choose(Choice.Leave, at = TestTime.at("2026-10-29T22:34"), context = gate)
        assertEquals(listOf("Dismiss", "Home", "Release", "Record(Open:LEFT)"), rig.tags())
        assertEquals("KEPT_WORD", economy.lastLeave?.name)
        assertTrue(rig.state.kindles.isEmpty())

        // 05:25 — the gate ended at 05:21. Fajr is in, and the night was quiet: three stars.
        rig.tick(at = TestTime.at("2026-10-30T05:25"), context = fajrWindow)
        assertEquals(listOf("Record(StarLit)"), rig.tags())
        assertEquals(WindowKind.SLEEP_GATE, economy.lastSpan?.kind)
        assertEquals(1, economy.lastLedgerEntry?.quickNeeds)
        assertEquals(false, economy.lastLedgerEntry?.reconciledUsage)

        // 06:00 — the morning starts with nothing pending.
        rig.tick(
            at = TestTime.at("2026-10-30T06:00"),
            context = Fixtures.context(now = TestTime.at("2026-10-30T06:00"), logicalDay = Fixtures.EPOCH_DAY + 1),
        )
        assertTrue(rig.effects.isEmpty())
        assertEquals(Phase.IDLE, rig.state.phase)
        assertEquals(0, rig.state.kindles.size)
    }
}
