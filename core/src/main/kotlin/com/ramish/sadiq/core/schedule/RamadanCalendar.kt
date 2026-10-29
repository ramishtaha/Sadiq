package com.ramish.sadiq.core.schedule

import com.ramish.sadiq.core.model.RamadanConfig
import com.ramish.sadiq.core.model.RamadanMode
import java.time.Instant
import java.time.ZoneId
import java.time.chrono.HijrahDate
import java.time.temporal.ChronoField

/**
 * A date in the Umm al-Qura Hijri calendar.
 *
 * @property year the Hijri year, e.g. 1448.
 * @property month the Hijri month, 1…12; 9 is Ramadan.
 * @property day the day of the month, 1…30.
 */
data class HijriDate(
    val year: Int,
    val month: Int,
    val day: Int
)

/**
 * Ramadan, read from the Umm al-Qura calendar with a manual correction.
 *
 * The phone has no network, so the month cannot be announced from a sighting report; java.time's
 * Umm al-Qura chronology is the reference the rest of the Muslim world uses for printed calendars.
 * `hijri_offset_days` lets the user correct it by the day or two their local moon sighting differs,
 * and it is applied before the date is read, so it moves the month boundaries with it.
 *
 * The Ramadan schedule is only in force when the user turned it on: [RamadanMode.ON]. The automatic
 * mode never changes the schedule by itself, it only offers the home card on 29 Sha'ban (§3.7).
 */
object RamadanCalendar {

    /** The Hijri month of fasting. */
    const val RAMADAN_MONTH: Int = 9

    /** The month before Ramadan, when the offer card appears. */
    const val SHABAN_MONTH: Int = 8

    /** The day of Sha'ban on which Ramadan is a day or two away. */
    const val SUGGEST_DAY: Int = 29

    /**
     * The Hijri date at [wallMs], after the manual correction.
     *
     * @param wallMs milliseconds since the Unix epoch, civil time.
     * @param zoneId the IANA zone the wall time is expressed in; the Hijri date changes at local
     *   midnight, so the zone decides which date is being read.
     * @param offsetDays the user's correction, −2…+2, read as "plus this many days" on the Hijri
     *   date. A positive value therefore makes the month begin earlier than the printed calendar
     *   says. It is applied to the Gregorian date before the conversion, which is the same day step
     *   as adding it to the Hijri date afterwards.
     */
    fun hijriAt(wallMs: Long, zoneId: String, offsetDays: Int): HijriDate {
        val localDate = Instant.ofEpochMilli(wallMs)
            .atZone(ZoneId.of(zoneId))
            .toLocalDate()
            .plusDays(offsetDays.toLong())
        val hijri = HijrahDate.from(localDate)
        return HijriDate(
            year = hijri.get(ChronoField.YEAR),
            month = hijri.get(ChronoField.MONTH_OF_YEAR),
            day = hijri.get(ChronoField.DAY_OF_MONTH)
        )
    }

    /**
     * Whether the Ramadan schedule is in force at [wallMs].
     *
     * True only inside the Ramadan month and only when the user turned Ramadan mode on. A phone left
     * on [RamadanMode.AUTO_SUGGEST] keeps the ordinary schedule until the card is accepted.
     */
    fun isRamadan(wallMs: Long, zoneId: String, config: RamadanConfig): Boolean {
        if (config.mode != RamadanMode.ON) return false
        return hijriAt(wallMs, zoneId, config.hijriOffsetDays).month == RAMADAN_MONTH
    }

    /**
     * Whether the "Ramadan starts in a day or two" card should be offered at [wallMs].
     *
     * Only on 29 Sha'ban of the corrected calendar and only while the mode is [RamadanMode.AUTO_SUGGEST];
     * once the user answers (on or off) the card never returns that year.
     */
    fun shouldSuggest(wallMs: Long, zoneId: String, config: RamadanConfig): Boolean {
        if (config.mode != RamadanMode.AUTO_SUGGEST) return false
        val today = hijriAt(wallMs, zoneId, config.hijriOffsetDays)
        return today.month == SHABAN_MONTH && today.day == SUGGEST_DAY
    }
}
