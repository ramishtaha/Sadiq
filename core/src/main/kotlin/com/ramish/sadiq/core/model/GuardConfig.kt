package com.ramish.sadiq.core.model

import kotlinx.serialization.Serializable

/**
 * One app the user chose to guard.
 *
 * @property packageName the Android package name, e.g. `com.instagram.android`.
 * @property label the launcher label, stored so a list can be rendered without a package query.
 * @property surfaceMode whether the whole app is guarded or only its short-video surface.
 */
@Serializable
data class GuardedApp(
    val packageName: String,
    val label: String,
    val surfaceMode: SurfaceMode = SurfaceMode.WHOLE_APP
)

/**
 * Prayer-time inputs. The defaults are Thane, so a fresh install computes real times before the
 * permission wizard has run (§3.4).
 *
 * @property lat latitude in degrees.
 * @property lon longitude in degrees.
 * @property place the place name shown on the Prayer settings screen.
 * @property method the calculation method.
 * @property madhab the Asr shadow rule.
 * @property windowMin length of a prayer window, in minutes (15–40 in the UI).
 * @property jumuahWindowMin length of the Friday Dhuhr window, in minutes.
 */
@Serializable
data class PrayerConfig(
    val lat: Double = 19.2183,
    val lon: Double = 72.9781,
    val place: String = "Thane, Maharashtra",
    val method: CalcMethod = CalcMethod.KARACHI,
    val madhab: Madhab = Madhab.HANAFI,
    val windowMin: Int = 20,
    val jumuahWindowMin: Int = 60
)

/**
 * The bedtime gate.
 *
 * @property enabled whether the gate is on.
 * @property startMinuteOfDay the earliest minute of the day the gate may start, counted from
 *   midnight (1350 = 22:30). The real start is `max(this, Isha + 30 min)` (§3.7).
 */
@Serializable
data class SleepGateConfig(
    val enabled: Boolean = true,
    val startMinuteOfDay: Int = 1350
)

/**
 * The morning window, the hours after Fajr that are worth protecting.
 *
 * @property enabled whether the window is on.
 * @property endMinuteOfDay the latest minute of the day the window may end (570 = 09:30). The real
 *   end is `min(this, Dhuhr)`, and the window is empty when Fajr's own window ends after it (§3.7).
 */
@Serializable
data class MorningConfig(
    val enabled: Boolean = true,
    val endMinuteOfDay: Int = 570
)

/**
 * Ramadan behaviour.
 *
 * @property mode whether the Ramadan sleep gate is off, forced on, or merely offered.
 * @property hijriOffsetDays manual correction to the Umm al-Qura date, −2…+2.
 */
@Serializable
data class RamadanConfig(
    val mode: RamadanMode = RamadanMode.AUTO_SUGGEST,
    val hijriOffsetDays: Int = 0
)

/**
 * "Think it over": loosening a rule waits before it takes effect.
 *
 * @property enabled whether the delay is on.
 * @property delayMin minutes a loosening change waits.
 */
@Serializable
data class StrictConfig(
    val enabled: Boolean = false,
    val delayMin: Int = 10
)

/**
 * Everything the user can decide about guarding, in one immutable value.
 *
 * The engine takes this as input on every transition and never mutates it; the data layer owns the
 * only authoritative copy and swaps the whole value when a setting changes.
 *
 * @property mode the budget and cooldown pair.
 * @property apps the guarded apps.
 * @property domains guarded web domains; only consulted when [webGuardEnabled] is on.
 * @property prayer prayer-time inputs.
 * @property sleepGate the bedtime gate.
 * @property morning the morning window.
 * @property ramadan Ramadan behaviour.
 * @property strict the delay on loosening changes.
 * @property reflectionEnabled whether the check-in question may appear.
 * @property gymHintEnabled whether the suggester may mention training.
 * @property webGuardEnabled whether guarded domains are enforced.
 */
@Serializable
data class GuardConfig(
    val mode: Mode = Mode.BALANCED,
    val apps: List<GuardedApp> = emptyList(),
    val domains: Set<String> = emptySet(),
    val prayer: PrayerConfig = PrayerConfig(),
    val sleepGate: SleepGateConfig = SleepGateConfig(),
    val morning: MorningConfig = MorningConfig(),
    val ramadan: RamadanConfig = RamadanConfig(),
    val strict: StrictConfig = StrictConfig(),
    val reflectionEnabled: Boolean = true,
    val gymHintEnabled: Boolean = true,
    val webGuardEnabled: Boolean = false
)
