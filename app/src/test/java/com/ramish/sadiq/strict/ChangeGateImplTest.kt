package com.ramish.sadiq.strict

import com.ramish.sadiq.contract.GateDecision
import com.ramish.sadiq.core.model.CalcMethod
import com.ramish.sadiq.core.model.GuardConfig
import com.ramish.sadiq.core.model.Mode
import com.ramish.sadiq.core.model.PrayerConfig
import com.ramish.sadiq.core.model.RamadanConfig
import com.ramish.sadiq.core.model.RamadanMode
import com.ramish.sadiq.core.model.StrictConfig
import com.ramish.sadiq.core.strict.DefaultStrictPolicy
import com.ramish.sadiq.core.strict.SettingsChange
import com.ramish.sadiq.core.time.TimeSnapshot
import com.ramish.sadiq.testing.FakeClock
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/**
 * The gate that turns a loosening edit into a waiting one.
 *
 * [com.ramish.sadiq.data.settings.SettingsRepositoryImpl] calls this for every write, so these tests
 * are the guarantee that "Think it over" is on for every screen rather than for the one screen that
 * remembered to ask.
 */
class ChangeGateImplTest {

    private val clock = FakeClock()

    private val gate = ChangeGateImpl(policy = DefaultStrictPolicy(), clock = clock)

    private val now: Long = FakeClock.EVENING.wallMs

    /** Firm, with the delay on and the plan's ten minutes. */
    private val strict = GuardConfig(
        mode = Mode.HARDCORE,
        strict = StrictConfig(enabled = true, delayMin = 10)
    )

    @Test
    fun withTheFeatureOffNothingWaits() = runTest {
        val off = strict.copy(strict = StrictConfig(enabled = false))
        assertEquals(
            GateDecision.ApplyNow,
            gate.gate(off, SettingsChange.SetMode(Mode.GENTLE), now),
            "a loosening edit is only delayed once the user turned the delay on",
        )
    }

    @Test
    fun aTighteningEditLandsAtOnce() = runTest {
        val balanced = strict.copy(mode = Mode.BALANCED)
        assertEquals(
            GateDecision.ApplyNow,
            gate.gate(balanced, SettingsChange.SetMode(Mode.HARDCORE), now),
        )
    }

    @Test
    fun aNeutralEditLandsAtOnce() = runTest {
        assertEquals(
            GateDecision.ApplyNow,
            gate.gate(
                strict,
                SettingsChange.SetPrayer(PrayerConfig(method = CalcMethod.MUSLIM_WORLD_LEAGUE)),
                now,
            ),
        )
    }

    @Test
    fun aLooseningEditWaitsTheConfiguredMinutes() = runTest {
        val decision = gate.gate(strict, SettingsChange.SetMode(Mode.GENTLE), now)
        assertEquals(GateDecision.Defer(now + 10 * 60_000L), decision)
    }

    @Test
    fun turningTheFeatureOffItselfWaits() = runTest {
        val decision = gate.gate(
            strict,
            SettingsChange.SetStrict(StrictConfig(enabled = false, delayMin = 10)),
            now,
        )
        assertEquals(
            GateDecision.Defer(now + 10 * 60_000L),
            decision,
            "the one edit that would end every other delay must not land at once",
        )
    }

    @Test
    fun aZeroDelayDefersNothing() = runTest {
        val zero = strict.copy(strict = StrictConfig(enabled = true, delayMin = 0))
        assertEquals(GateDecision.ApplyNow, gate.gate(zero, SettingsChange.SetMode(Mode.GENTLE), now))
    }

    @Test
    fun ramadanLengthensTheWaitToThirtyMinutes() = runTest {
        val ramadanClock = FakeClock(
            TimeSnapshot(
                wallMs = wallAt("2026-02-18", hour = 12),
                elapsedMs = 1_000L,
                bootCount = 1,
                zoneId = "Asia/Kolkata"
            )
        )
        val ramadanGate = ChangeGateImpl(policy = DefaultStrictPolicy(), clock = ramadanClock)
        val on = strict.copy(ramadan = RamadanConfig(mode = RamadanMode.ON))

        val decision = ramadanGate.gate(on, SettingsChange.SetMode(Mode.GENTLE), ramadanClock.now().wallMs)

        assertEquals(
            GateDecision.Defer(ramadanClock.now().wallMs + 30 * 60_000L),
            decision,
            "the month's wait is thirty minutes, not the configured ten",
        )
    }

    @Test
    fun theHijriCorrectionMovesTheRamadanWindow() = runTest {
        // 17 February 2026 is 29 Sha'ban 1447; a +1 correction makes it the first of Ramadan.
        val ramadanClock = FakeClock(
            TimeSnapshot(
                wallMs = wallAt("2026-02-17", hour = 12),
                elapsedMs = 1_000L,
                bootCount = 1,
                zoneId = "Asia/Kolkata"
            )
        )
        val ramadanGate = ChangeGateImpl(policy = DefaultStrictPolicy(), clock = ramadanClock)

        val noCorrection = strict.copy(ramadan = RamadanConfig(mode = RamadanMode.ON, hijriOffsetDays = 0))
        val corrected = strict.copy(ramadan = RamadanConfig(mode = RamadanMode.ON, hijriOffsetDays = 1))

        assertEquals(
            GateDecision.Defer(ramadanClock.now().wallMs + 10 * 60_000L),
            ramadanGate.gate(noCorrection, SettingsChange.SetMode(Mode.GENTLE), ramadanClock.now().wallMs),
            "29 Sha'ban is not Ramadan, so the ordinary ten minutes apply",
        )
        assertEquals(
            GateDecision.Defer(ramadanClock.now().wallMs + 30 * 60_000L),
            ramadanGate.gate(corrected, SettingsChange.SetMode(Mode.GENTLE), ramadanClock.now().wallMs),
            "with the correction this is the first of Ramadan",
        )
    }

    @Test
    fun aDueChangeBypassesTheGate() = runTest {
        // This is the anti-deadlock rule: the applier re-submits a stored edit through `apply`, and
        // without the bypass the gate would defer it a second time, forever.
        val decision = gate.applyingDueChange {
            gate.gate(strict, SettingsChange.SetMode(Mode.GENTLE), now)
        }
        assertEquals(GateDecision.ApplyNow, decision)
    }

    @Test
    fun theBypassDoesNotOutliveItsBlock() = runTest {
        gate.applyingDueChange {
            gate.gate(strict, SettingsChange.SetMode(Mode.GENTLE), now)
        }
        assertEquals(
            GateDecision.Defer(now + 10 * 60_000L),
            gate.gate(strict, SettingsChange.SetMode(Mode.GENTLE), now),
            "the next edit outside the block waits again",
        )
    }

    private fun wallAt(date: String, hour: Int): Long =
        LocalDate.parse(date)
            .atTime(hour, 0)
            .atZone(ZoneId.of("Asia/Kolkata"))
            .toInstant()
            .toEpochMilli()
}
