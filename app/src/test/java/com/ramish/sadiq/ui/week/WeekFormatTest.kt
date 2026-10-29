package com.ramish.sadiq.ui.week

import com.ramish.sadiq.review.WeekFormat
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Week screen's numbers, as words.
 *
 * The interesting cases are the dishonest ones: a real use that rounds to zero, a difference too
 * small to name, and a band that crosses midnight.
 */
class WeekFormatTest {

    private val minute = 60_000L
    private val hour = 3_600_000L

    @Test
    fun durationsReadInHoursAndMinutes() {
        assertEquals("9 h 40 m", WeekFormat.duration(9 * hour + 40 * minute))
        assertEquals("2 h", WeekFormat.duration(2 * hour))
        assertEquals("40 m", WeekFormat.duration(40 * minute))
        assertEquals("0 m", WeekFormat.duration(0L))
    }

    @Test
    fun aRealUseNeverRoundsDownToZero() {
        assertEquals("1 m", WeekFormat.duration(30_000L))
        assertEquals("1 m", WeekFormat.duration(59_000L))
    }

    @Test
    fun aNegativeDurationIsTreatedAsZero() {
        assertEquals("0 m", WeekFormat.duration(-5 * minute))
    }

    @Test
    fun aDifferenceSmallerThanHalfAMinuteReadsAsSame() {
        assertEquals(WeekFormat.Comparison.SAME, WeekFormat.comparison(0L))
        assertEquals(WeekFormat.Comparison.SAME, WeekFormat.comparison(20_000L))
        assertEquals(WeekFormat.Comparison.SAME, WeekFormat.comparison(-20_000L))
    }

    @Test
    fun theDirectionOfTheDeltaIsKept() {
        assertEquals(WeekFormat.Comparison.LESS, WeekFormat.comparison(-2 * hour))
        assertEquals(WeekFormat.Comparison.MORE, WeekFormat.comparison(hour))
    }

    @Test
    fun hoursFollowThePhonesClockSetting() {
        assertEquals("23:00", WeekFormat.hourLabel(23, is24Hour = true))
        assertEquals("00:00", WeekFormat.hourLabel(0, is24Hour = true))
        assertEquals("09:00", WeekFormat.hourLabel(9, is24Hour = true))

        assertEquals("11 pm", WeekFormat.hourLabel(23, is24Hour = false))
        assertEquals("12 am", WeekFormat.hourLabel(0, is24Hour = false))
        assertEquals("11 am", WeekFormat.hourLabel(11, is24Hour = false))
        assertEquals("12 pm", WeekFormat.hourLabel(12, is24Hour = false))
    }

    @Test
    fun theBandEndWrapsAcrossMidnight() {
        assertEquals(1, WeekFormat.bandEndHour(23))
        assertEquals(0, WeekFormat.bandEndHour(22))
        assertEquals(2, WeekFormat.bandEndHour(0))
    }

    @Test
    fun aMinuteOfDayReadsAsAClockTime() {
        assertEquals("22:00", WeekFormat.minuteLabel(22 * 60, is24Hour = true))
        assertEquals("10 pm", WeekFormat.minuteLabel(22 * 60, is24Hour = false))
        assertEquals("00:00", WeekFormat.minuteLabel(0, is24Hour = true))
    }
}
