package com.ramish.sadiq.ui.intercept

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.ramish.sadiq.R
import com.ramish.sadiq.core.engine.EngineRules
import com.ramish.sadiq.core.engine.GrantKind
import com.ramish.sadiq.core.engine.InterceptUi
import com.ramish.sadiq.core.engine.SurfaceKind
import com.ramish.sadiq.core.insight.ReflectionReason
import com.ramish.sadiq.core.insight.Suggestion
import com.ramish.sadiq.core.insight.SuggestionAction
import com.ramish.sadiq.core.insight.SuggestionKey
import com.ramish.sadiq.core.model.Mode
import com.ramish.sadiq.core.model.PrayerName
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.KingBed
import androidx.compose.material.icons.filled.Mosque
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.WbTwilight
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pure logic that maps [InterceptUi] into the strings, icon and options of the interception surface.
 *
 * The class is named `InterceptCopy` to match its remit: it only holds copy rules and never any
 * rendering or state itself. Everything here is testable without Compose.
 */
@Singleton
class InterceptCopy @Inject constructor(@ApplicationContext private val context: Context) {

    /**
     * Maps the headline for each surface kind. When a bloom is happening (on leaving), it overrides
     * the standard headline.
     */
    fun headline(ui: InterceptUi): String = when (ui.kind) {
        SurfaceKind.PAUSE, SurfaceKind.REFLECT -> if (isBloomHappening(ui)) {
            context.getString(R.string.intercept_leave_confirm_bloom_headline)
        } else {
            context.getString(R.string.intercept_prompt_headline)
        }

        SurfaceKind.TIME_UP -> when (ui.timeUpKind) {
            null, GrantKind.STAY -> context.getString(
                R.string.intercept_time_up_headline,
                (ui.resumeRemainingMs ?: 0) / EngineRules.MINUTE_MS
            )
            GrantKind.FREE -> context.getString(R.string.intercept_time_up_free_headline)
            GrantKind.QUICK -> context.getString(R.string.intercept_time_up_quick_headline)
            else -> "" // Should not happen given the logic in InterceptUiBuilder
        }

        SurfaceKind.FOCUS_GUARD -> context.getString(R.string.intercept_focus_guard_headline)
        SurfaceKind.SLEEP_GATE -> context.getString(
            R.string.intercept_bedtime_headline,
            formatTime(ui.windowEndWall ?: 0)
        )

        SurfaceKind.MORNING -> context.getString(R.string.intercept_morning_headline)
        SurfaceKind.PRAYER -> context.getString(
            R.string.intercept_prayer_headline,
            ui.prayer?.label()
        )

        SurfaceKind.COOLDOWN -> context.getString(
            R.string.intercept_cooldown_headline,
            (ui.restRemainingMs ?: 0) / EngineRules.MINUTE_MS
        )

        SurfaceKind.TEST -> context.getString(R.string.intercept_test_headline)
    }

    /** Maps the subtitle for each surface kind. */
    fun subtitle(ui: InterceptUi): String = when (ui.kind) {
        SurfaceKind.PAUSE -> mapSuggestion(ui.suggestion, ui.packageName)
        SurfaceKind.REFLECT -> {
            val reason = ui.reflectReason
            if (reason == null) {
                context.getString(R.string.intercept_reflect_subtitle, ui.reflectionOpens)
            } else {
                mapReflectionResponse(reason)
            }
        }

        SurfaceKind.TIME_UP -> when (ui.timeUpKind) {
            null, GrantKind.STAY -> context.getString(R.string.intercept_time_up_subtitle)
            GrantKind.FREE -> context.getString(R.string.intercept_time_up_free_subtitle)
            GrantKind.QUICK -> "" // No subtitle for quick time up
            else -> ""
        }

        SurfaceKind.FOCUS_GUARD -> context.getString(
            R.string.intercept_focus_guard_subtitle,
            (ui.focusRemainingMs ?: 0) / EngineRules.MINUTE_MS
        )

        SurfaceKind.SLEEP_GATE -> context.getString(
            R.string.intercept_bedtime_subtitle,
            formatTime(ui.nextFajrWall ?: 0),
            formatDuration((ui.nextFajrWall ?: 0) - (ui.windowEndWall ?: 0))
        )

        SurfaceKind.MORNING -> context.getString(R.string.intercept_morning_subtitle)
        SurfaceKind.PRAYER -> context.getString(
            R.string.intercept_prayer_subtitle,
            ui.packageName // The app label is resolved by the UI
        )

        SurfaceKind.COOLDOWN -> mapSuggestion(ui.suggestion, ui.packageName)
        SurfaceKind.TEST -> context.getString(
            R.string.intercept_test_subtitle,
            ui.packageName // The app label is resolved by the UI
        )
    }

    /** Maps the primary button text for each surface kind. */
    fun primaryButton(ui: InterceptUi): String = when (ui.kind) {
        SurfaceKind.PAUSE, SurfaceKind.REFLECT -> context.getString(R.string.intercept_leave_button)
        SurfaceKind.TIME_UP -> when (ui.timeUpKind) {
            null, GrantKind.STAY -> context.getString(R.string.intercept_time_up_leave_button)
            GrantKind.FREE -> context.getString(R.string.intercept_time_up_free_primary_button)
            GrantKind.QUICK -> context.getString(R.string.intercept_time_up_quick_primary_button)
            else -> ""
        }

        SurfaceKind.SLEEP_GATE -> context.getString(R.string.intercept_bedtime_primary_button)
        SurfaceKind.MORNING -> context.getString(R.string.intercept_morning_primary_button)
        SurfaceKind.PRAYER -> context.getString(R.string.intercept_prayer_primary_button)
        SurfaceKind.COOLDOWN -> context.getString(R.string.intercept_cooldown_primary_button)
        SurfaceKind.FOCUS_GUARD -> context.getString(R.string.intercept_focus_guard_primary_button)
        SurfaceKind.TEST -> context.getString(R.string.intercept_test_primary_button)
    }

    fun primaryButtonDescription(ui: InterceptUi): String = when (ui.kind) {
        SurfaceKind.PAUSE, SurfaceKind.REFLECT -> context.getString(R.string.intercept_leave_button_description)
        SurfaceKind.TIME_UP -> context.getString(R.string.intercept_time_up_leave_button_description)
        SurfaceKind.MORNING -> context.getString(R.string.intercept_morning_secondary_button_description)
        SurfaceKind.PRAYER -> context.getString(R.string.intercept_prayer_primary_button_description)
        SurfaceKind.COOLDOWN -> context.getString(R.string.intercept_cooldown_primary_button_description)
        SurfaceKind.TEST -> context.getString(R.string.intercept_test_primary_button_description)
        else -> ""
    }

    /** Maps the stay chip text. */
    fun stayChip(minutes: Int): String =
        context.getString(R.string.intercept_stay_button)

    /** Maps the resume button text. */
    fun resumeButton(ui: InterceptUi): String =
        context.getString(R.string.intercept_resume_button, (ui.resumeRemainingMs ?: 0) / EngineRules.MINUTE_MS)

    /** Maps the free scroll button text. */
    fun freeScrollButton(ui: InterceptUi): String =
        context.getString(R.string.intercept_cooldown_tertiary_button_free, (ui.freeScrollAvailableMs ?: 0) / EngineRules.MINUTE_MS)

    /** Maps the quick need button text. */
    fun quickNeedButton(ui: InterceptUi): String = when (ui.kind) {
        SurfaceKind.SLEEP_GATE -> context.getString(R.string.intercept_bedtime_tertiary_button, ui.quickNeedsLeft)
        SurfaceKind.COOLDOWN, SurfaceKind.TIME_UP -> context.getString(R.string.intercept_cooldown_tertiary_button, ui.quickNeedsLeft)
        else -> context.getString(R.string.intercept_focus_guard_tertiary_button)
    }

    /** Maps the caption for each surface kind. */
    fun caption(ui: InterceptUi): String = when (ui.kind) {
        SurfaceKind.PAUSE, SurfaceKind.REFLECT -> context.getString(
            R.string.intercept_pause_caption,
            ui.mode.label(),
            (ui.budgetRemainingMs ?: 0) / EngineRules.MINUTE_MS
        )

        SurfaceKind.TIME_UP -> context.getString(
            R.string.intercept_time_up_caption,
            ui.mode.cooldownMin
        )

        SurfaceKind.FOCUS_GUARD -> context.getString(
            R.string.intercept_focus_guard_caption,
            formatTime(ui.restEndsWall ?: 0)
        )

        SurfaceKind.SLEEP_GATE -> context.getString(R.string.intercept_bedtime_caption)
        SurfaceKind.MORNING -> context.getString(
            R.string.intercept_morning_caption,
            formatTime(ui.windowEndWall ?: 0)
        )

        SurfaceKind.PRAYER -> context.getString(R.string.intercept_prayer_caption)
        SurfaceKind.COOLDOWN -> context.getString(
            R.string.intercept_cooldown_caption,
            formatTime(ui.restEndsWall ?: 0)
        )

        SurfaceKind.TEST -> context.getString(R.string.intercept_test_caption)
    }

    /** Maps reflection reasons to their responses. */
    fun mapReflectionResponse(reason: ReflectionReason): String = when (reason) {
        ReflectionReason.BORED -> context.getString(R.string.intercept_reflect_bored_response)
        ReflectionReason.TIRED -> context.getString(R.string.intercept_reflect_tired_response)
        ReflectionReason.AVOIDING -> context.getString(R.string.intercept_reflect_avoiding_response)
        ReflectionReason.LONELY -> context.getString(R.string.intercept_reflect_lonely_response)
        ReflectionReason.ANXIOUS -> context.getString(R.string.intercept_reflect_anxious_response)
        ReflectionReason.HABIT -> context.getString(R.string.intercept_reflect_habit_response)
    }

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

    /** Whether the bloom animation is happening for a leave confirmation. */
    fun isBloomHappening(ui: InterceptUi): Boolean = ui.kind == SurfaceKind.PAUSE && ui.leaveEarnsStar

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

    /**
     * The secondary button's label, or null when the surface offers no second option.
     *
     * PAUSE and REFLECT offer "Yes, I need it" (the chips underneath carry the lengths); PRAYER
     * reuses the same wording because praying first is also a stay; TIME_UP offers "A little more".
     */
    fun secondaryButton(ui: InterceptUi): String? = when (ui.kind) {
        SurfaceKind.PAUSE, SurfaceKind.REFLECT -> context.getString(R.string.intercept_pause_secondary_button)
        SurfaceKind.PRAYER -> context.getString(R.string.intercept_stay_button)
        SurfaceKind.MORNING -> context.getString(R.string.intercept_morning_secondary_button)
        SurfaceKind.SLEEP_GATE -> context.getString(R.string.intercept_sleep_gate_secondary_button)
        SurfaceKind.FOCUS_GUARD -> context.getString(R.string.intercept_focus_guard_secondary_button)
        SurfaceKind.TIME_UP -> context.getString(R.string.intercept_time_up_secondary_button)
        SurfaceKind.COOLDOWN, SurfaceKind.TEST -> null
    }

    /**
     * The tertiary button's label, or null when the surface offers no third option.
     *
     * It is always the smaller, bounded choice — a free-scroll slice of today's balance, or one
     * quick minute against today's remaining count.
     */
    fun tertiaryButton(ui: InterceptUi): String? = when (ui.kind) {
        SurfaceKind.PAUSE, SurfaceKind.REFLECT, SurfaceKind.TIME_UP ->
            (ui.freeScrollAvailableMs ?: 0L).takeIf { it > 0L }?.let { balance ->
                context.getString(
                    if (ui.kind == SurfaceKind.TIME_UP) {
                        R.string.intercept_time_up_free_scroll_button
                    } else {
                        R.string.intercept_pause_free_scroll_button
                    },
                    balance / EngineRules.MINUTE_MS
                )
            }

        SurfaceKind.PRAYER -> context.getString(
            R.string.intercept_cooldown_tertiary_button_free,
            (ui.freeScrollAvailableMs ?: 0L) / EngineRules.MINUTE_MS
        )

        SurfaceKind.COOLDOWN -> context.getString(R.string.intercept_cooldown_tertiary_button, ui.quickNeedsLeft ?: 0)
        SurfaceKind.SLEEP_GATE -> context.getString(R.string.intercept_bedtime_tertiary_button, ui.quickNeedsLeft ?: 0)
        SurfaceKind.FOCUS_GUARD -> context.getString(R.string.intercept_focus_guard_tertiary_button, ui.quickNeedsLeft ?: 0)
        SurfaceKind.MORNING, SurfaceKind.TEST -> null
    }

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

    /** Maps suggestions to their string. */
    private fun mapSuggestion(suggestion: Suggestion?, appLabel: String): String = when (suggestion?.key) {
        SuggestionKey.PRAYER_NOW -> context.getString(
            R.string.intercept_suggestion_prayer_now,
            suggestion.prayer?.label() ?: ""
        )

        SuggestionKey.PRAYER_SOON -> context.getString(
            R.string.intercept_suggestion_prayer_soon,
            suggestion.prayer?.label() ?: "",
            suggestion.minutes
        )

        SuggestionKey.SLEEP_FAJR -> context.getString(
            R.string.intercept_suggestion_sleep_fajr,
            formatTime(suggestion.atWall ?: 0)
        )

        SuggestionKey.MORNING_ONE_THING -> context.getString(R.string.intercept_suggestion_morning_one_thing)
        SuggestionKey.GYM_SOON -> context.getString(R.string.intercept_suggestion_gym_soon)
        SuggestionKey.GYM_NOW -> context.getString(R.string.intercept_suggestion_gym_now)
        SuggestionKey.REFLECT_TIRED -> context.getString(R.string.intercept_reflect_tired_response)
        SuggestionKey.REFLECT_AVOIDING -> context.getString(R.string.intercept_reflect_avoiding_response)
        SuggestionKey.REFLECT_BORED -> context.getString(R.string.intercept_reflect_bored_response)
        SuggestionKey.REFLECT_LONELY -> context.getString(R.string.intercept_reflect_lonely_response)
        SuggestionKey.REFLECT_ANXIOUS -> context.getString(R.string.intercept_reflect_anxious_response)
        SuggestionKey.REFLECT_HABIT -> context.getString(R.string.intercept_reflect_habit_response)
        SuggestionKey.GENERIC_WATER -> context.getString(R.string.intercept_suggestion_water)
        SuggestionKey.GENERIC_WINDOW -> context.getString(R.string.intercept_suggestion_window)
        SuggestionKey.GENERIC_BREATHE -> context.getString(R.string.intercept_suggestion_breathe)
        SuggestionKey.GENERIC_ONE_THING -> context.getString(R.string.intercept_suggestion_one_thing)
        SuggestionKey.GENERIC_TEXT_BACK -> context.getString(R.string.intercept_suggestion_text_back)
        SuggestionKey.GENERIC_STRETCH -> context.getString(R.string.intercept_suggestion_stretch)
        else -> ""
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
