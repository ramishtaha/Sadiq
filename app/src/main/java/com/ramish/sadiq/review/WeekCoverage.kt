package com.ramish.sadiq.review

import com.ramish.sadiq.core.insight.WeeklyInput

/**
 * The honesty rules around "a week": which seven logical days a review covers, whether Sadiq has
 * been keeping records long enough to say anything about one, and how many days hold data.
 *
 * A review is only offered once the oldest record reaches back to the first day of the window. The
 * alternative — demanding a row on all seven days — would hide the screen from a light user who
 * simply had quiet days, which would be a mute button dressed up as diligence.
 */
object WeekCoverage {

    /** The review is seven logical days, today inclusive. */
    const val DAYS_IN_WEEK: Int = 7

    /** The seven days under review, ending on [today] (a logical day from `LogicalDays.of`). */
    fun reviewDays(today: Int): IntRange = (today - DAYS_IN_WEEK + 1)..today

    /** The seven days the review is compared against, immediately before [reviewDays]. */
    fun comparisonDays(today: Int): IntRange =
        (today - (2 * DAYS_IN_WEEK) + 1)..(today - DAYS_IN_WEEK)

    /**
     * Every logical day inside the window on which Sadiq holds any record at all: usage rows,
     * guarded opens, stars or check-ins.
     *
     * Usage rows alone would be wrong twice over: they only exist for guarded apps, and they need
     * usage access, while a star or a check-in is recorded without any permission.
     */
    fun recordedDays(input: WeeklyInput): Set<Int> {
        val days = HashSet<Int>()
        input.usage.forEach { row -> if (row.logicalDay in input.days) days += row.logicalDay }
        input.opens.forEach { open -> if (open.logicalDay in input.days) days += open.logicalDay }
        input.stars.forEach { star -> if (star.logicalDay in input.days) days += star.logicalDay }
        input.reflections.forEach { reflection ->
            if (reflection.logicalDay in input.days) days += reflection.logicalDay
        }
        return days
    }

    /**
     * True when Sadiq's records span the whole window.
     *
     * The test is the *oldest* record, not the count: a week with two quiet evenings is still a week
     * Sadiq watched, and it must still produce a review.
     */
    fun isWeekReady(input: WeeklyInput): Boolean {
        val earliest: Int = recordedDays(input).minOrNull() ?: return false
        return earliest <= input.days.first
    }
}
