package com.ramish.sadiq.core.schedule

import com.ramish.sadiq.core.model.ActiveWindow
import com.ramish.sadiq.core.model.GuardConfig
import com.ramish.sadiq.core.model.GuardContext
import com.ramish.sadiq.core.model.PrayerConfig
import com.ramish.sadiq.core.model.PrayerDay
import com.ramish.sadiq.core.model.PrayerName
import com.ramish.sadiq.core.model.ProtectedSpan
import com.ramish.sadiq.core.model.WindowKind
import com.ramish.sadiq.core.time.LogicalDays
import com.ramish.sadiq.core.time.TimeSnapshot
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The schedule of §3.7, computed purely from the clock and the settings.
 *
 * Five windows exist, and they only ever overlap in one place, so the priority order
 * PRAYER > SLEEP_GATE > MORNING is what decides the answer:
 *
 * - a prayer window is `[adhan, adhan + prayer_window_min)`, and Friday's Dhuhr becomes Jumu'ah with
 *   `jumuah_window_min`;
 * - the sleep gate is `[max(sleep_gate_start, Isha + 30 min), Fajr)`, or in Ramadan
 *   `[max(sleep_gate_start, Isha + 90 min), Fajr − 40 min)`; it crosses midnight;
 * - the morning window is `[Fajr + prayer_window_min, min(morning_end, Dhuhr))`, and is empty when
 *   that interval is.
 *
 * **Which day "today" is.** [GuardContext.today] is the prayer day of the *logical* day, so
 * `today.epochDay == logicalDay` always holds. That is what makes the pre-dawn hours coherent: at
 * 02:00 the logical day is still yesterday, and `today.nextFajr` is the Fajr that will end the night
 * in progress rather than the one twenty-four hours later. The window maths does not depend on this
 * choice — every window is an absolute instant — but the reducer's sleep-gate copy does.
 *
 * **The gate that outlives the logical day.** The logical day rolls at 04:00 and the gate ends at
 * Fajr, so between 04:00 and Fajr the running gate belongs to the previous day's row. Both nights are
 * checked — the one ending this morning, then the one that starts tonight.
 *
 * **Prayers before 04:00.** At a high latitude Fajr — and the morning window that follows it — can
 * fall before the rollover. Prayer and morning windows, and the next prayer, therefore also consult
 * the calendar day whenever it differs from the logical day.
 */
class DefaultScheduleEvaluator(
    private val prayerTimes: PrayerTimesProvider = AdhanPrayerTimesProvider()
) : ScheduleEvaluator {

    override fun contextAt(now: TimeSnapshot, config: GuardConfig, lastSeenWall: Long): GuardContext {
        val zoneId = now.zoneId
        val logicalDay = LogicalDays.of(now.wallMs, zoneId)
        val calendarDay = localDate(now.wallMs, zoneId).toEpochDay().toInt()
        val days = DayReader(prayerTimes, zoneId, config.prayer)
        val today = days.day(logicalDay)
        // Between midnight and 04:00 the logical day is still yesterday, yet a prayer can already
        // belong to the calendar day: at a high latitude Fajr falls before 04:00 in summer, and the
        // morning window follows it. Windows and the next prayer therefore also consult that date.
        // At every other hour the two days are the same and only one row is read.
        val windowDays: List<PrayerDay> =
            if (calendarDay == logicalDay) listOf(today) else listOf(today, days.day(calendarDay))
        val next = nextPrayer(now.wallMs, windowDays)
        return GuardContext(
            config = config,
            window = activeWindow(now.wallMs, zoneId, config, windowDays, days),
            logicalDay = logicalDay,
            today = today,
            nextPrayer = next.name,
            nextPrayerWall = next.wall,
            ramadanActive = RamadanCalendar.isRamadan(now.wallMs, zoneId, config.ramadan),
            minuteOfDay = minuteOfDay(now.wallMs, zoneId),
            endedProtected = ended(lastSeenWall, now.wallMs, zoneId, config, days)
        )
    }

    override fun endedProtectedWindows(
        fromWall: Long,
        toWall: Long,
        zoneId: String,
        config: GuardConfig
    ): List<ProtectedSpan> =
        ended(fromWall, toWall, zoneId, config, DayReader(prayerTimes, zoneId, config.prayer))

    // ── Active window ───────────────────────────────────────────────────────────────────────────

    /**
     * The window containing [nowWall], chosen by the priority of §3.7.
     *
     * [windowDays] holds the logical day, plus the calendar day when the two differ (00:00–04:00);
     * both are searched for prayer and morning windows.
     */
    private fun activeWindow(
        nowWall: Long,
        zoneId: String,
        config: GuardConfig,
        windowDays: List<PrayerDay>,
        days: DayReader
    ): ActiveWindow {
        for (day in windowDays) {
            prayerWindow(nowWall, day, config.prayer)?.let { return it }
        }

        if (config.sleepGate.enabled) {
            // The gate ending this morning belongs to yesterday's row and is the one running before
            // dawn; tonight's gate is the one that has just begun. They cannot both contain the clock.
            val today = windowDays.first()
            gate(days.day(today.epochDay - 1), zoneId, config)?.takeIf { it.contains(nowWall) }?.let { gate ->
                return ActiveWindow(WindowKind.SLEEP_GATE, gate.startWall, gate.endWall)
            }
            gate(today, zoneId, config)?.takeIf { it.contains(nowWall) }?.let { gate ->
                return ActiveWindow(WindowKind.SLEEP_GATE, gate.startWall, gate.endWall)
            }
        }

        if (config.morning.enabled) {
            for (day in windowDays) {
                morning(day, zoneId, config)?.takeIf { it.contains(nowWall) }?.let { morning ->
                    return ActiveWindow(WindowKind.MORNING, morning.startWall, morning.endWall)
                }
            }
        }

        return ActiveWindow(kind = WindowKind.NONE, startWall = null, endWall = null)
    }

    /**
     * The prayer window containing [nowWall], if any.
     *
     * Start is inclusive and end exclusive, so an open at the adhan minute is inside the window and
     * an open at `adhan + window_min` is not. Friday's Dhuhr uses the Jumu'ah length and is flagged,
     * which is what makes the surface say Jumu'ah and offer the longer Stay.
     */
    private fun prayerWindow(nowWall: Long, day: PrayerDay, config: PrayerConfig): ActiveWindow? {
        val windows = listOf(
            PrayerWindow(PrayerName.FAJR, day.fajr, config.windowMin),
            PrayerWindow(
                PrayerName.DHUHR,
                day.dhuhr,
                if (day.isFriday) config.jumuahWindowMin else config.windowMin
            ),
            PrayerWindow(PrayerName.ASR, day.asr, config.windowMin),
            PrayerWindow(PrayerName.MAGHRIB, day.maghrib, config.windowMin),
            PrayerWindow(PrayerName.ISHA, day.isha, config.windowMin)
        )
        for (window in windows) {
            val length = window.minutes.coerceAtLeast(0) * MILLIS_PER_MINUTE
            if (length <= 0L) continue
            val end = window.adhan + length
            if (nowWall >= window.adhan && nowWall < end) {
                return ActiveWindow(
                    kind = WindowKind.PRAYER,
                    startWall = window.adhan,
                    endWall = end,
                    prayer = window.prayer,
                    isJumuah = window.prayer == PrayerName.DHUHR && day.isFriday
                )
            }
        }
        return null
    }

    /**
     * The sleep gate that follows [day], or null when it is empty.
     *
     * The start is the later of the configured bedtime and Isha plus a delay: thirty minutes on an
     * ordinary night, ninety in Ramadan, which leaves the Suhoor time open by ending forty minutes
     * before Fajr. Ramadan is read at the moment the night begins, so a night that started inside
     * Ramadan keeps its shape even if the calendar turns before dawn.
     */
    private fun gate(day: PrayerDay, zoneId: String, config: GuardConfig): ProtectedSpan? {
        if (!config.sleepGate.enabled) return null

        val bedtime = atMinuteOfDay(day.epochDay, config.sleepGate.startMinuteOfDay, zoneId)
        val earliestStart = maxOf(bedtime, day.isha + ORDINARY_ISHA_DELAY_MIN * MILLIS_PER_MINUTE)
        val ramadan = RamadanCalendar.isRamadan(earliestStart, zoneId, config.ramadan)

        val start: Long
        val end: Long
        if (ramadan) {
            start = maxOf(bedtime, day.isha + RAMADAN_ISHA_DELAY_MIN * MILLIS_PER_MINUTE)
            end = day.nextFajr - RAMADAN_FAJR_MARGIN_MIN * MILLIS_PER_MINUTE
        } else {
            start = earliestStart
            end = day.nextFajr
        }
        return if (end > start) ProtectedSpan(WindowKind.SLEEP_GATE, start, end) else null
    }

    /**
     * The morning window of [day], or null when it is empty.
     *
     * It starts only after the Fajr window has closed — there is no point protecting a morning the
     * prayer window is still covering — and ends at whichever comes first, the configured end or
     * Dhuhr. If Fajr's window closes after the configured end, the whole window is empty.
     */
    private fun morning(day: PrayerDay, zoneId: String, config: GuardConfig): ProtectedSpan? {
        if (!config.morning.enabled) return null
        val start = day.fajr + config.prayer.windowMin.coerceAtLeast(0) * MILLIS_PER_MINUTE
        val end = minOf(atMinuteOfDay(day.epochDay, config.morning.endMinuteOfDay, zoneId), day.dhuhr)
        return if (end > start) ProtectedSpan(WindowKind.MORNING, start, end) else null
    }

    // ── Ended windows ───────────────────────────────────────────────────────────────────────────

    /**
     * Every gate and morning window whose *end* falls in `(fromWall, toWall]`, oldest first.
     *
     * A window is reported even though nothing happened inside it: an untouched night still ends, and
     * the economy awards its quiet-night star from this list. A missing or nonsensical previous input
     * (zero, or a clock that moved backwards) reports nothing rather than a fabricated night.
     */
    private fun ended(
        fromWall: Long,
        toWall: Long,
        zoneId: String,
        config: GuardConfig,
        days: DayReader
    ): List<ProtectedSpan> {
        if (fromWall <= 0L || toWall <= fromWall) return emptyList()

        // A previous input older than this cannot produce a meaningful award — the star caps are
        // daily — and walking years of nights would block the interception path it is called from.
        val from = maxOf(fromWall, toWall - MAX_LOOKBACK_MS)
        val firstDay = localDate(from, zoneId).toEpochDay().toInt() - 1
        val lastDay = localDate(toWall, zoneId).toEpochDay().toInt() + 1

        val spans = ArrayList<ProtectedSpan>(4)
        for (epochDay in firstDay..lastDay) {
            val day = days.day(epochDay)
            appendIfEnded(gate(day, zoneId, config), fromWall, toWall, spans)
            appendIfEnded(morning(day, zoneId, config), fromWall, toWall, spans)
        }
        return spans.sortedWith(compareBy({ it.endWall }, { it.startWall }))
    }

    private fun appendIfEnded(
        span: ProtectedSpan?,
        fromWall: Long,
        toWall: Long,
        into: MutableList<ProtectedSpan>
    ) {
        if (span != null && span.endWall > fromWall && span.endWall <= toWall) into.add(span)
    }

    // ── Next prayer ─────────────────────────────────────────────────────────────────────────────

    /**
     * The nearest adhan strictly after [nowWall], falling back to the last day's next Fajr.
     *
     * At the exact adhan instant the prayer is in force rather than upcoming — its window owns the
     * clock — so the answer moves on to the following prayer instead of announcing a zero-minute wait.
     * [days] is the logical day alone, or the logical day and the calendar day before 04:00.
     */
    private fun nextPrayer(nowWall: Long, days: List<PrayerDay>): NextPrayer {
        for (day in days) {
            val candidates = listOf(
                NextPrayer(PrayerName.FAJR, day.fajr),
                NextPrayer(PrayerName.DHUHR, day.dhuhr),
                NextPrayer(PrayerName.ASR, day.asr),
                NextPrayer(PrayerName.MAGHRIB, day.maghrib),
                NextPrayer(PrayerName.ISHA, day.isha)
            )
            for (candidate in candidates) {
                if (candidate.wall > nowWall) return candidate
            }
        }
        return NextPrayer(PrayerName.FAJR, days.last().nextFajr)
    }

    // ── Time helpers ────────────────────────────────────────────────────────────────────────────

    /** The instant at [minuteOfDay] on [epochDay], in [zoneId]. Out-of-range minutes wrap. */
    private fun atMinuteOfDay(epochDay: Int, minuteOfDay: Int, zoneId: String): Long {
        val minute = Math.floorMod(minuteOfDay, MINUTES_PER_DAY)
        return LocalDate.ofEpochDay(epochDay.toLong())
            .atTime(minute / MINUTES_PER_HOUR, minute % MINUTES_PER_HOUR)
            .atZone(ZoneId.of(zoneId))
            .toInstant()
            .toEpochMilli()
    }

    private fun localDate(wallMs: Long, zoneId: String): LocalDate =
        Instant.ofEpochMilli(wallMs).atZone(ZoneId.of(zoneId)).toLocalDate()

    private fun minuteOfDay(wallMs: Long, zoneId: String): Int {
        val time = Instant.ofEpochMilli(wallMs).atZone(ZoneId.of(zoneId)).toLocalTime()
        return time.hour * MINUTES_PER_HOUR + time.minute
    }

    // ── Small types ─────────────────────────────────────────────────────────────────────────────

    /** One prayer's adhan and configured length, before the Friday override is resolved. */
    private data class PrayerWindow(val prayer: PrayerName, val adhan: Long, val minutes: Int)

    /** An adhan and the name it belongs to. */
    private data class NextPrayer(val name: PrayerName, val wall: Long)

    /** Per-call cache of prayer days, so one context costs a handful of Adhan computations at most. */
    private class DayReader(
        private val provider: PrayerTimesProvider,
        private val zoneId: String,
        private val config: PrayerConfig
    ) {
        private val days = HashMap<Int, PrayerDay>()

        fun day(epochDay: Int): PrayerDay =
            days.getOrPut(epochDay) { provider.dayFor(epochDay, zoneId, config) }
    }
}

/** Whether [wallMs] falls inside this window: start inclusive, end exclusive. */
private fun ProtectedSpan.contains(wallMs: Long): Boolean = wallMs >= startWall && wallMs < endWall

private const val MILLIS_PER_MINUTE: Long = 60_000L
private const val MINUTES_PER_HOUR: Int = 60
private const val MINUTES_PER_DAY: Int = 24 * MINUTES_PER_HOUR

/** Isha to the gate on an ordinary night (§3.7). */
private const val ORDINARY_ISHA_DELAY_MIN = 30

/** Isha to the gate in Ramadan: Suhoor stays open (§3.7). */
private const val RAMADAN_ISHA_DELAY_MIN = 90

/** Ramadan's gate ends this long before Fajr, leaving the Suhoor window (§3.7). */
private const val RAMADAN_FAJR_MARGIN_MIN = 40

/** The furthest back a missed window is looked for; a year of nights is already absurd. */
private const val MAX_LOOKBACK_MS: Long = 366L * 24 * 60 * MILLIS_PER_MINUTE
