package com.ramish.sadiq.core.insight

/** §3.6: six returns inside twenty minutes is a loop, not a visit. */
private const val REFLECT_OPENS: Int = 6

/** §3.6: the window the returns are counted in, 20 minutes. */
private const val REFLECT_WINDOW_MS: Long = 1_200_000L

/** §3.6: after one reflection the question stays quiet for an hour. */
private const val REFLECT_COOLDOWN_MS: Long = 3_600_000L

/**
 * The reflection rule of §3.6.
 *
 * The reflection card is the only surface that asks him to look at the pattern instead of the app, so it
 * has to fire on a real loop and stay quiet otherwise: six opens in twenty minutes, and at most one
 * reflection an hour. Asking twice about the same loop turns a question into nagging, which is the
 * failure this rule exists to avoid.
 *
 * Pure and clock-free: both methods take `nowWall` as an argument, so replaying the same history always
 * produces the same answer.
 */
class DefaultReflectionPolicy : ReflectionPolicy {

    /**
     * Counts the opens inside the twenty minutes ending at [nowWall].
     *
     * The window includes an open exactly [REFLECT_WINDOW_MS] old: an open at 23:00 and one at 23:20 are
     * twenty minutes apart, and "six times in twenty minutes" is true of them. Opens stamped after
     * [nowWall] are ignored rather than trusted — a wall clock that moved backwards must not manufacture
     * a reflection.
     */
    override fun opensInWindow(opensWall: List<Long>, nowWall: Long): Int {
        val windowStart: Long = nowWall - REFLECT_WINDOW_MS
        return opensWall.count { at -> at in windowStart..nowWall }
    }

    /**
     * True when the loop is real and the last reflection is old enough to ask again.
     *
     * [enabled] is checked first: with reflection off the answer is no, whatever the history says. A
     * [lastReflectWall] in the future counts as inside the cooldown, because a clock that moved backwards
     * says nothing about how long he has been quiet.
     */
    override fun shouldReflect(
        opensWall: List<Long>,
        lastReflectWall: Long?,
        nowWall: Long,
        enabled: Boolean,
    ): Boolean {
        if (!enabled) return false
        if (lastReflectWall != null && nowWall - lastReflectWall < REFLECT_COOLDOWN_MS) return false
        return opensInWindow(opensWall, nowWall) >= REFLECT_OPENS
    }
}
