package com.ramish.sadiq.ui.intercept

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.KingBed
import androidx.compose.material.icons.filled.Mosque
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.ramish.sadiq.R
import com.ramish.sadiq.core.engine.Choice
import com.ramish.sadiq.core.engine.EngineRules
import com.ramish.sadiq.core.engine.GrantKind
import com.ramish.sadiq.core.engine.InterceptUi
import com.ramish.sadiq.core.engine.SurfaceKind
import com.ramish.sadiq.core.insight.ReflectionReason
import com.ramish.sadiq.core.insight.Suggestion
import com.ramish.sadiq.core.insight.SuggestionKey
import com.ramish.sadiq.core.model.Mode
import com.ramish.sadiq.core.model.PrayerName
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pure logic that maps [InterceptUi] into the strings, icon and options of the interception surface.
 *
 * The class is named `InterceptCopy` to match its remit: it only holds copy rules and never any
 * rendering or state itself. Everything here is testable without Compose.
 *
 * Two invariants drive every function below (addendum §1.3, voice rule 1):
 *
 * - **A line is only shown with the facts that make it true.** Minutes come from the field that
 *   carries them (TIME_UP's `grantedMinutes` is the spent grant's length, the Bedtime clock is the
 *   wall clock the surface is drawn with, and every duration is measured from that same instant).
 *   When a fact is missing the line is dropped, never filled with a zero.
 * - **An option only appears when the engine will accept it.** [primaryChoice], [secondaryChoice]
 *   and [tertiaryChoice] are the single source of the answers the screen submits, and each label
 *   function returns null in exactly the cases the engine would refuse the matching [Choice].
 */
@Singleton
class InterceptCopy @Inject constructor(@ApplicationContext private val context: Context) {

    // ── headline ──────────────────────────────────────────────────────────────────────────────

    /**
     * The headline for the surface.
     *
     * @param nowWall the wall clock the surface is drawn with; the Bedtime headline states it.
     * @param grantedMinutes the length of the grant that just ran out, for TIME_UP. The engine
     *   keeps the spent grant until the surface is answered, so this is always known for a real
     *   time-up; when it is absent the headline states no number rather than "0 minutes".
     */
    fun headline(
        ui: InterceptUi,
        nowWall: Long = System.currentTimeMillis(),
        grantedMinutes: Int? = null,
    ): String = when (ui.kind) {
        // The question is the headline on every open. A leave confirmation is a separate, local
        // state of the screen (`leaveConfirmation()`), never the resting headline.
        SurfaceKind.PAUSE, SurfaceKind.REFLECT -> context.getString(R.string.intercept_prompt_headline)

        SurfaceKind.TIME_UP -> when (ui.timeUpKind) {
            GrantKind.FREE -> context.getString(R.string.intercept_time_up_free_headline)
            GrantKind.QUICK -> context.getString(R.string.intercept_time_up_quick_headline)
            // A stay is a stay, whether it came from the prayer window or from the budget.
            null, GrantKind.STAY, GrantKind.PRAYER_STAY ->
                grantedMinutes?.takeIf { it > 0 }?.let { minutes ->
                    context.getString(R.string.intercept_time_up_headline, minutes)
                } ?: context.getString(R.string.intercept_time_up_headline_no_number)
        }

        SurfaceKind.FOCUS_GUARD -> context.getString(R.string.intercept_focus_guard_headline)

        SurfaceKind.SLEEP_GATE -> context.getString(
            R.string.intercept_bedtime_headline,
            formatTime(nowWall)
        )

        SurfaceKind.MORNING -> context.getString(R.string.intercept_morning_headline)

        SurfaceKind.PRAYER -> {
            val prayer = ui.prayer
            when {
                ui.isJumuah -> context.getString(R.string.intercept_prayer_headline_jumuah)
                prayer != null -> context.getString(R.string.intercept_prayer_headline, prayer.label())
                // No prayer name in the prompt: ask the question rather than name nothing.
                else -> context.getString(R.string.intercept_prompt_headline)
            }
        }

        SurfaceKind.COOLDOWN -> {
            val minutes = ((ui.restRemainingMs ?: 0L) / EngineRules.MINUTE_MS).toInt()
            if (minutes >= 1) {
                context.getString(R.string.intercept_cooldown_headline, minutes)
            } else {
                context.getString(R.string.intercept_cooldown_headline_short)
            }
        }

        SurfaceKind.TEST -> context.getString(R.string.intercept_test_headline)
    }

    // ── subtitle ──────────────────────────────────────────────────────────────────────────────

    /**
     * The subtitle, or null when the surface has nothing true to add.
     *
     * @param appLabel the app's own label; the prayer and test lines name the app, never the package.
     * @param nowWall the wall clock, so "That's 5 h 41 m from now" is measured from the same instant
     *   the Bedtime headline states.
     */
    fun subtitle(
        ui: InterceptUi,
        appLabel: String = ui.packageName,
        nowWall: Long = System.currentTimeMillis(),
    ): String? = when (ui.kind) {
        SurfaceKind.PAUSE -> mapSuggestion(ui.suggestion)

        SurfaceKind.REFLECT -> {
            val reason = ui.reflectReason
            when {
                reason != null -> mapReflectionResponse(reason)
                ui.reflectionOpens != null ->
                    context.getString(R.string.intercept_reflect_subtitle, ui.reflectionOpens)
                else -> context.getString(R.string.intercept_reflect_subtitle_plain)
            }
        }

        SurfaceKind.TIME_UP -> when (ui.timeUpKind) {
            GrantKind.FREE -> context.getString(R.string.intercept_time_up_free_subtitle)
            GrantKind.QUICK -> null
            // "…or choose a little more" is only true while the chips are actually there.
            null, GrantKind.STAY, GrantKind.PRAYER_STAY ->
                if (ui.stayOptionsMin.isEmpty()) {
                    null
                } else {
                    context.getString(R.string.intercept_time_up_subtitle)
                }
        }

        SurfaceKind.FOCUS_GUARD -> ui.focusRemainingMs?.let { remaining ->
            // Rounded up: "0 min left" is the one number a running focus cannot have.
            context.getString(R.string.intercept_focus_guard_subtitle, minutesLeft(remaining))
        }

        SurfaceKind.SLEEP_GATE -> ui.nextFajrWall?.let { fajr ->
            context.getString(
                R.string.intercept_bedtime_subtitle,
                formatTime(fajr),
                formatDuration((fajr - nowWall).coerceAtLeast(0L))
            )
        }

        SurfaceKind.MORNING -> context.getString(R.string.intercept_morning_subtitle)

        SurfaceKind.PRAYER -> context.getString(R.string.intercept_prayer_subtitle, appLabel)

        SurfaceKind.COOLDOWN -> mapSuggestion(ui.suggestion)

        SurfaceKind.TEST -> context.getString(R.string.intercept_test_subtitle, appLabel)
    }

    // ── buttons and the answers they send ─────────────────────────────────────────────────────

    /** The primary button's label. Every surface has one; rule zero is that Leave always exists. */
    fun primaryButton(ui: InterceptUi): String = when (ui.kind) {
        SurfaceKind.PAUSE, SurfaceKind.REFLECT -> context.getString(R.string.intercept_leave_button)
        SurfaceKind.TIME_UP -> when (ui.timeUpKind) {
            GrantKind.FREE -> context.getString(R.string.intercept_time_up_free_primary_button)
            GrantKind.QUICK -> context.getString(R.string.intercept_time_up_quick_primary_button)
            null, GrantKind.STAY, GrantKind.PRAYER_STAY ->
                context.getString(R.string.intercept_time_up_leave_button)
        }
        SurfaceKind.SLEEP_GATE -> context.getString(R.string.intercept_bedtime_primary_button)
        SurfaceKind.MORNING -> context.getString(R.string.intercept_morning_primary_button)
        SurfaceKind.PRAYER -> context.getString(R.string.intercept_prayer_primary_button)
        SurfaceKind.COOLDOWN -> context.getString(R.string.intercept_cooldown_primary_button)
        SurfaceKind.FOCUS_GUARD -> context.getString(R.string.intercept_focus_guard_primary_button)
        SurfaceKind.TEST -> context.getString(R.string.intercept_test_primary_button)
    }

    /**
     * The [Choice] the primary button submits.
     *
     * PRAYER is the case this exists for: "I'll pray first" leaves, and the honest yes sits on the
     * secondary. MORNING's primary starts the five-minute focus it names.
     */
    fun primaryChoice(ui: InterceptUi): Choice = when (ui.kind) {
        SurfaceKind.PAUSE,
        SurfaceKind.REFLECT,
        SurfaceKind.PRAYER,
        SurfaceKind.COOLDOWN,
        SurfaceKind.SLEEP_GATE,
        SurfaceKind.TIME_UP,
        SurfaceKind.TEST,
        -> Choice.Leave

        SurfaceKind.MORNING -> Choice.StartFocusNow(FOCUS_MIN)
        SurfaceKind.FOCUS_GUARD -> Choice.BackToFocus
    }

    /** The primary button's TalkBack description, or null when the label says it all. */
    fun primaryButtonDescription(ui: InterceptUi): String? = when (ui.kind) {
        SurfaceKind.PAUSE, SurfaceKind.REFLECT -> context.getString(R.string.intercept_leave_button_description)
        SurfaceKind.TIME_UP -> context.getString(R.string.intercept_time_up_leave_button_description)
        SurfaceKind.MORNING -> context.getString(R.string.intercept_morning_primary_button_description)
        SurfaceKind.PRAYER -> context.getString(R.string.intercept_prayer_primary_button_description)
        SurfaceKind.COOLDOWN -> context.getString(R.string.intercept_cooldown_primary_button_description)
        SurfaceKind.TEST -> context.getString(R.string.intercept_test_primary_button_description)
        SurfaceKind.SLEEP_GATE, SurfaceKind.FOCUS_GUARD -> null
    }

    /**
     * The secondary button's label, or null when the surface has no second option.
     *
     * On PAUSE, REFLECT, PRAYER and TIME_UP the second option is the stay chips, so the label only
     * appears while [InterceptUi.stayOptionsMin] is non-empty — "A little more" with nothing to
     * choose is a dead offer. The chips themselves submit [Choice.Stay] with their own minutes.
     */
    fun secondaryButton(ui: InterceptUi): String? = when (ui.kind) {
        SurfaceKind.PAUSE, SurfaceKind.REFLECT, SurfaceKind.PRAYER, SurfaceKind.TIME_UP ->
            if (ui.stayOptionsMin.isEmpty()) null else context.getString(R.string.intercept_stay_button)

        SurfaceKind.MORNING -> context.getString(R.string.intercept_morning_secondary_button)
        SurfaceKind.SLEEP_GATE -> context.getString(R.string.intercept_sleep_gate_secondary_button)
        SurfaceKind.FOCUS_GUARD -> context.getString(R.string.intercept_focus_guard_secondary_button)
        SurfaceKind.COOLDOWN, SurfaceKind.TEST -> null
    }

    /** The [Choice] the secondary button submits, or null when the chips carry the answer. */
    fun secondaryChoice(ui: InterceptUi): Choice? = when (ui.kind) {
        SurfaceKind.MORNING -> Choice.Leave
        SurfaceKind.SLEEP_GATE -> Choice.WindDown
        SurfaceKind.FOCUS_GUARD -> Choice.EndFocus
        SurfaceKind.PAUSE,
        SurfaceKind.REFLECT,
        SurfaceKind.PRAYER,
        SurfaceKind.TIME_UP,
        SurfaceKind.COOLDOWN,
        SurfaceKind.TEST,
        -> null
    }

    /**
     * The one-minute hold's label, or null when no minute is left.
     *
     * A "… · 0 left" button is the classic dead control: the engine refuses [Choice.QuickNeed] at
     * zero, so the option is not drawn at all.
     */
    fun tertiaryButton(ui: InterceptUi): String? {
        val left = quickNeedsLeft(ui) ?: return null
        return when (ui.kind) {
            SurfaceKind.COOLDOWN -> context.getString(R.string.intercept_cooldown_tertiary_button, left)
            SurfaceKind.SLEEP_GATE -> context.getString(R.string.intercept_bedtime_tertiary_button, left)
            SurfaceKind.FOCUS_GUARD -> context.getString(R.string.intercept_focus_guard_tertiary_button, left)
            else -> null
        }
    }

    /** The [Choice] the one-minute hold submits, or null when it is not offered. */
    fun tertiaryChoice(ui: InterceptUi): Choice? = if (quickNeedsLeft(ui) == null) null else Choice.QuickNeed

    // ── small option labels ───────────────────────────────────────────────────────────────────

    /** The line above the stay chips: the plan's own words for "yes, for how long". */
    fun forHowLong(): String = context.getString(R.string.intercept_for_how_long)

    /** A stay chip: the number of minutes it sends. */
    fun stayChipLabel(minutes: Int): String = context.getString(R.string.intercept_n_min, minutes)

    /** A free-scroll chip: the slice of today's balance it spends. */
    fun freeScrollChipLabel(minutes: Int): String =
        context.getString(R.string.intercept_free_scroll_chip, minutes)

    /** The morning's hold-to-confirm stay. */
    fun holdForMinutes(minutes: Int): String =
        context.getString(R.string.intercept_hold_for_n_minutes, minutes)

    /** The check-in's "Start 5-min focus" pill. */
    fun startFocusFiveChoice(): Choice = Choice.StartFocusNow(REFLECT_FOCUS_MIN)

    /** The 400 ms confirmation that replaces the headline for one beat after Leave. */
    fun leaveConfirmation(): String =
        context.getString(R.string.intercept_leave_confirm_bloom_headline)

    // ── caption ──────────────────────────────────────────────────────────────────────────────

    /**
     * The caption, or null when there is nothing true to state.
     *
     * @param nowWall the wall clock, so "Focus ends at 16:40" is the instant the focus really ends.
     */
    fun caption(ui: InterceptUi, nowWall: Long = System.currentTimeMillis()): String? =
        when (ui.kind) {
            SurfaceKind.PAUSE, SurfaceKind.REFLECT -> ui.budgetRemainingMs?.let { budget ->
                val mode = ui.mode.label()
                if (budget >= EngineRules.MINUTE_MS) {
                    context.getString(
                        R.string.intercept_pause_caption,
                        mode,
                        (budget / EngineRules.MINUTE_MS).toInt()
                    )
                } else {
                    context.getString(R.string.intercept_pause_caption_mode, mode)
                }
            }

            SurfaceKind.TIME_UP -> timeUpCaption(ui)

            SurfaceKind.FOCUS_GUARD -> ui.focusRemainingMs?.let { remaining ->
                context.getString(R.string.intercept_focus_guard_caption, formatTime(nowWall + remaining))
            }

            SurfaceKind.SLEEP_GATE -> context.getString(R.string.intercept_bedtime_caption)

            SurfaceKind.MORNING -> ui.windowEndWall?.let { end ->
                context.getString(R.string.intercept_morning_caption, formatTime(end))
            }

            SurfaceKind.PRAYER -> context.getString(R.string.intercept_prayer_caption)

            SurfaceKind.COOLDOWN -> ui.restEndsWall?.let { end ->
                context.getString(R.string.intercept_cooldown_caption, formatTime(end))
            }

            SurfaceKind.TEST -> context.getString(R.string.intercept_test_caption)
        }

    /**
     * TIME_UP's caption states what happens after this, and only that.
     *
     * A rest is started by the engine the moment an exhausted STAY runs out (T21), so the line is
     * chosen from the budget: exhausted → the rest that is now running; budget left → how much is
     * left before one; FREE or QUICK → nothing, because neither ends in a rest.
     */
    private fun timeUpCaption(ui: InterceptUi): String? = when (ui.timeUpKind) {
        GrantKind.FREE, GrantKind.QUICK -> null

        null, GrantKind.STAY, GrantKind.PRAYER_STAY -> {
            val budget = ui.budgetRemainingMs
            val restEnds = ui.restEndsWall
            when {
                budget != null && budget < EngineRules.BUDGET_EPSILON_MS ->
                    context.getString(R.string.intercept_time_up_caption_rest, ui.mode.cooldownMin)

                ui.restRemainingMs != null && restEnds != null ->
                    context.getString(R.string.intercept_cooldown_caption, formatTime(restEnds))

                budget != null && budget >= EngineRules.MINUTE_MS ->
                    context.getString(
                        R.string.intercept_time_up_caption_before_rest,
                        (budget / EngineRules.MINUTE_MS).toInt()
                    )

                else -> context.getString(R.string.intercept_time_up_caption_rest, ui.mode.cooldownMin)
            }
        }
    }

    // ── reflection ───────────────────────────────────────────────────────────────────────────

    /**
     * A check-in answer's response: the next thing to try, never the chip read back.
     *
     * The chip already said "Bored"; repeating it tells him nothing. The response lines come from
     * the plan's §4.4 response table.
     */
    fun mapReflectionResponse(reason: ReflectionReason): String = context.getString(
        when (reason) {
            ReflectionReason.BORED -> R.string.intercept_reflect_response_bored
            ReflectionReason.TIRED -> R.string.intercept_reflect_response_tired
            ReflectionReason.AVOIDING -> R.string.intercept_reflect_response_avoiding
            ReflectionReason.LONELY -> R.string.intercept_reflect_response_lonely
            ReflectionReason.ANXIOUS -> R.string.intercept_reflect_response_anxious
            ReflectionReason.HABIT -> R.string.intercept_reflect_response_habit
        }
    )

    /** Maps reflection reasons to chip labels. */
    fun reflectionChipLabel(reason: ReflectionReason): String =
        context.getString(reflectionChipLabelRes(reason))

    /** The string resource behind [reflectionChipLabel], for callers that resolve text themselves. */
    @StringRes
    fun reflectionChipLabelRes(reason: ReflectionReason): Int = when (reason) {
        ReflectionReason.BORED -> R.string.intercept_reflect_chip_bored
        ReflectionReason.TIRED -> R.string.intercept_reflect_chip_tired
        ReflectionReason.AVOIDING -> R.string.intercept_reflect_chip_avoiding
        ReflectionReason.LONELY -> R.string.intercept_reflect_chip_lonely
        ReflectionReason.ANXIOUS -> R.string.intercept_reflect_chip_anxious
        ReflectionReason.HABIT -> R.string.intercept_reflect_chip_habit
    }

    // ── glyph ────────────────────────────────────────────────────────────────────────────────

    /** Returns the icon and tint the surface's hero glyph is drawn with. */
    fun glyph(ui: InterceptUi, colorScheme: ColorScheme): InterceptGlyphSpec = when (ui.kind) {
        SurfaceKind.PAUSE, SurfaceKind.REFLECT, SurfaceKind.TIME_UP, SurfaceKind.TEST ->
            InterceptGlyphSpec(imageVector = Icons.Default.Star, tint = colorScheme.primary)

        SurfaceKind.PRAYER -> InterceptGlyphSpec(imageVector = Icons.Default.Mosque, tint = colorScheme.primary)
        SurfaceKind.MORNING -> InterceptGlyphSpec(imageVector = Icons.Default.WbTwilight, tint = colorScheme.primary)
        SurfaceKind.COOLDOWN -> InterceptGlyphSpec(imageVector = Icons.Default.HourglassBottom, tint = colorScheme.primary)
        SurfaceKind.SLEEP_GATE -> InterceptGlyphSpec(imageVector = Icons.Default.KingBed, tint = colorScheme.tertiary)
        SurfaceKind.FOCUS_GUARD -> InterceptGlyphSpec(imageVector = Icons.Default.Star, tint = colorScheme.primary)
    }

    // ── internals ────────────────────────────────────────────────────────────────────────────

    /** The one-minute count, or null when the engine would refuse the hold. */
    private fun quickNeedsLeft(ui: InterceptUi): Int? = ui.quickNeedsLeft?.takeIf { it > 0 }

    /** Minutes, rounded up, never below one: a running window always has at least a minute left. */
    private fun minutesLeft(remainingMs: Long): Int =
        ((remainingMs + EngineRules.MINUTE_MS - 1) / EngineRules.MINUTE_MS).toInt().coerceAtLeast(1)

    /** Maps [Mode] to its string label. */
    private fun Mode.label(): String = when (this) {
        Mode.GENTLE -> context.getString(R.string.mode_gentle)
        Mode.BALANCED -> context.getString(R.string.mode_steady)
        Mode.HARDCORE -> context.getString(R.string.mode_firm)
    }

    /** Maps [PrayerName] to its string label. */
    private fun PrayerName.label(): String = when (this) {
        PrayerName.FAJR -> context.getString(R.string.prayer_fajr)
        PrayerName.DHUHR -> context.getString(R.string.prayer_dhuhr)
        PrayerName.ASR -> context.getString(R.string.prayer_asr)
        PrayerName.MAGHRIB -> context.getString(R.string.prayer_maghrib)
        PrayerName.ISHA -> context.getString(R.string.prayer_isha)
    }

    /** The suggestion line, or null when the suggester has nothing that applies. */
    private fun mapSuggestion(suggestion: Suggestion?): String? {
        if (suggestion == null) return null
        return when (suggestion.key) {
            SuggestionKey.PRAYER_NOW -> context.getString(
                R.string.intercept_suggestion_prayer_now,
                suggestion.prayer?.label() ?: ""
            )

            SuggestionKey.PRAYER_SOON -> context.getString(
                R.string.intercept_suggestion_prayer_soon,
                suggestion.prayer?.label() ?: "",
                suggestion.minutes ?: 0
            )

            SuggestionKey.SLEEP_FAJR -> suggestion.atWall?.let {
                context.getString(R.string.intercept_suggestion_sleep_fajr, formatTime(it))
            }

            SuggestionKey.MORNING_ONE_THING -> context.getString(R.string.intercept_suggestion_morning_one_thing)
            SuggestionKey.GYM_SOON -> context.getString(R.string.intercept_suggestion_gym_soon)
            SuggestionKey.GYM_NOW -> context.getString(R.string.intercept_suggestion_gym_now)

            // A reflect suggestion is the check-in's own line, so it reads as a response, not a chip.
            SuggestionKey.REFLECT_TIRED -> mapReflectionResponse(ReflectionReason.TIRED)
            SuggestionKey.REFLECT_AVOIDING -> mapReflectionResponse(ReflectionReason.AVOIDING)
            SuggestionKey.REFLECT_BORED -> mapReflectionResponse(ReflectionReason.BORED)
            SuggestionKey.REFLECT_LONELY -> mapReflectionResponse(ReflectionReason.LONELY)
            SuggestionKey.REFLECT_ANXIOUS -> mapReflectionResponse(ReflectionReason.ANXIOUS)
            SuggestionKey.REFLECT_HABIT -> mapReflectionResponse(ReflectionReason.HABIT)

            SuggestionKey.GENERIC_WATER -> context.getString(R.string.intercept_suggestion_water)
            SuggestionKey.GENERIC_WINDOW -> context.getString(R.string.intercept_suggestion_window)
            SuggestionKey.GENERIC_BREATHE -> context.getString(R.string.intercept_suggestion_breathe)
            SuggestionKey.GENERIC_ONE_THING -> context.getString(R.string.intercept_suggestion_one_thing)
            SuggestionKey.GENERIC_TEXT_BACK -> context.getString(R.string.intercept_suggestion_text_back)
            SuggestionKey.GENERIC_STRETCH -> context.getString(R.string.intercept_suggestion_stretch)
        }
    }

    /** Formats a wall clock time into a short localized time string. */
    private fun formatTime(wallMs: Long): String =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
            .withLocale(Locale.getDefault())
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(wallMs))

    /** Formats a duration in milliseconds into a human-readable string. */
    private fun formatDuration(durationMs: Long): String {
        val totalMinutes = durationMs / EngineRules.MINUTE_MS
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60

        return when {
            hours > 0 && minutes > 0 -> context.getString(R.string.duration_hours_minutes, hours, minutes)
            hours > 0 -> context.getString(R.string.duration_hours, hours)
            minutes > 0 -> context.getString(R.string.duration_minutes, minutes)
            else -> context.getString(R.string.duration_less_than_minute)
        }
    }

    /** Forbidden words to be checked in strings_*.xml files. */
    val forbiddenWords: Set<String> = setOf(
        "block", "blocked", "fail", "failed", "addict", "waste", "wasted", "guilty", "shame",
        "streak", "lost", "relapse", "punish", "are you sure", "warning", "limit reached",
        "lie", "lied", "liar", "lying", "dishonest", "be honest", "promise", "your word", "broke",
        "break your", "cheat", "sin", "sinful", "haram", "hypocrite", "munafiq", "lock", "locks",
        "locked"
    )

    /** Whitelisted files for "lock" words (e.g., HyperOS OEM strings). */
    val lockWhitelistFiles: Set<String> = setOf("strings_onboarding_oem.xml")

    private companion object {
        /** The morning's one thing (plan §4.4: "Pick one thing. 5 minutes."). */
        const val FOCUS_MIN: Int = 5

        /** The check-in's focus pill (plan §4.4: "Start 5-min focus"). */
        const val REFLECT_FOCUS_MIN: Int = 5
    }
}

/**
 * The hero glyph of a surface, as data: which icon and which tint.
 *
 * Kept separate from the `InterceptGlyph` composable so the glyph choice stays testable without
 * Compose, while the drawing (size, semantics) lives in one place in the UI.
 *
 * @property imageVector the icon.
 * @property tint the colour it is drawn in.
 */
data class InterceptGlyphSpec(
    val imageVector: ImageVector,
    val tint: Color
)
