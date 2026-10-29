package com.ramish.sadiq.core.insight

import com.ramish.sadiq.core.economy.StarAward
import com.ramish.sadiq.core.engine.RecordEvent
import com.ramish.sadiq.core.model.ActiveWindow
import com.ramish.sadiq.core.model.GuardConfig
import com.ramish.sadiq.core.model.Mode
import com.ramish.sadiq.core.model.PrayerName

/** Why the user says he opened the app. One tap, no free text, no judgement (§4.4). */
enum class ReflectionReason {
    BORED,
    TIRED,
    AVOIDING,
    LONELY,
    ANXIOUS,
    HABIT
}

/** An action attached to a suggestion line, if any. */
enum class SuggestionAction {
    NONE,
    START_FOCUS_5,
    WIND_DOWN
}

/**
 * The stable identity of a suggestion line. The copy for each key lives in the UI layer, so the
 * engine and the suggester never hold a user-visible string.
 */
enum class SuggestionKey {
    PRAYER_NOW,
    PRAYER_SOON,
    SLEEP_FAJR,
    MORNING_ONE_THING,
    GYM_SOON,
    GYM_NOW,
    REFLECT_TIRED,
    REFLECT_AVOIDING,
    REFLECT_BORED,
    REFLECT_LONELY,
    REFLECT_ANXIOUS,
    REFLECT_HABIT,
    GENERIC_WATER,
    GENERIC_WINDOW,
    GENERIC_BREATHE,
    GENERIC_ONE_THING,
    GENERIC_TEXT_BACK,
    GENERIC_STRETCH
}

/**
 * One next action, chosen by the suggester and rendered by the surface.
 *
 * @property key which line to show.
 * @property prayer the prayer a prayer line refers to.
 * @property minutes a duration the line can state, e.g. "Focus for 5 minutes".
 * @property atWall a wall time the line can state, e.g. Maghrib's adhan.
 * @property action the button to offer, if any.
 */
data class Suggestion(
    val key: SuggestionKey,
    val prayer: PrayerName? = null,
    val minutes: Int? = null,
    val atWall: Long? = null,
    val action: SuggestionAction = SuggestionAction.NONE
)

/**
 * Everything the suggester may look at. Built by the engine for the surface it is about to show, so
 * the suggester never reads the clock or the settings itself.
 *
 * @property window the window in force.
 * @property nowWall wall time the suggestion is for.
 * @property minuteOfDay minutes since local midnight.
 * @property nextPrayer the nearest upcoming prayer.
 * @property nextPrayerWall its adhan.
 * @property maghribWall today's Maghrib, used by the sleep-gate line.
 * @property nextFajrWall tomorrow's Fajr, used by the sleep-gate line.
 * @property reason the check-in answer, when the suggestion follows one.
 * @property gymHintEnabled whether the user allowed training hints.
 * @property openIndexToday how many guarded opens have happened today, for the generic rotation.
 * @property logicalDay the logical day, for the deterministic rotation.
 */
data class SuggestionContext(
    val window: ActiveWindow,
    val nowWall: Long,
    val minuteOfDay: Int,
    val nextPrayer: PrayerName?,
    val nextPrayerWall: Long?,
    val maghribWall: Long,
    val nextFajrWall: Long,
    val reason: ReflectionReason?,
    val gymHintEnabled: Boolean,
    val openIndexToday: Int,
    val logicalDay: Int
)

/**
 * One row of `usage_days`: foreground time for a package on a logical day.
 *
 * @property logicalDay the logical day the row belongs to.
 * @property packageName the app.
 * @property foregroundMs foreground time in milliseconds.
 * @property opens how many times it came to the front.
 * @property nightMs foreground time that fell inside the sleep gate.
 */
data class UsageDayRow(
    val logicalDay: Int,
    val packageName: String,
    val foregroundMs: Long,
    val opens: Int,
    val nightMs: Long
)

/**
 * All the material for one weekly review. Assembled by the data layer; the builder is pure.
 *
 * @property days the days being reviewed.
 * @property usage usage rows for those days.
 * @property prevUsage usage rows for the comparison week.
 * @property stars the stars lit in those days.
 * @property opens the guarded opens recorded in those days.
 * @property reflections the check-ins answered in those days.
 * @property focusMinutes focus minutes completed in those days.
 * @property quietNights quiet nights counted in those days.
 * @property hourlyMs foreground milliseconds per hour of the day, 24 entries.
 * @property config the settings in force, so a suggestion can suggest a change to them.
 * @property approximate whether any part came from Sadiq's own records instead of usage stats.
 */
data class WeeklyInput(
    val days: IntRange,
    val usage: List<UsageDayRow>,
    val prevUsage: List<UsageDayRow>,
    val stars: List<StarAward>,
    val opens: List<RecordEvent.Open>,
    val reflections: List<RecordEvent.Reflected>,
    val focusMinutes: Int,
    val quietNights: Int,
    val hourlyMs: LongArray,
    val config: GuardConfig,
    val approximate: Boolean
)

/**
 * The pure result of a weekly review: facts first, at most one suggestion.
 *
 * @property totalMs guarded foreground time in the reviewed days.
 * @property deltaMs change against the comparison week, or null when there is nothing to compare.
 * @property topApps up to three package/foreground-millisecond pairs, largest first.
 * @property peakStartHour the start of the busiest two-hour band, or null without data.
 * @property stars stars lit in the reviewed days.
 * @property leaves leave-type stars lit in the reviewed days.
 * @property quietNights quiet nights counted in the reviewed days.
 * @property focusMinutes focus minutes completed in the reviewed days.
 * @property topReason the most common check-in answer, or null.
 * @property suggestion the one change worth considering, or null.
 * @property approximate whether Sadiq is saying "approximate" on the Week screen.
 */
data class WeeklyReview(
    val totalMs: Long,
    val deltaMs: Long?,
    val topApps: List<Pair<String, Long>>,
    val peakStartHour: Int?,
    val stars: Int,
    val leaves: Int,
    val quietNights: Int,
    val focusMinutes: Int,
    val topReason: ReflectionReason?,
    val suggestion: WeeklySuggestion?,
    val approximate: Boolean
)

/** A single, concrete change the Week screen may propose. One per review, never a list of nagging. */
sealed interface WeeklySuggestion {
    /**
     * Move the bedtime gate.
     *
     * @property toMinuteOfDay the new earliest start, in minutes from midnight.
     */
    data class MoveSleepGate(val toMinuteOfDay: Int) : WeeklySuggestion

    /** Switch mode. @property to the mode to switch to. */
    data class SwitchMode(val to: Mode) : WeeklySuggestion

    /** Guard an app that is eating time unguarded. @property packageName the app. */
    data class GuardApp(val packageName: String) : WeeklySuggestion
}
