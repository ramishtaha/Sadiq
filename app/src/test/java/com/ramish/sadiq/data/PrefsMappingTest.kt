package com.ramish.sadiq.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.preferencesOf
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
import com.ramish.sadiq.data.settings.PrefsMapping
import com.ramish.sadiq.data.settings.SettingsKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * §3.4's defaults, ranges and enum parsing, checked through the pure mapping — no DataStore, no
 * clock, no Android.
 */
class PrefsMappingTest {

    @Test
    fun uiPrefsDefaults_matchPlan() {
        val prefs = PrefsMapping.readUiPrefs(preferencesOf())

        assertEquals(ThemeMode.SYSTEM, prefs.themeMode)
        assertFalse(prefs.reduceMotion)
        assertFalse(prefs.onboardingDone)
        assertEquals(0, prefs.onboardingStep)
        assertTrue(prefs.keepAlive)
        assertTrue(prefs.weeklyNotif)
        assertEquals(25, prefs.focusDefaultMin)
        assertEquals(SoundKind.NONE, prefs.focusDefaultSound)
        assertFalse(prefs.recentsLockedConfirmed)
        assertFalse(prefs.autostartConfirmed)
        assertFalse(prefs.batterySaverConfirmed)
        assertEquals(0L, prefs.skySeed)
        assertFalse(prefs.welcomeStarGiven)
    }

    @Test
    fun uiPrefsRoundTrip_keepsEveryField() {
        val original = UiPrefs(
            themeMode = ThemeMode.SEPIA,
            reduceMotion = true,
            onboardingDone = true,
            onboardingStep = 3,
            keepAlive = false,
            weeklyNotif = false,
            focusDefaultMin = 45,
            focusDefaultSound = SoundKind.NIGHT_PAD,
            recentsLockedConfirmed = true,
            autostartConfirmed = true,
            batterySaverConfirmed = true,
            skySeed = 8_675_309L,
            welcomeStarGiven = true,
        )
        val target = mutablePreferencesOf()

        PrefsMapping.writeUiPrefs(target, original)

        assertEquals(original, PrefsMapping.readUiPrefs(target))
    }

    @Test
    fun uiPrefsWrite_leavesSkySeedUnwrittenWhenUnassigned() {
        val target = mutablePreferencesOf()

        PrefsMapping.writeUiPrefs(target, PrefsMapping.readUiPrefs(preferencesOf()).copy(themeMode = ThemeMode.DARK))

        assertFalse(target.contains(SettingsKeys.SKY_SEED))
        assertEquals(ThemeMode.DARK, PrefsMapping.readUiPrefs(target).themeMode)
    }

    @Test
    fun configDefaults_matchPlan() {
        val config = PrefsMapping.readConfig(preferencesOf(), apps = emptyList(), domains = emptySet())

        assertEquals(GuardConfig(), config)
        assertEquals(19.2183, config.prayer.lat)
        assertEquals(72.9781, config.prayer.lon)
        assertEquals("Thane, Maharashtra", config.prayer.place)
        assertEquals(20, config.prayer.windowMin)
        assertEquals(1350, config.sleepGate.startMinuteOfDay)
        assertEquals(570, config.morning.endMinuteOfDay)
        assertTrue(config.reflectionEnabled)
        assertTrue(config.gymHintEnabled)
        assertFalse(config.webGuardEnabled)
    }

    @Test
    fun configRoundTrip_keepsEveryDataStoreBackedField() {
        val original = GuardConfig(
            mode = Mode.HARDCORE,
            apps = listOf(GuardedApp(packageName = "com.instagram.android", label = "Instagram")),
            domains = setOf("instagram.com", "x.com"),
            prayer = PrayerConfig(
                lat = 21.1458,
                lon = 79.0882,
                place = "Nagpur, Maharashtra",
                method = CalcMethod.MUSLIM_WORLD_LEAGUE,
                madhab = Madhab.SHAFI,
                windowMin = 30,
                jumuahWindowMin = 45,
            ),
            sleepGate = SleepGateConfig(enabled = false, startMinuteOfDay = 1380),
            morning = MorningConfig(enabled = false, endMinuteOfDay = 600),
            ramadan = RamadanConfig(mode = RamadanMode.ON, hijriOffsetDays = 1),
            strict = StrictConfig(enabled = true, delayMin = 15),
            reflectionEnabled = false,
            gymHintEnabled = false,
            webGuardEnabled = true,
        )
        val target = mutablePreferencesOf()

        PrefsMapping.writeConfig(target, original)
        val read = PrefsMapping.readConfig(target, original.apps, original.domains)

        assertEquals(original, read)
    }

    @Test
    fun configRead_clampsPrayerWindowAndHijriOffset() {
        val stored: MutablePreferences = mutablePreferencesOf().apply {
            this[SettingsKeys.PRAYER_WINDOW_MIN] = 99
            this[SettingsKeys.HIJRI_OFFSET_DAYS] = 9
            this[SettingsKeys.SLEEP_GATE_START_MIN] = -5
            this[SettingsKeys.MORNING_END_MIN] = 5000
        }

        val config = PrefsMapping.readConfig(stored, apps = emptyList(), domains = emptySet())

        assertEquals(40, config.prayer.windowMin)
        assertEquals(2, config.ramadan.hijriOffsetDays)
        assertEquals(0, config.sleepGate.startMinuteOfDay)
        assertEquals(1439, config.morning.endMinuteOfDay)
    }

    @Test
    fun configWrite_clampsValuesItStores() {
        val target = mutablePreferencesOf()

        PrefsMapping.writePrayer(
            target,
            PrayerConfig(windowMin = 90, jumuahWindowMin = 500),
        )
        PrefsMapping.writeRamadan(target, RamadanConfig(hijriOffsetDays = -7))

        assertEquals(40, target[SettingsKeys.PRAYER_WINDOW_MIN])
        assertEquals(180, target[SettingsKeys.JUMUAH_WINDOW_MIN])
        assertEquals(-2, target[SettingsKeys.HIJRI_OFFSET_DAYS])
    }

    @Test
    fun unknownEnumNames_fallBackToDefaults() {
        val stored: MutablePreferences = mutablePreferencesOf().apply {
            this[SettingsKeys.THEME_MODE] = "PURPLE"
            this[SettingsKeys.MODE] = "TURBO"
            this[SettingsKeys.PRAYER_METHOD] = "MARS"
            this[SettingsKeys.FOCUS_DEFAULT_SOUND] = "THUNDER"
        }

        val uiPrefs = PrefsMapping.readUiPrefs(stored)
        val config = PrefsMapping.readConfig(stored, apps = emptyList(), domains = emptySet())

        assertEquals(ThemeMode.SYSTEM, uiPrefs.themeMode)
        assertEquals(SoundKind.NONE, uiPrefs.focusDefaultSound)
        assertEquals(Mode.BALANCED, config.mode)
        assertEquals(CalcMethod.KARACHI, config.prayer.method)
    }

    @Test
    fun guardedAppsAndDomains_comeFromTablesAndAreNeverStoredInPreferences() {
        val apps = listOf(
            GuardedApp(packageName = "com.instagram.android", label = "Instagram"),
            GuardedApp(packageName = "com.google.android.youtube", label = "YouTube"),
        )
        val domains = setOf("instagram.com")
        val target = mutablePreferencesOf()

        PrefsMapping.writeConfig(target, GuardConfig(apps = apps, domains = domains))
        val config = PrefsMapping.readConfig(target, apps, domains)

        assertEquals(apps, config.apps)
        assertEquals(domains, config.domains)
        val keyNames = target.asMap().keys.map { it.name }
        assertFalse(keyNames.any { it.startsWith("guarded_") })
    }

    @Test
    fun sectionWriters_touchOnlyTheirOwnKeys() {
        val target = mutablePreferencesOf()
        PrefsMapping.writeMode(target, Mode.GENTLE)

        assertEquals(Mode.GENTLE.name, target[SettingsKeys.MODE])
        assertEquals(setOf(SettingsKeys.MODE.name), target.asMap().keys.map { it.name }.toSet())

        PrefsMapping.writeFlags(target, reflection = null, gymHint = false, webGuard = null)

        assertEquals(Mode.GENTLE.name, target[SettingsKeys.MODE])
        assertFalse(target[SettingsKeys.GYM_HINT_ENABLED] ?: true)
        assertFalse(target.contains(SettingsKeys.REFLECTION_ENABLED))
        assertFalse(target.contains(SettingsKeys.WEB_GUARD_ENABLED))
    }
}
