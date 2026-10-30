package com.ramish.sadiq.core.insight

import com.ramish.sadiq.core.model.WindowKind

/** §4.4: an adhan fifteen minutes away is close enough to name. */
private const val PRAYER_SOON_MIN: Int = 15

/** §4.4: "Pick one thing. 5 minutes." is a five-minute thing. */
private const val MORNING_ONE_THING_MIN: Int = 5

/** §4.4: the gym hint starts thirty minutes after Maghrib. */
private const val GYM_SOON_FROM_MIN: Int = 30

/** §4.4: at sixty minutes the hint becomes "Gym hour." */
private const val GYM_HOUR_FROM_MIN: Int = 60

/** §4.4: two hours after Maghrib the gym hint is over. */
private const val GYM_HOUR_END_MIN: Int = 120

private const val MINUTE_MS: Long = 60_000L

/** §4.4's fallback lines, in the order the rotation walks them. */
private val GENERIC_ROTATION: List<SuggestionKey> = listOf(
    SuggestionKey.GENERIC_WATER,
    SuggestionKey.GENERIC_WINDOW,
    SuggestionKey.GENERIC_BREATHE,
    SuggestionKey.GENERIC_ONE_THING,
    SuggestionKey.GENERIC_TEXT_BACK,
    SuggestionKey.GENERIC_STRETCH,
)

/**
 * The "next better action" engine of §4.4.
 *
 * First match wins, in the plan's order: a prayer window, an adhan within fifteen minutes, the sleep gate,
 * the morning window, the two gym hours after Maghrib, then a deterministic rotation through six small
 * human things. Every branch is a fact taken from the context — a window that is open, a prayer the
 * schedule computed, a Maghrib that happened — so nothing urgent is invented and nothing counts down that
 * the context does not contain.
 *
 * One precedence is deliberate: when [SuggestionContext.reason] is set, the reason's line is returned
 * before any window line. The reason exists only because he answered the REFLECT card (T17), and
 * replacing his answer with "Maghrib in 12 minutes" would throw away the thing he just said.
 */
class DefaultNextActionSuggester : NextActionSuggester {

    override fun suggest(ctx: SuggestionContext): Suggestion {
        val reason: ReflectionReason? = ctx.reason
        // A null line (NEED) falls through to the window lines rather than showing an empty answer.
        if (reason != null) forReason(reason, ctx.window.kind)?.let { return it }

        if (ctx.window.kind == WindowKind.PRAYER) {
            val prayer = ctx.window.prayer
            // A prayer window without a name is a broken context; falling through is more honest than
            // putting a nameless "It's {}. Wudu first?" on screen.
            if (prayer != null) return Suggestion(key = SuggestionKey.PRAYER_NOW, prayer = prayer)
        }

        prayerSoon(ctx)?.let { return it }
        if (ctx.window.kind == WindowKind.SLEEP_GATE) return sleepGate(ctx)
        if (ctx.window.kind == WindowKind.MORNING) return morning()
        gym(ctx)?.let { return it }
        return rotation(ctx)
    }

    /** The response line for the chip he tapped on the REFLECT card (§4.4). */
    private fun forReason(reason: ReflectionReason, window: WindowKind): Suggestion? = when (reason) {
        ReflectionReason.BORED -> Suggestion(key = SuggestionKey.REFLECT_BORED)
        ReflectionReason.TIRED -> Suggestion(
            key = SuggestionKey.REFLECT_TIRED,
            // At night the honest next action is sleep, so the line carries the wind-down option.
            action = if (window == WindowKind.SLEEP_GATE) SuggestionAction.WIND_DOWN else SuggestionAction.NONE,
        )
        ReflectionReason.AVOIDING -> Suggestion(
            key = SuggestionKey.REFLECT_AVOIDING,
            action = SuggestionAction.START_FOCUS_5,
        )
        ReflectionReason.LONELY -> Suggestion(key = SuggestionKey.REFLECT_LONELY)
        ReflectionReason.ANXIOUS -> Suggestion(key = SuggestionKey.REFLECT_ANXIOUS)
        ReflectionReason.HABIT -> Suggestion(key = SuggestionKey.REFLECT_HABIT)
        // "I need it" is the override's reason, not a check-in answer: there is no response line to
        // give, and the confirm view already states the cost. Silence is the honest reply (OV-2).
        ReflectionReason.NEED -> null
    }

    /**
     * "{Prayer} in {n} minutes." for an adhan inside the next fifteen.
     *
     * A missing prayer or time means the schedule did not answer, so the branch stays silent. An adhan
     * that has already passed is not announced as upcoming either: "in 0 minutes" is a false urgency,
     * and the prayer window's own branch is the one that speaks once the adhan is in.
     */
    private fun prayerSoon(ctx: SuggestionContext): Suggestion? {
        val prayer = ctx.nextPrayer ?: return null
        val adhanWall = ctx.nextPrayerWall ?: return null
        val minutes = minutesUntil(ctx.nowWall, adhanWall) ?: return null
        if (minutes > PRAYER_SOON_MIN) return null
        return Suggestion(key = SuggestionKey.PRAYER_SOON, prayer = prayer, minutes = minutes, atWall = adhanWall)
    }

    /**
     * "Fajr is at {time}." inside the gate.
     *
     * `nextFajrWall` is the schedule's answer; a zero or already-passed value is not an answer, so the
     * window's own end is used when it is ahead (§3.7 defines the gate as ending at Fajr, so the two are
     * the same instant). If neither is ahead the key still returns with no time: the surface keeps the
     * key's copy rather than inventing a time, and a past Fajr is never shown as if it were ahead.
     */
    private fun sleepGate(ctx: SuggestionContext): Suggestion {
        val fajrWall: Long? = if (ctx.nextFajrWall > ctx.nowWall) {
            ctx.nextFajrWall
        } else {
            ctx.window.endWall?.takeIf { it > ctx.nowWall }
        }
        return Suggestion(
            key = SuggestionKey.SLEEP_FAJR,
            minutes = fajrWall?.let { minutesUntil(ctx.nowWall, it) },
            atWall = fajrWall,
        )
    }

    /** The morning's one line, with the only action the morning surface accepts (T13). */
    private fun morning(): Suggestion = Suggestion(
        key = SuggestionKey.MORNING_ONE_THING,
        minutes = MORNING_ONE_THING_MIN,
        action = SuggestionAction.START_FOCUS_5,
    )

    /**
     * The gym hint lives in the two hours after Maghrib: "in a bit" for the first hour, "Gym hour." for
     * the second. It respects one flag, because `gymHintEnabled` is the switch that means "gym nudges";
     * leaving half the hint on after he turned it off would be a nudge he did not ask for.
     *
     * No countdown is attached: §4.4's gym lines carry no number, and inventing one would be the kind of
     * precision the rest of the app refuses to fake.
     */
    private fun gym(ctx: SuggestionContext): Suggestion? {
        if (!ctx.gymHintEnabled) return null
        if (ctx.maghribWall <= 0L || ctx.nowWall < ctx.maghribWall) return null
        val sinceMaghribMin: Int = ((ctx.nowWall - ctx.maghribWall) / MINUTE_MS).toInt()
        return when {
            sinceMaghribMin < GYM_SOON_FROM_MIN -> null
            sinceMaghribMin < GYM_HOUR_FROM_MIN -> Suggestion(key = SuggestionKey.GYM_SOON)
            sinceMaghribMin < GYM_HOUR_END_MIN -> Suggestion(key = SuggestionKey.GYM_NOW)
            else -> null
        }
    }

    /**
     * Deterministic rotation through §4.4's six fallback lines by (logical day, open index).
     *
     * The same day and the same open always produce the same line, and consecutive opens walk the list so
     * he does not read the same sentence every time. `floorMod` keeps the slot sane for a caller that
     * hands in a negative day or index.
     */
    private fun rotation(ctx: SuggestionContext): Suggestion {
        val slot: Int = floorMod(ctx.logicalDay.toLong() + ctx.openIndexToday.toLong(), GENERIC_ROTATION.size)
        return Suggestion(key = GENERIC_ROTATION[slot])
    }
}

/**
 * Whole minutes until [targetWall], rounded up, or null when the target is not in the future.
 *
 * Rounding up keeps the line true: "Maghrib in 12 minutes" at 11 min 30 s is the next whole minute he can
 * act on, never an earlier claim than the clock supports.
 */
private fun minutesUntil(nowWall: Long, targetWall: Long): Int? {
    if (targetWall <= nowWall) return null
    val minutes: Long = (targetWall - nowWall + MINUTE_MS - 1) / MINUTE_MS
    return minutes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

/** Non-negative remainder, so a negative slot still lands on a line. */
private fun floorMod(value: Long, modulus: Int): Int {
    val remainder: Int = (value % modulus).toInt()
    return if (remainder < 0) remainder + modulus else remainder
}
