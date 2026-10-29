package com.ramish.sadiq.notify

/**
 * Every live line and countdown number a Sadiq notification can show, computed without Android so the
 * formats are pinned by [LiveTextTest] rather than by a screenshot.
 *
 * The dynamic parts live here instead of `strings_notify.xml` because they are language-invariant
 * shapes from §4.6 ("4m", "3:12", "Instagram · 5 min"); static labels ("I'm done", channel names) stay
 * in resources where the rest of the copy lives. The separator between an app name and its minutes is
 * a middle dot with spaces, exactly as the spec writes it.
 */
object LiveText {

    private const val SECOND_MS: Long = 1_000L
    private const val MINUTE_MS: Long = 60 * SECOND_MS

    /**
     * "3:12" for three minutes twelve seconds; "1:02:05" once an hour is on the clock.
     *
     * A negative remainder reads as "0:00": the countdown never shows a number that would alarm.
     *
     * @param remainingMs time left, which may be zero or, briefly, negative.
     */
    fun clock(remainingMs: Long): String {
        val totalSeconds = remainingMs.coerceAtLeast(0L) / SECOND_MS
        val hours = totalSeconds / 3_600
        val minutes = (totalSeconds % 3_600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0L) {
            "$hours:${twoDigits(minutes)}:${twoDigits(seconds)}"
        } else {
            "$minutes:${twoDigits(seconds)}"
        }
    }

    /**
     * "4m": the short critical text of a promoted Live Update. Rounded up, so the chip never claims
     * less time than is actually left.
     *
     * @param remainingMs time left.
     */
    fun shortRemaining(remainingMs: Long): String = "${minutesLeft(remainingMs)}m"

    /**
     * Whole minutes, rounded up: 3 min 12 s is "4" because the fourth minute has begun.
     *
     * @param durationMs a remainder or a granted length.
     */
    fun minutesLeft(durationMs: Long): Int {
        val ms = durationMs.coerceAtLeast(0L)
        return ((ms + MINUTE_MS - 1) / MINUTE_MS).toInt()
    }

    /**
     * "Instagram · 5 min": the title of a running grant.
     *
     * @param appLabel the app's own name, as the launcher shows it.
     * @param grantedMs the length the user chose.
     */
    fun grantTitle(appLabel: String, grantedMs: Long): String = "$appLabel · ${minutesLeft(grantedMs)} min"

    /**
     * "Focus · 25 min": the title of a running focus session.
     *
     * @param plannedMs the length the user chose.
     */
    fun focusTitle(plannedMs: Long): String = "Focus · ${minutesLeft(plannedMs)} min"

    /**
     * "3:12 left · paused": a grant held because the user left the app or the screen went off.
     *
     * @param remainingMs what is left of the grant.
     */
    fun heldTitle(remainingMs: Long): String = "${clock(remainingMs)} left · paused"

    /**
     * "3:12 saved for later": a grant the user paused on purpose, which is a different promise from
     * being interrupted (name addendum §1.4).
     *
     * @param remainingMs what is left of the grant.
     */
    fun savedTitle(remainingMs: Long): String = "${clock(remainingMs)} saved for later"

    /**
     * "+5 stars in your sky" / "+1 star in your sky". The caller only posts this when at least one
     * star lit, so zero is not a legal input and is rendered as the plural for safety.
     *
     * @param stars how many stars the focus session lit.
     */
    fun starsLine(stars: Int): String =
        if (stars == 1) "+1 star in your sky" else "+$stars stars in your sky"

    /**
     * How full the promoted chip's single progress segment is, 0..100.
     *
     * It sits beside the clock formats because it is the same remainder seen a second way. A total of
     * zero reads as 0 rather than dividing by it, and an over-spent grant is clamped to a full bar.
     *
     * @param elapsedMs how much of the chosen time is already spent.
     * @param totalMs the chosen time.
     */
    fun progressPercent(elapsedMs: Long, totalMs: Long): Int {
        if (totalMs <= 0L) return 0
        val spent = elapsedMs.coerceIn(0L, totalMs)
        return ((spent * 100) / totalMs).toInt()
    }

    /**
     * "Guarding 5 apps" for the keep-alive line; "Guarding your apps" when the caller has no count,
     * which keeps the sentence true instead of printing a number it does not know.
     *
     * @param appCount how many apps are guarded today, or null when that is not known here.
     */
    fun guardedLine(appCount: Int?): String = when (appCount) {
        null -> "Guarding your apps"
        1 -> "Guarding 1 app"
        else -> "Guarding $appCount apps"
    }

    /** Minutes and seconds are always two digits, so a countdown does not jitter as it changes. */
    private fun twoDigits(value: Long): String = if (value < 10L) "0$value" else value.toString()
}
