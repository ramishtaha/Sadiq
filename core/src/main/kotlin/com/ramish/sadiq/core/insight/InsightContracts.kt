package com.ramish.sadiq.core.insight

/**
 * The check-in trigger (§3.6 rule 6, §3.8 caps).
 *
 * The policy is pure and takes the open times it is given; the engine keeps `recentOpensWall`.
 */
interface ReflectionPolicy {
    /**
     * How many guarded opens fall inside the reflection window ending at [nowWall].
     *
     * @param opensWall the recent guarded opens, oldest first.
     * @param nowWall the instant to count back from.
     */
    fun opensInWindow(opensWall: List<Long>, nowWall: Long): Int

    /**
     * Whether a guarded open should show the check-in instead of the pause.
     *
     * @param opensWall the recent guarded opens, oldest first.
     * @param lastReflectWall when the last check-in was answered, or null.
     * @param nowWall the instant of the open.
     * @param enabled whether the user left check-ins on.
     */
    fun shouldReflect(
        opensWall: List<Long>,
        lastReflectWall: Long?,
        nowWall: Long,
        enabled: Boolean
    ): Boolean
}

/** Chooses the single next action for a surface. First match wins, in the order of §4.4. */
interface NextActionSuggester {
    /** @param ctx everything the choice may depend on. */
    fun suggest(ctx: SuggestionContext): Suggestion
}

/** Builds the weekly review from the material the data layer assembles. */
interface WeeklyReviewBuilder {
    /** @param input the week's usage, stars, opens, reflections and settings. */
    fun build(input: WeeklyInput): WeeklyReview
}
