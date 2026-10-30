package com.ramish.sadiq.core.engine

/**
 * The numbers of §3.6, in one place.
 *
 * They are constants, not settings: anything the user can change lives in
 * [com.ramish.sadiq.core.model.GuardConfig], and anything the star economy decides lives in
 * [com.ramish.sadiq.core.economy.EconomyRules]. Every value here is asserted by
 * `RulesConstantsTest`, so changing one is a deliberate act that breaks a test first.
 */
object EngineRules {
    /** Milliseconds in a minute. Every duration below is expressed through it. */
    const val MINUTE_MS: Long = 60_000L

    /** "Yes, I need it" chips, filtered by the remaining budget before they are shown. */
    val STAY_CHIPS_MIN: List<Int> = listOf(2, 5, 10, 15)

    /** A surface shows at most four Stay chips: the fitting ones plus "all n min". */
    const val MAX_STAY_CHIPS: Int = 4

    /** During a prayer window Stay is capped at five minutes; it is never withheld. */
    const val PRAYER_STAY_MAX_MIN: Int = 5

    /** In the morning window the only Stay option is two minutes, and it must be held. */
    const val MORNING_STAY_MIN: Int = 2

    /** How long a chip must be held before a Morning Stay or an irreversible choice commits. */
    const val HOLD_TO_CONFIRM_MS: Long = 2_000L

    /** One quick minute: the escape hatch from a rest, a protected time or time-up. */
    const val QUICK_NEED_MS: Long = MINUTE_MS

    /** Quick minutes available per logical day. */
    const val QUICK_NEEDS_PER_DAY: Int = 3

    /** Grace after leaving a guarded app mid-grant before the grant closes as LEFT. */
    const val HOLD_GRACE_MS: Long = 3 * MINUTE_MS

    /** How long a "Save the rest" grant stays resumable. */
    const val PAUSED_MAX_MS: Long = 60 * MINUTE_MS

    /** Guarded opens inside this many minutes that trigger the check-in. */
    const val REFLECT_OPENS: Int = 6

    /** The window over which [REFLECT_OPENS] opens are counted. */
    const val REFLECT_WINDOW_MS: Long = 20 * MINUTE_MS

    /** No check-in within this long after the previous one. */
    const val REFLECT_COOLDOWN_MS: Long = 60 * MINUTE_MS

    /** A budget below this counts as exhausted, so "0 min left" never becomes a one-second Stay. */
    const val BUDGET_EPSILON_MS: Long = MINUTE_MS

    /** Focus lengths offered on the Focus tab and in the check-in. */
    val FOCUS_CHOICES_MIN: List<Int> = listOf(5, 15, 25, 45, 60, 90)

    /** The focus length pre-selected before the user picks one. */
    const val FOCUS_DEFAULT_MIN: Int = 25

    /** How long the rain plays for "Wind down with rain". */
    const val WIND_DOWN_MS: Long = 20 * MINUTE_MS

    /** Free-scroll chips, filtered by today's balance, plus "all". */
    val FREE_CHIPS_MIN: List<Int> = listOf(5, 10)

    /**
     * How long the surface presenter waits for `InterceptActivity` to report itself visible before
     * it asks the accessibility service for the overlay fallback (§3.5). It lives here with the rest
     * of the timing table so the app layer has one place to read and the test can assert it.
     */
    const val OVERLAY_FALLBACK_MS: Long = 600L

    /** How long a self-test arm survives before it expires, so a forgotten test guards nothing later. */
    const val SELF_TEST_ARM_MS: Long = MINUTE_MS

    // ── "Open anyway" (§A.0–A.4) ────────────────────────────────────────────────────────────────

    /** What the door gives: ten minutes, fixed. Never longer, never a second hold. */
    const val OVERRIDE_MS: Long = 10 * MINUTE_MS

    /** How many times the door may open per logical day. */
    const val OVERRIDE_DAY_CAP: Int = 2

    /** How many times it may open per Bedtime night; it is never offered twice in one gate. */
    const val OVERRIDE_NIGHT_CAP: Int = 1

    /** The wait before the hold, by day. */
    const val OVERRIDE_WAIT_MS: Long = 30_000L

    /** The wait before the hold at Bedtime: slower, because 2 AM is when he is least able to say no. */
    const val OVERRIDE_WAIT_NIGHT_MS: Long = 60_000L
}
