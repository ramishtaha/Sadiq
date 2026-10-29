package com.ramish.sadiq.core.schedule

import com.batoulapps.adhan.CalculationMethod
import com.batoulapps.adhan.CalculationParameters
import com.batoulapps.adhan.Coordinates
import com.batoulapps.adhan.HighLatitudeRule
import com.batoulapps.adhan.Madhab as AdhanMadhab
import com.batoulapps.adhan.PrayerTimes
import com.batoulapps.adhan.data.DateComponents
import com.ramish.sadiq.core.model.CalcMethod
import com.ramish.sadiq.core.model.Madhab
import com.ramish.sadiq.core.model.PrayerConfig
import com.ramish.sadiq.core.model.PrayerDay
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Date

/**
 * Prayer times from Adhan 1.2.1, computed offline.
 *
 * The defaults match Rafiq's `PrayerEngine`: Karachi (University of Islamic Sciences) angles, Hanafi
 * Asr, and Adhan's own high-latitude default of the middle of the night. Sadiq has no high-latitude
 * setting, so no rule is ever stored and that default is always the one applied. Times are computed
 * for a local date, never from the current instant, which makes every result a pure function of its
 * inputs and keeps the golden test meaningful.
 *
 * Adhan returns absolute instants: it resolves the solar times in UTC, so the host JVM's default zone
 * cannot shift them. Each time is then cut down to the start of its minute. Adhan already rounds to
 * the nearest minute but leaves the sub-second remainder of the computation in place, which would
 * make a window that starts at a displayed "04:13" actually begin at 04:13:00.28. A prayer window is
 * specified in minutes (`[adhan, adhan + window_min)`), so the boundary is pinned to the minute the
 * user is shown.
 */
class AdhanPrayerTimesProvider : PrayerTimesProvider {

    /**
     * Prayer times for one local date.
     *
     * @param epochDay the local date as an epoch day. The date is already local, so [zoneId] does not
     *   change any instant; it is accepted because the contract passes it and because a future
     *   implementation (a cache keyed per zone, or a zone-aware adjustment) may need it.
     * @param zoneId the zone the times are expressed in; see above.
     * @param cfg location, calculation method and madhab.
     */
    override fun dayFor(epochDay: Int, zoneId: String, cfg: PrayerConfig): PrayerDay {
        val date: LocalDate = LocalDate.ofEpochDay(epochDay.toLong())
        val coordinates = Coordinates(cfg.lat, cfg.lon)
        val parameters: CalculationParameters = cfg.method.parameters().apply {
            madhab = cfg.madhab.toAdhan()
            highLatitudeRule = HighLatitudeRule.MIDDLE_OF_THE_NIGHT
        }
        val today = PrayerTimes(coordinates, date.toComponents(), parameters)
        val tomorrow = PrayerTimes(coordinates, date.plusDays(1).toComponents(), parameters)

        return PrayerDay(
            epochDay = epochDay,
            fajr = today.fajr.minuteStart(),
            sunrise = today.sunrise.minuteStart(),
            dhuhr = today.dhuhr.minuteStart(),
            asr = today.asr.minuteStart(),
            maghrib = today.maghrib.minuteStart(),
            isha = today.isha.minuteStart(),
            nextFajr = tomorrow.fajr.minuteStart(),
            isFriday = date.dayOfWeek == DayOfWeek.FRIDAY
        )
    }
}

/** The Adhan parameters for one calculation method. Each call returns a fresh, mutable copy. */
private fun CalcMethod.parameters(): CalculationParameters = when (this) {
    CalcMethod.KARACHI -> CalculationMethod.KARACHI.parameters
    CalcMethod.MUSLIM_WORLD_LEAGUE -> CalculationMethod.MUSLIM_WORLD_LEAGUE.parameters
    CalcMethod.EGYPTIAN -> CalculationMethod.EGYPTIAN.parameters
    CalcMethod.UMM_AL_QURA -> CalculationMethod.UMM_AL_QURA.parameters
    CalcMethod.DUBAI -> CalculationMethod.DUBAI.parameters
    CalcMethod.QATAR -> CalculationMethod.QATAR.parameters
    CalcMethod.KUWAIT -> CalculationMethod.KUWAIT.parameters
    CalcMethod.SINGAPORE -> CalculationMethod.SINGAPORE.parameters
    CalcMethod.NORTH_AMERICA -> CalculationMethod.NORTH_AMERICA.parameters
}

/** Our two Asr shadow rules, mapped onto Adhan's. */
private fun Madhab.toAdhan(): AdhanMadhab = when (this) {
    Madhab.HANAFI -> AdhanMadhab.HANAFI
    Madhab.SHAFI -> AdhanMadhab.SHAFI
}

/** The calendar date in the form Adhan's solar maths expects. */
private fun LocalDate.toComponents(): DateComponents =
    DateComponents(year, monthValue, dayOfMonth)

/**
 * The instant at the start of this time's minute.
 *
 * Adhan rounds to the minute but keeps the sub-second remainder, so two calls for the same day can
 * differ by a few hundred milliseconds. Prayer windows are minute-based, so the remainder is dropped.
 */
private fun Date.minuteStart(): Long = time - Math.floorMod(time, MILLIS_PER_MINUTE)

private const val MILLIS_PER_MINUTE: Long = 60_000L
