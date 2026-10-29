package com.ramish.sadiq.data.settings

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import com.ramish.sadiq.contract.ThemeMode
import com.ramish.sadiq.contract.UiPrefs
import com.ramish.sadiq.core.model.CalcMethod
import com.ramish.sadiq.core.model.GuardConfig
import com.ramish.sadiq.core.model.GuardedApp
import com.ramish.sadiq.core.model.Madhab
import com.ramish.sadiq.core.model.Mode
import com.ramish.sadiq.core.model.MorningConfig
import com.ramish.sadiq.core.model.PrayerConfig
import com.ramish.sadiq.core.model.RamadanConfig
import com.ramish.sadiq.core.model.RamadanMode
import com.ramish.sadiq.core.model.SleepGateConfig
import com.ramish.sadiq.core.model.StrictConfig
import com.ramish.sadiq.core.sound.SoundKind

/**
 * Pure translation between the `settings` DataStore and the two contract shapes the app reads:
 * [UiPrefs] for the UI and [GuardConfig] for the engine.
 *
 * Rules that hold here and nowhere else:
 * - A missing key means the plan's default (§3.4); an unreadable enum value means the same default,
 *   so a hand-edited or downgraded store can never crash startup.
 * - Ranges the plan fixes are clamped on read, because the engine computes with them.
 * - Guarded apps and domains are *not* in DataStore: they live in Room, so they are passed in and
 *   returned separately. [writeConfig] therefore never touches them.
 */
object PrefsMapping {

    private val DEFAULT_PRAYER = PrayerConfig()

    // ── UiPrefs ─────────────────────────────────────────────────────────────────────────────────

    fun readUiPrefs(prefs: Preferences): UiPrefs = UiPrefs(
        themeMode = readEnum(prefs, SettingsKeys.THEME_MODE, ThemeMode.SYSTEM),
        reduceMotion = prefs[SettingsKeys.REDUCE_MOTION] ?: false,
        onboardingDone = prefs[SettingsKeys.ONBOARDING_DONE] ?: false,
        onboardingStep = (prefs[SettingsKeys.ONBOARDING_STEP] ?: 0).coerceAtLeast(0),
        keepAlive = prefs[SettingsKeys.KEEP_ALIVE] ?: true,
        weeklyNotif = prefs[SettingsKeys.WEEKLY_REVIEW_NOTIF] ?: true,
        focusDefaultMin = prefs[SettingsKeys.FOCUS_DEFAULT_MIN] ?: 25,
        focusDefaultSound = readEnum(prefs, SettingsKeys.FOCUS_DEFAULT_SOUND, SoundKind.NONE),
        recentsLockedConfirmed = prefs[SettingsKeys.RECENTS_LOCKED_CONFIRMED] ?: false,
        autostartConfirmed = prefs[SettingsKeys.AUTOSTART_CONFIRMED] ?: false,
        batterySaverConfirmed = prefs[SettingsKeys.BATTERY_SAVER_CONFIRMED] ?: false,
        skySeed = prefs[SettingsKeys.SKY_SEED] ?: SettingsKeys.NO_SKY_SEED,
        welcomeStarGiven = prefs[SettingsKeys.WELCOME_STAR_GIVEN] ?: false,
    )

    fun writeUiPrefs(target: MutablePreferences, prefs: UiPrefs) {
        target[SettingsKeys.THEME_MODE] = prefs.themeMode.name
        target[SettingsKeys.REDUCE_MOTION] = prefs.reduceMotion
        target[SettingsKeys.ONBOARDING_DONE] = prefs.onboardingDone
        target[SettingsKeys.ONBOARDING_STEP] = prefs.onboardingStep.coerceAtLeast(0)
        target[SettingsKeys.KEEP_ALIVE] = prefs.keepAlive
        target[SettingsKeys.WEEKLY_REVIEW_NOTIF] = prefs.weeklyNotif
        target[SettingsKeys.FOCUS_DEFAULT_MIN] = prefs.focusDefaultMin
        target[SettingsKeys.FOCUS_DEFAULT_SOUND] = prefs.focusDefaultSound.name
        target[SettingsKeys.RECENTS_LOCKED_CONFIRMED] = prefs.recentsLockedConfirmed
        target[SettingsKeys.AUTOSTART_CONFIRMED] = prefs.autostartConfirmed
        target[SettingsKeys.BATTERY_SAVER_CONFIRMED] = prefs.batterySaverConfirmed
        target[SettingsKeys.WELCOME_STAR_GIVEN] = prefs.welcomeStarGiven
        // Zero means "not assigned yet"; writing it would stop the seed from ever being created.
        if (prefs.skySeed != SettingsKeys.NO_SKY_SEED) {
            target[SettingsKeys.SKY_SEED] = prefs.skySeed
        }
    }

    // ── GuardConfig ─────────────────────────────────────────────────────────────────────────────

    fun readConfig(prefs: Preferences, apps: List<GuardedApp>, domains: Set<String>): GuardConfig = GuardConfig(
        mode = readEnum(prefs, SettingsKeys.MODE, Mode.BALANCED),
        apps = apps,
        domains = domains,
        prayer = PrayerConfig(
            lat = prefs[SettingsKeys.PRAYER_LAT] ?: DEFAULT_PRAYER.lat,
            lon = prefs[SettingsKeys.PRAYER_LON] ?: DEFAULT_PRAYER.lon,
            place = prefs[SettingsKeys.PRAYER_PLACE] ?: DEFAULT_PRAYER.place,
            method = readEnum(prefs, SettingsKeys.PRAYER_METHOD, CalcMethod.KARACHI),
            madhab = readEnum(prefs, SettingsKeys.PRAYER_MADHAB, Madhab.HANAFI),
            windowMin = (prefs[SettingsKeys.PRAYER_WINDOW_MIN] ?: DEFAULT_PRAYER.windowMin)
                .coerceIn(SettingsKeys.PRAYER_WINDOW_MIN_RANGE.first, SettingsKeys.PRAYER_WINDOW_MIN_RANGE.last),
            jumuahWindowMin = (prefs[SettingsKeys.JUMUAH_WINDOW_MIN] ?: DEFAULT_PRAYER.jumuahWindowMin)
                .coerceIn(0, MAX_WINDOW_MIN),
        ),
        sleepGate = SleepGateConfig(
            enabled = prefs[SettingsKeys.SLEEP_GATE_ENABLED] ?: true,
            startMinuteOfDay = (prefs[SettingsKeys.SLEEP_GATE_START_MIN] ?: 1350).coerceIn(0, LAST_MINUTE_OF_DAY),
        ),
        morning = MorningConfig(
            enabled = prefs[SettingsKeys.MORNING_ENABLED] ?: true,
            endMinuteOfDay = (prefs[SettingsKeys.MORNING_END_MIN] ?: 570).coerceIn(0, LAST_MINUTE_OF_DAY),
        ),
        ramadan = RamadanConfig(
            mode = readEnum(prefs, SettingsKeys.RAMADAN_MODE, RamadanMode.AUTO_SUGGEST),
            hijriOffsetDays = (prefs[SettingsKeys.HIJRI_OFFSET_DAYS] ?: 0)
                .coerceIn(SettingsKeys.HIJRI_OFFSET_DAYS_RANGE.first, SettingsKeys.HIJRI_OFFSET_DAYS_RANGE.last),
        ),
        strict = StrictConfig(
            enabled = prefs[SettingsKeys.STRICT_ENABLED] ?: false,
            delayMin = (prefs[SettingsKeys.STRICT_DELAY_MIN] ?: 10).coerceAtLeast(0),
        ),
        reflectionEnabled = prefs[SettingsKeys.REFLECTION_ENABLED] ?: true,
        gymHintEnabled = prefs[SettingsKeys.GYM_HINT_ENABLED] ?: true,
        webGuardEnabled = prefs[SettingsKeys.WEB_GUARD_ENABLED] ?: false,
    )

    /** Writes every DataStore-backed part of [config]. Apps and domains stay in Room. */
    fun writeConfig(target: MutablePreferences, config: GuardConfig) {
        writeMode(target, config.mode)
        writePrayer(target, config.prayer)
        writeSleepGate(target, config.sleepGate)
        writeMorning(target, config.morning)
        writeRamadan(target, config.ramadan)
        writeStrict(target, config.strict)
        writeFlags(
            target = target,
            reflection = config.reflectionEnabled,
            gymHint = config.gymHintEnabled,
            webGuard = config.webGuardEnabled,
        )
    }

    // ── Section writers, one per SettingsChange ─────────────────────────────────────────────────

    fun writeMode(target: MutablePreferences, mode: Mode) {
        target[SettingsKeys.MODE] = mode.name
    }

    fun writePrayer(target: MutablePreferences, config: PrayerConfig) {
        target[SettingsKeys.PRAYER_LAT] = config.lat
        target[SettingsKeys.PRAYER_LON] = config.lon
        target[SettingsKeys.PRAYER_PLACE] = config.place
        target[SettingsKeys.PRAYER_METHOD] = config.method.name
        target[SettingsKeys.PRAYER_MADHAB] = config.madhab.name
        target[SettingsKeys.PRAYER_WINDOW_MIN] = config.windowMin.coerceIn(
            SettingsKeys.PRAYER_WINDOW_MIN_RANGE.first,
            SettingsKeys.PRAYER_WINDOW_MIN_RANGE.last,
        )
        target[SettingsKeys.JUMUAH_WINDOW_MIN] = config.jumuahWindowMin.coerceIn(0, MAX_WINDOW_MIN)
    }

    fun writeSleepGate(target: MutablePreferences, config: SleepGateConfig) {
        target[SettingsKeys.SLEEP_GATE_ENABLED] = config.enabled
        target[SettingsKeys.SLEEP_GATE_START_MIN] = config.startMinuteOfDay.coerceIn(0, LAST_MINUTE_OF_DAY)
    }

    fun writeMorning(target: MutablePreferences, config: MorningConfig) {
        target[SettingsKeys.MORNING_ENABLED] = config.enabled
        target[SettingsKeys.MORNING_END_MIN] = config.endMinuteOfDay.coerceIn(0, LAST_MINUTE_OF_DAY)
    }

    fun writeRamadan(target: MutablePreferences, config: RamadanConfig) {
        target[SettingsKeys.RAMADAN_MODE] = config.mode.name
        target[SettingsKeys.HIJRI_OFFSET_DAYS] = config.hijriOffsetDays.coerceIn(
            SettingsKeys.HIJRI_OFFSET_DAYS_RANGE.first,
            SettingsKeys.HIJRI_OFFSET_DAYS_RANGE.last,
        )
    }

    fun writeStrict(target: MutablePreferences, config: StrictConfig) {
        target[SettingsKeys.STRICT_ENABLED] = config.enabled
        target[SettingsKeys.STRICT_DELAY_MIN] = config.delayMin.coerceAtLeast(0)
    }

    /** Flags are applied one at a time: a null in [com.ramish.sadiq.core.strict.SettingsChange.SetFlags] means "leave it". */
    fun writeFlags(target: MutablePreferences, reflection: Boolean?, gymHint: Boolean?, webGuard: Boolean?) {
        reflection?.let { target[SettingsKeys.REFLECTION_ENABLED] = it }
        gymHint?.let { target[SettingsKeys.GYM_HINT_ENABLED] = it }
        webGuard?.let { target[SettingsKeys.WEB_GUARD_ENABLED] = it }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────

    private inline fun <reified T : Enum<T>> readEnum(
        prefs: Preferences,
        key: Preferences.Key<String>,
        default: T,
    ): T {
        val stored = prefs[key] ?: return default
        return enumValues<T>().firstOrNull { it.name == stored } ?: default
    }

    private const val LAST_MINUTE_OF_DAY = 1439
    private const val MAX_WINDOW_MIN = 180
}
