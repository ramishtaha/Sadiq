package com.ramish.sadiq.guard

import com.ramish.sadiq.core.model.GuardConfig
import com.ramish.sadiq.core.model.GuardContext
import com.ramish.sadiq.core.schedule.ScheduleEvaluator
import com.ramish.sadiq.core.time.TimeSnapshot
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the [GuardContext] for one engine step (§3.5).
 *
 * The assembler exists for two reasons. It keeps the controller from knowing how the schedule is
 * computed, and it makes the one input that is easy to get wrong explicit: [lastSeenWall] is the
 * wall time of the *previous* input, not the current one. That is what lets the evaluator report a
 * sleep gate that ended while the phone was in a drawer, so an untouched night still earns its
 * quiet-night star (§3.7).
 *
 * Nothing here is cached. Windows, the logical day and the next prayer are all functions of the
 * clock, and a context that outlived its instant would decide a surface for the wrong minute.
 */
@Singleton
class ContextAssembler @Inject constructor(
    private val schedule: ScheduleEvaluator,
) {

    /**
     * The context at [now].
     *
     * @param now both clocks plus the zone.
     * @param config the settings in force, read at this instant.
     * @param lastSeenWall the wall time of the previous input, or the current wall time on a start
     *   with no saved state — in which case no window has "ended since".
     */
    fun assemble(now: TimeSnapshot, config: GuardConfig, lastSeenWall: Long): GuardContext =
        schedule.contextAt(now = now, config = config, lastSeenWall = lastSeenWall)
}
