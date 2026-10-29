package com.ramish.sadiq.core.contract

import com.ramish.sadiq.core.economy.EconomyRules
import com.ramish.sadiq.core.engine.EngineRules
import com.ramish.sadiq.core.model.GuardConfig
import com.ramish.sadiq.core.model.Mode
import com.ramish.sadiq.core.testing.Fixtures
import com.ramish.sadiq.core.testing.TestTime
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The numeric heart of the product: every value in §3.6 (interception) and §3.8 (star economy), plus
 * the mode table and the settings defaults.
 *
 * If one of these fails, either the spec changed or a constant drifted. Both are worth stopping a
 * build for.
 */
class RulesConstantsTest {

    @Test
    fun engineRules_matchTheSpecTable() {
        assertEquals(listOf(2, 5, 10, 15), EngineRules.STAY_CHIPS_MIN)
        assertEquals(4, EngineRules.MAX_STAY_CHIPS)
        assertEquals(5, EngineRules.PRAYER_STAY_MAX_MIN)
        assertEquals(2, EngineRules.MORNING_STAY_MIN)
        assertEquals(2_000L, EngineRules.HOLD_TO_CONFIRM_MS)
        assertEquals(60_000L, EngineRules.QUICK_NEED_MS)
        assertEquals(3, EngineRules.QUICK_NEEDS_PER_DAY)
        assertEquals(180_000L, EngineRules.HOLD_GRACE_MS)
        assertEquals(3_600_000L, EngineRules.PAUSED_MAX_MS)
        assertEquals(6, EngineRules.REFLECT_OPENS)
        assertEquals(1_200_000L, EngineRules.REFLECT_WINDOW_MS)
        assertEquals(3_600_000L, EngineRules.REFLECT_COOLDOWN_MS)
        assertEquals(60_000L, EngineRules.BUDGET_EPSILON_MS)
        assertEquals(listOf(5, 15, 25, 45, 60, 90), EngineRules.FOCUS_CHOICES_MIN)
        assertEquals(25, EngineRules.FOCUS_DEFAULT_MIN)
        assertEquals(1_200_000L, EngineRules.WIND_DOWN_MS)
        assertEquals(listOf(5, 10), EngineRules.FREE_CHIPS_MIN)
        assertEquals(600L, EngineRules.OVERLAY_FALLBACK_MS)
        assertEquals(60_000L, EngineRules.SELF_TEST_ARM_MS)
    }

    @Test
    fun economyRules_matchTheSpecTable() {
        assertEquals(180_000L, EconomyRules.KINDLE_CONFIRM_MS)
        assertEquals(600_000L, EconomyRules.LEAVE_RATE_LIMIT_MS)
        assertEquals(12, EconomyRules.LEAVE_DAILY_CAP)
        assertEquals(12, EconomyRules.FOCUS_DAILY_CAP)
        assertEquals(30, EconomyRules.DAILY_CAP)
        assertEquals(60_000L, EconomyRules.FREE_MS_PER_STAR)
        assertEquals(1_200_000L, EconomyRules.FREE_SCROLL_DAILY_MAX_MS)
        assertEquals(120_000L, EconomyRules.DONE_EARLY_MIN_REMAINING_MS)
        assertEquals(0.5, EconomyRules.DONE_EARLY_MIN_REMAINING_FRACTION)
        assertEquals(300_000L, EconomyRules.FOCUS_FIRST_STAR_MS)
        assertEquals(900_000L, EconomyRules.FOCUS_BLOCK_MS)
        assertEquals(1_500_000L, EconomyRules.FOCUS_FINISH_MIN_PLANNED_MS)
        assertEquals(3, EconomyRules.QUIET_NIGHT_STARS)
        assertEquals(2, EconomyRules.PROTECTED_MORNING_STARS)
        assertEquals(1, EconomyRules.WELCOME_STARS)
        assertEquals(1, EconomyRules.QUIET_MAX_QUICK_NEEDS)
        assertEquals(1, EconomyRules.MAX_PENDING_KINDLES)
        assertEquals(7, EconomyRules.QUIET_NIGHT_CONSTELLATION_SIZE)
    }

    @Test
    fun modeTable_matchesTheSpec() {
        assertEquals(15, Mode.GENTLE.budgetMin)
        assertEquals(15, Mode.GENTLE.cooldownMin)
        assertEquals(10, Mode.BALANCED.budgetMin)
        assertEquals(30, Mode.BALANCED.cooldownMin)
        assertEquals(5, Mode.HARDCORE.budgetMin)
        assertEquals(60, Mode.HARDCORE.cooldownMin)
        assertEquals(3, Mode.entries.size)
    }

    @Test
    fun configDefaults_matchTheDataModel() {
        val config = GuardConfig()

        assertEquals(Mode.BALANCED, config.mode)
        assertEquals(emptyList(), config.apps)
        assertEquals(emptySet(), config.domains)
        assertEquals(19.2183, config.prayer.lat)
        assertEquals(72.9781, config.prayer.lon)
        assertEquals("Thane, Maharashtra", config.prayer.place)
        assertEquals(20, config.prayer.windowMin)
        assertEquals(60, config.prayer.jumuahWindowMin)
        assertEquals(true, config.sleepGate.enabled)
        assertEquals(1350, config.sleepGate.startMinuteOfDay)
        assertEquals(true, config.morning.enabled)
        assertEquals(570, config.morning.endMinuteOfDay)
        assertEquals(0, config.ramadan.hijriOffsetDays)
        assertEquals(false, config.strict.enabled)
        assertEquals(10, config.strict.delayMin)
        assertEquals(true, config.reflectionEnabled)
        assertEquals(true, config.gymHintEnabled)
        assertEquals(false, config.webGuardEnabled)
    }

    @Test
    fun defaultEngineState_hasSchemaOne() {
        assertEquals(1, Fixtures.state().schema)
    }

    @Test
    fun budgetEpsilon_isUnderEveryStayChip() {
        // A chip that fits the budget must still be a usable amount of time, so the epsilon has to be
        // smaller than the smallest chip on offer.
        assertEquals(true, EngineRules.BUDGET_EPSILON_MS < EngineRules.STAY_CHIPS_MIN.min() * 60_000L)
    }

    @Test
    fun freeScrollCapIsTwentyStarsWorth() {
        assertEquals(
            EconomyRules.FREE_SCROLL_DAILY_MAX_MS,
            20 * EconomyRules.FREE_MS_PER_STAR
        )
        assertEquals(
            EconomyRules.FREE_MS_PER_STAR,
            60_000L
        )
    }

    @Test
    fun testTime_readsTheDocumentedLocalInstant() {
        // The helper every other package's tests build on: a local date-time plus a zone, with no
        // system clock involved, and elapsed time advancing in step with wall time.
        val snapshot = TestTime.at("2026-10-29T23:40", "Asia/Kolkata")

        assertEquals(23, TestTime.local(snapshot).hour)
        assertEquals(40, TestTime.local(snapshot).minute)
        assertEquals(23 * 60 + 40, TestTime.minuteOfDay(snapshot))
        assertEquals("Asia/Kolkata", snapshot.zoneId)
        assertEquals(1, snapshot.bootCount)

        val later = TestTime.after(snapshot, wallMinutes = 5)
        assertEquals(5 * 60_000L, later.wallMs - snapshot.wallMs)
        assertEquals(5 * 60_000L, later.elapsedMs - snapshot.elapsedMs)

        val rebooted = TestTime.reboot(later, wallMinutes = 20)
        assertEquals(2, rebooted.bootCount)
        assertEquals(20 * 60_000L, rebooted.wallMs - later.wallMs)
        assertEquals(60_000L, rebooted.elapsedMs)
    }
}
