package com.ramish.sadiq.review

import java.util.Locale

/**
 * The Week screen's numbers, as words: durations, comparisons and clock hours.
 *
 * Pure and Android-free, so the notification and the screen format the same week the same way and
 * both can be unit-tested. Durations round to the nearest minute — a forty-second use must never be
 * printed as "0 m", which would be a false claim about a real event.
 */
object WeekFormat {

    private const val MINUTE_MS: Long = 60_000L

    /** Durations round to the nearest minute, so anything from 30 s up reads as a minute. */
    private const val ROUNDING_MS: Long = MINUTE_MS / 2

    private const val MINUTES_PER_HOUR: Long = 60L

    /** How this week compares with last week, in the only three directions that can be true. */
    enum class Comparison { LESS, SAME, MORE }

    /**
     * The duration in hours and minutes: "9 h 40 m", "40 m", "2 h".
     *
     * @param ms a duration in milliseconds; negative values are treated as zero.
     */
    fun duration(ms: Long): String {
        val minutes: Long = minutes(ms)
        if (minutes <= 0L) return "0 m"
        val hours: Long = minutes / MINUTES_PER_HOUR
        val rest: Long = minutes % MINUTES_PER_HOUR
        return when {
            hours == 0L -> "$rest m"
            rest == 0L -> "$hours h"
            else -> "$hours h $rest m"
        }
    }

    /** The rounded minute count of [ms]; the one place the rounding rule lives. */
    fun minutes(ms: Long): Long = (ms.coerceAtLeast(0L) + ROUNDING_MS) / MINUTE_MS

    /**
     * Which way this week went, decided after rounding so a difference smaller than half a minute
     * reads as "same" instead of as a direction nobody can act on.
     *
     * @param deltaMs this week minus last week, as [com.ramish.sadiq.core.insight.WeeklyReview.deltaMs].
     */
    fun comparison(deltaMs: Long): Comparison = when {
        minutes(kotlin.math.abs(deltaMs)) <= 0L -> Comparison.SAME
        deltaMs < 0L -> Comparison.LESS
        else -> Comparison.MORE
    }

    /**
     * A clock hour, "23:00" or "11 pm", following the phone's 12/24-hour setting.
     *
     * @param hour 0–23; values outside the day wrap, so the end of a band that crosses midnight
     *   still prints a real hour.
     * @param is24Hour `DateFormat.is24HourFormat(context)` on the screen, and the same setting read
     *   by the notification.
     */
    fun hourLabel(hour: Int, is24Hour: Boolean): String {
        val wrapped: Int = ((hour % 24) + 24) % 24
        if (is24Hour) return String.format(Locale.ROOT, "%02d:00", wrapped)
        val suffix: String = if (wrapped < 12) "am" else "pm"
        val twelve: Int = wrapped % 12
        val display: Int = if (twelve == 0) 12 else twelve
        return "$display $suffix"
    }

    /** The end of the two-hour band that starts at [startHour], wrapping across midnight. */
    fun bandEndHour(startHour: Int): Int = ((startHour + 2) % 24 + 24) % 24

    /**
     * A minute-of-day as a clock time, "22:00" or "10 pm", for a suggestion's target.
     *
     * @param minuteOfDay minutes since local midnight.
     */
    fun minuteLabel(minuteOfDay: Int, is24Hour: Boolean): String =
        hourLabel(minuteOfDay / 60, is24Hour)
}
