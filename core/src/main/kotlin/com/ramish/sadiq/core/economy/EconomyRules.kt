package com.ramish.sadiq.core.economy

/**
 * The numbers of §3.8, in one place.
 *
 * Earning rules are named in the KDoc of each constant (E1–E9) so the economy implementation and the
 * tests can be read against the spec table without cross-referencing. Every value is asserted by
 * `RulesConstantsTest`.
 */
object EconomyRules {
    private const val MINUTE_MS: Long = 60_000L

    /** E1/E2: a leave lights its star only after this long with no guarded app in front. */
    const val KINDLE_CONFIRM_MS: Long = 3 * MINUTE_MS

    /** E1–E3 share this rate limit, measured from the last lit leave-type star. */
    const val LEAVE_RATE_LIMIT_MS: Long = 10 * MINUTE_MS

    /** E1–E3 share this many leave-type stars per logical day. */
    const val LEAVE_DAILY_CAP: Int = 12

    /** E4–E6 share this many focus stars per logical day. */
    const val FOCUS_DAILY_CAP: Int = 12

    /** Every star per logical day, excluding WELCOME, against this cap. */
    const val DAILY_CAP: Int = 30

    /** One lit star is worth one minute of free scroll today. */
    const val FREE_MS_PER_STAR: Long = MINUTE_MS

    /** Free scroll is capped at twenty minutes per day, whatever the sky holds. */
    const val FREE_SCROLL_DAILY_MAX_MS: Long = 20 * MINUTE_MS

    /** E3: a grant closed early must have at least this much left, AND at least half of what was granted. */
    const val DONE_EARLY_MIN_REMAINING_MS: Long = 2 * MINUTE_MS

    /** E3: the other half of the "finished early" test — a fraction of `grantedMs`. */
    const val DONE_EARLY_MIN_REMAINING_FRACTION: Double = 0.5

    /** E4: a focus session earns its first star once it reaches five minutes. */
    const val FOCUS_FIRST_STAR_MS: Long = 5 * MINUTE_MS

    /** E5: every full block of this length earns one more star. */
    const val FOCUS_BLOCK_MS: Long = 15 * MINUTE_MS

    /** E6: finishing the planned time earns one more star, but only for sessions this long or longer. */
    const val FOCUS_FINISH_MIN_PLANNED_MS: Long = 25 * MINUTE_MS

    /** E7: a quiet night is worth three stars. */
    const val QUIET_NIGHT_STARS: Int = 3

    /** E8: a quiet morning is worth two stars. */
    const val PROTECTED_MORNING_STARS: Int = 2

    /** E9: the first star, from the onboarding self-test. Outside every cap. */
    const val WELCOME_STARS: Int = 1

    /** E7/E8: a protected window stays quiet with at most this many quick minutes inside it. */
    const val QUIET_MAX_QUICK_NEEDS: Int = 1

    /** Only one kindle may be pending at a time; a guarded foreground fades it (§3.8). */
    const val MAX_PENDING_KINDLES: Int = 1

    /** Quiet-night stars are joined into constellations in groups of this size. */
    const val QUIET_NIGHT_CONSTELLATION_SIZE: Int = 7
}
