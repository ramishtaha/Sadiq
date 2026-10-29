package com.ramish.sadiq.data.settings

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * The DataStore `settings` file: one key per row of §3.4, with the plan's exact names.
 *
 * Keys are values, not an enum, so other work packages can read the notification bookkeeping keys
 * (`last_attention_notif_at`, `last_weekly_review_day`, `overlay_fallback_*`) without going through
 * a settings API that does not cover them.
 */
object SettingsKeys {

    /** File name; DataStore appends `.preferences_pb` inside `files/datastore`. */
    const val STORE_NAME: String = "settings"

    // ── Appearance and onboarding ────────────────────────────────────────────────────────────────
    val THEME_MODE: Preferences.Key<String> = stringPreferencesKey("theme_mode")
    val REDUCE_MOTION: Preferences.Key<Boolean> = booleanPreferencesKey("reduce_motion")
    val ONBOARDING_DONE: Preferences.Key<Boolean> = booleanPreferencesKey("onboarding_done")
    val ONBOARDING_STEP: Preferences.Key<Int> = intPreferencesKey("onboarding_step")

    // ── Guard ────────────────────────────────────────────────────────────────────────────────────
    val MODE: Preferences.Key<String> = stringPreferencesKey("mode")
    val REFLECTION_ENABLED: Preferences.Key<Boolean> = booleanPreferencesKey("reflection_enabled")
    val GYM_HINT_ENABLED: Preferences.Key<Boolean> = booleanPreferencesKey("gym_hint_enabled")
    val WEB_GUARD_ENABLED: Preferences.Key<Boolean> = booleanPreferencesKey("web_guard_enabled")

    // ── Prayer ───────────────────────────────────────────────────────────────────────────────────
    val PRAYER_LAT: Preferences.Key<Double> = doublePreferencesKey("prayer_lat")
    val PRAYER_LON: Preferences.Key<Double> = doublePreferencesKey("prayer_lon")
    val PRAYER_PLACE: Preferences.Key<String> = stringPreferencesKey("prayer_place")
    val PRAYER_METHOD: Preferences.Key<String> = stringPreferencesKey("prayer_method")
    val PRAYER_MADHAB: Preferences.Key<String> = stringPreferencesKey("prayer_madhab")
    val PRAYER_WINDOW_MIN: Preferences.Key<Int> = intPreferencesKey("prayer_window_min")
    val JUMUAH_WINDOW_MIN: Preferences.Key<Int> = intPreferencesKey("jumuah_window_min")

    // ── Protected times ──────────────────────────────────────────────────────────────────────────
    val SLEEP_GATE_ENABLED: Preferences.Key<Boolean> = booleanPreferencesKey("sleep_gate_enabled")
    val SLEEP_GATE_START_MIN: Preferences.Key<Int> = intPreferencesKey("sleep_gate_start_min")
    val MORNING_ENABLED: Preferences.Key<Boolean> = booleanPreferencesKey("morning_enabled")
    val MORNING_END_MIN: Preferences.Key<Int> = intPreferencesKey("morning_end_min")

    // ── Strict mode and Ramadan (M2) ─────────────────────────────────────────────────────────────
    val STRICT_ENABLED: Preferences.Key<Boolean> = booleanPreferencesKey("strict_enabled")
    val STRICT_DELAY_MIN: Preferences.Key<Int> = intPreferencesKey("strict_delay_min")
    val RAMADAN_MODE: Preferences.Key<String> = stringPreferencesKey("ramadan_mode")
    val HIJRI_OFFSET_DAYS: Preferences.Key<Int> = intPreferencesKey("hijri_offset_days")

    // ── Focus ────────────────────────────────────────────────────────────────────────────────────
    val FOCUS_DEFAULT_MIN: Preferences.Key<Int> = intPreferencesKey("focus_default_min")
    val FOCUS_DEFAULT_SOUND: Preferences.Key<String> = stringPreferencesKey("focus_default_sound")

    // ── Notifications and keep-alive ─────────────────────────────────────────────────────────────
    val WEEKLY_REVIEW_NOTIF: Preferences.Key<Boolean> = booleanPreferencesKey("weekly_review_notif")
    val KEEP_ALIVE: Preferences.Key<Boolean> = booleanPreferencesKey("keep_alive")
    val LAST_ATTENTION_NOTIF_AT: Preferences.Key<Long> = longPreferencesKey("last_attention_notif_at")
    val LAST_WEEKLY_REVIEW_DAY: Preferences.Key<Int> = intPreferencesKey("last_weekly_review_day")

    // ── Self-reported permission steps ───────────────────────────────────────────────────────────
    val RECENTS_LOCKED_CONFIRMED: Preferences.Key<Boolean> = booleanPreferencesKey("recents_locked_confirmed")
    val AUTOSTART_CONFIRMED: Preferences.Key<Boolean> = booleanPreferencesKey("autostart_confirmed")
    val BATTERY_SAVER_CONFIRMED: Preferences.Key<Boolean> = booleanPreferencesKey("battery_saver_confirmed")

    // ── Diagnostics ──────────────────────────────────────────────────────────────────────────────
    val OVERLAY_FALLBACK_DAY: Preferences.Key<Int> = intPreferencesKey("overlay_fallback_day")
    val OVERLAY_FALLBACK_COUNT: Preferences.Key<Int> = intPreferencesKey("overlay_fallback_count")

    // ── Sky ──────────────────────────────────────────────────────────────────────────────────────
    val WELCOME_STAR_GIVEN: Preferences.Key<Boolean> = booleanPreferencesKey("welcome_star_given")
    val SKY_SEED: Preferences.Key<Long> = longPreferencesKey("sky_seed")

    /** Prayer window, minutes either side (§4.5 slider). Values outside are clamped on read. */
    val PRAYER_WINDOW_MIN_RANGE: IntRange = 15..40

    /** Hijri offset the user may apply, in days. */
    val HIJRI_OFFSET_DAYS_RANGE: IntRange = -2..2

    /** `sky_seed` is a random Long; zero is the "not assigned yet" sentinel. */
    const val NO_SKY_SEED: Long = 0L
}
