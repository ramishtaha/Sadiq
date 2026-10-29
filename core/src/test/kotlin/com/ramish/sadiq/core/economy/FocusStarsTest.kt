package com.ramish.sadiq.core.economy

import com.ramish.sadiq.core.engine.EngineRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The focus half of §3.8: the projection the Focus screen shows before a session, and the cap that
 * decides what a real session actually pays.
 *
 * `stars = (elapsed ≥ 5 min ? 1 : 0) + floor(elapsed / 15 min) + (completed && planned ≥ 25 ? 1 : 0)`,
 * capped by the focus cap of 12 a day.
 */
class FocusStarsTest {

    private val economy: StarEconomy = DefaultStarEconomy()

    @Test
    fun projection_matchesTheFormulaForEveryFocusChoice() {
        val expected: Map<Int, Int> = mapOf(5 to 1, 15 to 2, 25 to 3, 45 to 5, 60 to 6, 90 to 8)

        assertEquals(
            EngineRules.FOCUS_CHOICES_MIN,
            expected.keys.sorted(),
            "every chip the Focus screen offers has an expectation",
        )

        for (plannedMin in EngineRules.FOCUS_CHOICES_MIN) {
            assertEquals(
                expected.getValue(plannedMin),
                economy.projectFocusStars(plannedMin, alreadyLitFocusToday = 0),
                "a completed $plannedMin-minute session",
            )
        }
    }

    @Test
    fun projection_isTheFormulaItself() {
        for (plannedMin in EngineRules.FOCUS_CHOICES_MIN) {
            val formula: Int =
                (if (plannedMin >= 5) 1 else 0) + plannedMin / 15 + (if (plannedMin >= 25) 1 else 0)
            assertEquals(
                formula,
                economy.projectFocusStars(plannedMin, alreadyLitFocusToday = 0),
                "projection must not invent a second formula for $plannedMin",
            )
        }
    }

    @Test
    fun projection_deductsTheStarsAlreadyLitToday() {
        assertEquals(2, economy.projectFocusStars(45, alreadyLitFocusToday = 10))
        assertEquals(5, economy.projectFocusStars(45, alreadyLitFocusToday = 0))
        assertEquals(1, economy.projectFocusStars(5, alreadyLitFocusToday = 11))
        assertEquals(0, economy.projectFocusStars(5, alreadyLitFocusToday = 12))
    }

    @Test
    fun projection_neverGoesNegativePastTheFocusCap() {
        assertEquals(0, economy.projectFocusStars(90, alreadyLitFocusToday = 12))
        assertEquals(0, economy.projectFocusStars(90, alreadyLitFocusToday = 30))
        assertEquals(1, economy.projectFocusStars(90, alreadyLitFocusToday = 11))
    }

    @Test
    fun projection_ofAnEmptyPlanIsZero() {
        assertEquals(0, economy.projectFocusStars(0, alreadyLitFocusToday = 0))
        assertEquals(0, economy.projectFocusStars(-5, alreadyLitFocusToday = 0))
    }

    @Test
    fun award_booksBothCountersAndEndsWithTheFinishStar() {
        val (day, awards) = economy.onFocusEnded(
            day = freshDay(),
            focus = focus(plannedMs = 45 * MINUTE, elapsedMs = 45 * MINUTE),
            completed = true,
            nowWall = at(10, 45),
            logicalDay = DAY,
        )

        assertEquals(5, awards.size)
        assertEquals(StarKind.FOCUS_FINISH, awards.last().kind)
        assertEquals(4, awards.count { it.kind == StarKind.FOCUS })
        assertEquals(5, day.litFocus)
        assertEquals(5, day.litTotal)
        assertTrue(awards.all { it.litWall == at(10, 45) && it.logicalDay == DAY })
    }

    @Test
    fun award_keepsTheFinishStarWhenTheCapTruncatesTheSession() {
        val day: EconomyDay = EconomyDay(logicalDay = DAY, litFocus = 11, litTotal = 11)

        val (after, awards) = economy.onFocusEnded(
            day = day,
            focus = focus(plannedMs = 90 * MINUTE, elapsedMs = 90 * MINUTE),
            completed = true,
            nowWall = at(19, 0),
            logicalDay = DAY,
        )

        assertEquals(1, awards.size, "one slot was left, and the finish star takes it")
        assertEquals(StarKind.FOCUS_FINISH, awards.single().kind)
        assertEquals(12, after.litFocus)
    }

    @Test
    fun award_ignoresPlannedTimeExceptForTheFinishStar() {
        val (_, awards) = economy.onFocusEnded(
            day = freshDay(),
            focus = focus(plannedMs = 10 * MINUTE, elapsedMs = 40 * MINUTE),
            completed = false,
            nowWall = at(10, 40),
            logicalDay = DAY,
        )

        assertEquals(3, awards.size, "40 minutes is 1 + 2 blocks, with no finish star")
        assertTrue(awards.none { it.kind == StarKind.FOCUS_FINISH })
    }
}
