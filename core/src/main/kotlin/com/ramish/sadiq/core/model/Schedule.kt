package com.ramish.sadiq.core.model

/** The five daily prayers, in order. */
enum class PrayerName {
    FAJR,
    DHUHR,
    ASR,
    MAGHRIB,
    ISHA
}

/** Which protected window the clock is inside, if any. */
enum class WindowKind {
    NONE,
    PRAYER,
    SLEEP_GATE,
    MORNING
}

/**
 * Every prayer time for one local day, in wall milliseconds.
 *
 * [nextFajr] belongs to tomorrow, so a sleep gate that crosses midnight can end without reading the
 * next day's row first.
 *
 * @property epochDay the local date as an epoch day (`LocalDate.toEpochDay()`).
 * @property fajr Fajr adhan.
 * @property sunrise sunrise, used only by the suggester and the sky.
 * @property dhuhr Dhuhr adhan; on Friday this is the Jumu'ah time.
 * @property asr Asr adhan.
 * @property maghrib Maghrib adhan.
 * @property isha Isha adhan.
 * @property nextFajr tomorrow's Fajr adhan.
 * @property isFriday whether the local date is a Friday, i.e. whether [dhuhr] is Jumu'ah.
 */
data class PrayerDay(
    val epochDay: Int,
    val fajr: Long,
    val sunrise: Long,
    val dhuhr: Long,
    val asr: Long,
    val maghrib: Long,
    val isha: Long,
    val nextFajr: Long,
    val isFriday: Boolean
)

/**
 * The window the clock is inside right now, or [WindowKind.NONE].
 *
 * @property kind which window; [WindowKind.NONE] means no window is active.
 * @property startWall window start in wall milliseconds, null when [kind] is [WindowKind.NONE].
 * @property endWall window end in wall milliseconds, null when [kind] is [WindowKind.NONE].
 * @property prayer which prayer owns the window, null for the sleep gate and the morning window.
 * @property isJumuah whether a prayer window is Friday's Jumu'ah window.
 */
data class ActiveWindow(
    val kind: WindowKind,
    val startWall: Long?,
    val endWall: Long?,
    val prayer: PrayerName? = null,
    val isJumuah: Boolean = false
)

/**
 * A sleep gate or morning window that has already ended, reported so the economy can award its
 * star even if no input arrived while it was open (§3.7).
 *
 * @property kind [WindowKind.SLEEP_GATE] or [WindowKind.MORNING].
 * @property startWall window start in wall milliseconds.
 * @property endWall window end in wall milliseconds.
 */
data class ProtectedSpan(
    val kind: WindowKind,
    val startWall: Long,
    val endWall: Long
)

/**
 * Everything a single reducer step needs to know about the clock, the settings and the windows.
 *
 * Assembled once per input by the app layer and passed to
 * [com.ramish.sadiq.core.engine.GuardReducer.reduce]; the reducer is a pure function of this value.
 *
 * @property config the settings in force for this step.
 * @property window the window active at this instant.
 * @property logicalDay the logical day at this instant; it rolls over at 04:00 local (§3.7).
 * @property today prayer times for the local date.
 * @property nextPrayer the nearest upcoming prayer, used by the suggester.
 * @property nextPrayerWall that prayer's adhan in wall milliseconds.
 * @property ramadanActive whether the Ramadan schedule is in force today.
 * @property minuteOfDay minutes since local midnight, 0…1439.
 * @property endedProtected protected windows that ended since the previous input.
 */
data class GuardContext(
    val config: GuardConfig,
    val window: ActiveWindow,
    val logicalDay: Int,
    val today: PrayerDay,
    val nextPrayer: PrayerName?,
    val nextPrayerWall: Long?,
    val ramadanActive: Boolean,
    val minuteOfDay: Int,
    val endedProtected: List<ProtectedSpan>
)
