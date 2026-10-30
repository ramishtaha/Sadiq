// CONTRACT-GAP: WeeklyInput carries no day range for `prevUsage`, so the previous week is summed as
// supplied; the caller that produced those rows (HistoryRepository.weekInput) is the only one that knows
// its range. The builder filters this week's rows by `days` and cannot do the same for the comparison.
// CONTRACT-GAP: WeeklyInput carries no known-feed catalog, so the guard suggestion takes one as a
// constructor parameter (defaulted to §4.7 O2's feeds) instead of reading `AppCatalog`.
package com.ramish.sadiq.core.insight

import com.ramish.sadiq.core.engine.OpenOutcome
import com.ramish.sadiq.core.engine.RecordEvent
import com.ramish.sadiq.core.engine.SurfaceKind
import com.ramish.sadiq.core.model.Mode

/** §4.3 S4: three bars is what fits beside a headline. */
private const val TOP_APPS: Int = 3

private const val HOURS_PER_DAY: Int = 24
private const val HOUR_MINUTES: Int = 60
private const val BAND_HOURS: Int = 2
private const val MINUTES_PER_DAY: Int = 1_440

/** §4.3 S4's example: "Move your sleep gate to 22:00?" is thirty minutes earlier. */
private const val MOVE_SLEEP_GATE_BY_MIN: Int = 30

/** §4.3 S4: an unguarded known feed used more than two hours in the week is worth naming. */
private const val GUARD_APP_THRESHOLD_MS: Long = 7_200_000L

private const val PERCENT: Long = 100L

/** §4.3 S4: "up more than 20 %" — 120 % of last week, not 120 % of this one. */
private const val GROWTH_LIMIT_PERCENT: Long = 120L

/**
 * §A.7: four trips through the rest wall in one week is a rule he keeps going around, so the wall is the
 * thing that has to give — not him.
 */
private const val REST_OVERRIDES_FOR_LOOSER: Int = 4

/** The open outcomes that mean he left: the prompt's Leave, a gesture away, or the screen going off. */
private val LEAVE_OUTCOMES: Set<OpenOutcome> = setOf(
    OpenOutcome.LEFT,
    OpenOutcome.LEFT_GESTURE,
    OpenOutcome.SCREEN_OFF,
)

/**
 * The weekly review maths of §4.3 S4: where the time went, what he chose, what he said, and one
 * suggestion for next week.
 *
 * Everything is derived from the input — no clock, no I/O — so the same week always produces the same
 * review. The review is honest about itself: [WeeklyInput.approximate] is carried into
 * [WeeklyReview.approximate] so the screen can label engine records as approximate instead of presenting
 * them as measured usage, and a comparison is only offered when there is a previous week to compare with.
 *
 * Usage rows are taken as the guarded apps' rows; scoping them belongs to the repository that produced
 * them, because only it knows which apps were guarded during the week.
 *
 * @param knownFeeds the feed packages of §4.7 O2, used by the guard suggestion. It is a parameter because
 * [WeeklyInput] carries no catalog; the default keeps the class usable on its own, and the app may pass
 * `AppCatalog.knownFeeds` so the two lists cannot drift.
 */
class DefaultWeeklyReviewBuilder(
    private val knownFeeds: Set<String> = DEFAULT_KNOWN_FEEDS,
) : WeeklyReviewBuilder {

    override fun build(input: WeeklyInput): WeeklyReview {
        val weekRows: List<UsageDayRow> = input.usage.filter { row -> row.logicalDay in input.days }
        val perAppMs: Map<String, Long> = foregroundByPackage(weekRows)
        val totalMs: Long = weekRows.sumOf { row -> row.foregroundMs.coerceAtLeast(0L) }
        val previousMs: Long = input.prevUsage.sumOf { row -> row.foregroundMs.coerceAtLeast(0L) }
        val hasPreviousWeek: Boolean = input.prevUsage.isNotEmpty()
        val peakStartHour: Int? = peakBandStartHour(input.hourlyMs)

        return WeeklyReview(
            totalMs = totalMs,
            deltaMs = if (hasPreviousWeek) totalMs - previousMs else null,
            topApps = largest(perAppMs, TOP_APPS),
            peakStartHour = peakStartHour,
            stars = input.stars.count { star -> star.logicalDay in input.days },
            // Counted from opens, not from leave stars: a leave that hit the rate limit, the daily cap or
            // the sleep-gate rule still happened, and "times you left" must not shrink because the reward
            // did.
            leaves = input.opens.count { open ->
                open.logicalDay in input.days && open.outcome in LEAVE_OUTCOMES
            },
            quietNights = input.quietNights,
            focusMinutes = input.focusMinutes,
            topReason = topReason(input.reflections.filter { reflection -> reflection.logicalDay in input.days }),
            // Counted from opens, never from stars: "Open anyway" lights nothing, and the count says so
            // without a star, a gap in the sky or a word of shame (§A.7).
            overrides = input.opens.count { open ->
                open.logicalDay in input.days && open.outcome == OpenOutcome.OVERRIDE
            },
            suggestion = sleepGateMove(input, peakStartHour)
                ?: restOverrides(input)
                ?: modeSwitch(input, totalMs, previousMs, hasPreviousWeek)
                ?: guardApp(input, perAppMs),
            approximate = input.approximate,
        )
    }

    /** Sums the week's rows per package; rows with no use are dropped so a bar is never drawn for nothing. */
    private fun foregroundByPackage(rows: List<UsageDayRow>): Map<String, Long> {
        val totals = LinkedHashMap<String, Long>()
        for (row in rows) {
            if (row.foregroundMs <= 0L) continue
            val current: Long = totals[row.packageName] ?: 0L
            totals[row.packageName] = current + row.foregroundMs
        }
        return totals
    }

    /**
     * The largest entries, ties broken by package name.
     *
     * The tie-break is not cosmetic: without it the same week could list its bars in a different order on
     * two openings of the same review, and a deterministic review is the only kind worth trusting.
     */
    private fun largest(totals: Map<String, Long>, limit: Int): List<Pair<String, Long>> =
        totals.entries
            .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
            .take(limit)
            .map { entry -> entry.key to entry.value }

    /**
     * Start hour of the busiest two-hour band, or null when the profile holds no usage.
     *
     * [WeeklyInput.hourlyMs] is a 24-slot hour-of-day profile; a whole number of days (168 slots for a
     * week) is folded by `index % 24`. Any other length is not a profile this builder can read, so the
     * answer is null rather than a guess — the screen then omits the peak line instead of printing one
     * that is not true.
     *
     * The band wraps across midnight, because "most of it between 23:00 and 01:00" is a real answer. Ties
     * go to the band that starts when the use starts, then to the earliest hour, so a single busy hour at
     * 05:00 reads as 05:00–07:00 and not 04:00–06:00.
     */
    private fun peakBandStartHour(hourlyMs: LongArray): Int? {
        if (hourlyMs.isEmpty() || hourlyMs.size % HOURS_PER_DAY != 0) return null
        val byHour = LongArray(HOURS_PER_DAY)
        for (index in hourlyMs.indices) {
            val ms: Long = hourlyMs[index]
            if (ms > 0L) byHour[index % HOURS_PER_DAY] += ms
        }
        if (byHour.all { ms -> ms == 0L }) return null

        var bestStart: Int = 0
        var bestMs: Long = -1L
        for (hour in 0 until HOURS_PER_DAY) {
            val bandMs: Long = byHour[hour] + byHour[(hour + 1) % HOURS_PER_DAY]
            val betterStart: Boolean = byHour[hour] > byHour[bestStart]
            if (bandMs > bestMs || (bandMs == bestMs && betterStart)) {
                bestMs = bandMs
                bestStart = hour
            }
        }
        return bestStart
    }

    /**
     * §4.3 S4's first suggestion: when the busiest two hours touch the hour before the sleep gate, moving
     * the gate thirty minutes earlier takes that hour back.
     *
     * Overlap is checked minute by minute on the circular day. Sixty integer steps are easier to trust
     * than interval arithmetic across midnight, and the rule reads exactly as the plan states it. A gate
     * that is switched off is left alone: moving a boundary he has not set would be advice about nothing.
     */
    private fun sleepGateMove(input: WeeklyInput, peakStartHour: Int?): WeeklySuggestion? {
        if (peakStartHour == null) return null
        val gate = input.config.sleepGate
        if (!gate.enabled) return null
        if (!bandTouchesHourBefore(peakStartHour, gate.startMinuteOfDay)) return null
        val earlier: Int = floorMod(gate.startMinuteOfDay - MOVE_SLEEP_GATE_BY_MIN, MINUTES_PER_DAY)
        return WeeklySuggestion.MoveSleepGate(earlier)
    }

    /** True when any minute of the band falls inside the sixty minutes before [gateStartMinute]. */
    private fun bandTouchesHourBefore(peakStartHour: Int, gateStartMinute: Int): Boolean {
        val bandStartMinute: Int = peakStartHour * HOUR_MINUTES
        val bandLengthMin: Int = BAND_HOURS * HOUR_MINUTES
        for (offset in 0 until HOUR_MINUTES) {
            val minute: Int = floorMod(gateStartMinute - HOUR_MINUTES + offset, MINUTES_PER_DAY)
            if (floorMod(minute - bandStartMinute, MINUTES_PER_DAY) < bandLengthMin) return true
        }
        return false
    }

    /**
     * §A.7's rule, placed after the sleep gate and before the mode rule: in Firm (the code's
     * [Mode.HARDCORE]), four or more "Open anyway" opens from the rest wall in one week mean the wall is
     * tighter than the week, so Steady is the honest suggestion.
     *
     * Only `COOLDOWN` counts. A night override is answered by the reason line he already gets ("Sleep is
     * the better scroll"), and suggesting he loosen the whole mode because of 2 AM would be the app giving
     * up on the one hour it exists for — so night and time's-up overrides never loosen anything here.
     */
    private fun restOverrides(input: WeeklyInput): WeeklySuggestion? {
        if (input.config.mode != Mode.HARDCORE) return null
        val restOverrides: Int = input.opens.count { open ->
            open.logicalDay in input.days &&
                open.outcome == OpenOutcome.OVERRIDE &&
                open.surface == SurfaceKind.COOLDOWN
        }
        if (restOverrides < REST_OVERRIDES_FOR_LOOSER) return null
        return WeeklySuggestion.SwitchMode(Mode.BALANCED)
    }

    /**
     * §4.3 S4's second suggestion: in Gentle, a week-on-week rise above 20 % says the mode is not holding,
     * and Balanced is a smaller change than another week of the same.
     *
     * The comparison is integer (`total · 100 > previous · 120`) so the 20 % line lands exactly where the
     * plan puts it instead of where floating point rounds it. A previous week with no recorded time gives
     * no percentage, so the rule stays silent rather than treating zero as a baseline and calling any use
     * an increase.
     */
    private fun modeSwitch(
        input: WeeklyInput,
        totalMs: Long,
        previousMs: Long,
        hasPreviousWeek: Boolean,
    ): WeeklySuggestion? {
        if (input.config.mode != Mode.GENTLE) return null
        if (!hasPreviousWeek || previousMs <= 0L) return null
        if (totalMs * PERCENT <= previousMs * GROWTH_LIMIT_PERCENT) return null
        return WeeklySuggestion.SwitchMode(Mode.BALANCED)
    }

    /**
     * §4.3 S4's third suggestion: a known feed he has not guarded, used more than two hours in the week.
     *
     * Only one app is named — the largest — because the review offers one action, and a list of four
     * things to guard is a list he will close. Already-guarded apps are skipped (the suggestion would do
     * nothing) and apps outside the catalog are skipped too: the builder can only name what it knows is a
     * feed, and guessing would be an accusation dressed as advice.
     */
    private fun guardApp(input: WeeklyInput, perAppMs: Map<String, Long>): WeeklySuggestion? {
        if (knownFeeds.isEmpty()) return null
        val guarded: Set<String> = input.config.apps.map { app -> app.packageName }.toSet()
        val candidates: Map<String, Long> = perAppMs.filterKeys { packageName ->
            packageName in knownFeeds && packageName !in guarded
        }
        val worst: Pair<String, Long> = largest(candidates, 1).firstOrNull() ?: return null
        if (worst.second <= GUARD_APP_THRESHOLD_MS) return null
        return WeeklySuggestion.GuardApp(worst.first)
    }

    /**
     * The reason he tapped most often this week, or null when he did not tap one.
     *
     * Ties fall to the enum's order so the same week always names the same reason. The screen shows one
     * line, and a stable choice is better than a line that changes between two openings of one review.
     */
    private fun topReason(reflections: List<RecordEvent.Reflected>): ReflectionReason? =
        reflections
            .groupingBy { reflection -> reflection.reason }
            .eachCount()
            .entries
            .sortedWith(
                compareByDescending<Map.Entry<ReflectionReason, Int>> { entry -> entry.value }
                    .thenBy { entry -> entry.key.ordinal },
            )
            .firstOrNull()
            ?.key

    companion object {
        /**
         * §4.7 O2's feeds, kept in step with `AppCatalog.knownFeeds` (WP-12).
         *
         * Duplicated here so this builder has no dependency on a file that is not part of the contract;
         * the app passes the catalog in, and the default is the plan's own list.
         */
        val DEFAULT_KNOWN_FEEDS: Set<String> = setOf(
            "com.instagram.android",
            "com.google.android.youtube",
            "com.facebook.katana",
            "com.twitter.android",
            "com.reddit.frontpage",
            "com.snapchat.android",
            "in.mohalla.sharechat",
            "in.mohalla.video",
            "com.eterno.shortvideos",
            "com.mxtakatak",
            "com.instagram.barcelona",
        )
    }
}

/** Non-negative remainder, so a gate before midnight still lands on a minute of the day. */
private fun floorMod(value: Int, modulus: Int): Int {
    val remainder: Int = value % modulus
    return if (remainder < 0) remainder + modulus else remainder
}
