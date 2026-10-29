package com.ramish.sadiq.ui.week

import com.ramish.sadiq.core.insight.UsageDayRow
import com.ramish.sadiq.core.insight.WeeklyInput
import com.ramish.sadiq.core.model.GuardConfig
import com.ramish.sadiq.review.WeekCoverage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When the Week screen is allowed to say something about a week.
 *
 * The distinction under test is "we have rows on seven days" versus "our records span seven days":
 * only the second is a week, and the first would hide the review from anyone with a quiet evening.
 */
class WeekCoverageTest {

    private fun input(
        days: IntRange,
        usageDays: List<Int> = emptyList(),
        starDays: List<Int> = emptyList(),
    ): WeeklyInput = WeeklyInput(
        days = days,
        usage = usageDays.map { day ->
            UsageDayRow(day, "com.instagram.android", 60_000L, 1, 0L)
        },
        prevUsage = emptyList(),
        stars = starDays.map { day ->
            com.ramish.sadiq.core.economy.StarAward(
                kind = com.ramish.sadiq.core.economy.StarKind.QUIET_NIGHT,
                litWall = 0L,
                logicalDay = day,
                window = com.ramish.sadiq.core.model.WindowKind.NONE,
                sourcePackage = null,
                sourceRef = null,
            )
        },
        opens = emptyList(),
        reflections = emptyList(),
        focusMinutes = 0,
        quietNights = 0,
        hourlyMs = LongArray(24),
        config = GuardConfig(),
        approximate = false,
    )

    @Test
    fun theWindowIsSevenDaysEndingToday() {
        assertEquals(100..106, WeekCoverage.reviewDays(106))
        assertEquals(93..99, WeekCoverage.comparisonDays(106))
    }

    @Test
    fun theReviewWaitsUntilTheRecordsReachBackAWeek() {
        val fresh = input(days = 100..106, usageDays = listOf(104, 105, 106))
        assertFalse(WeekCoverage.isWeekReady(fresh))

        val older = input(days = 100..106, usageDays = listOf(100, 105, 106))
        assertTrue(WeekCoverage.isWeekReady(older))
    }

    @Test
    fun aQuietDayDoesNotHideTheWeek() {
        // Two evenings with usage, one star on a night Sadiq recorded nothing else: still a week.
        val light = input(days = 100..106, usageDays = listOf(100, 106), starDays = listOf(103))
        assertTrue(WeekCoverage.isWeekReady(light))
        assertEquals(3, WeekCoverage.recordedDays(light).size)
    }

    @Test
    fun noRecordsAtAllIsNotAWeek() {
        assertFalse(WeekCoverage.isWeekReady(input(days = 100..106)))
        assertTrue(WeekCoverage.recordedDays(input(days = 100..106)).isEmpty())
    }

    @Test
    fun daysOutsideTheWindowAreNotCounted() {
        val mixed = input(days = 100..106, usageDays = listOf(99, 100, 106, 107))
        assertEquals(setOf(100, 106), WeekCoverage.recordedDays(mixed))
    }
}
