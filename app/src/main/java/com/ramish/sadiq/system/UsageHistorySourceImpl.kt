package com.ramish.sadiq.system

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import com.ramish.sadiq.contract.UsageHistorySource
import com.ramish.sadiq.core.engine.UsageSpan
import com.ramish.sadiq.core.insight.UsageDayRow
import com.ramish.sadiq.core.model.GuardConfig
import com.ramish.sadiq.core.model.ProtectedSpan
import com.ramish.sadiq.core.model.WindowKind
import com.ramish.sadiq.core.schedule.DefaultScheduleEvaluator
import com.ramish.sadiq.core.schedule.ScheduleEvaluator
import com.ramish.sadiq.core.time.LogicalDays
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Usage stats: the sessions the engine missed while the process was down, and the Week screen's
 * numbers.
 *
 * Every read degrades to "nothing" rather than failing: without usage access `queryEvents` returns
 * an empty stream, a ROM that throws returns an empty list, and an unknown zone returns zeroed
 * hours. The consequence is documented in §3.5 — reconciliation is skipped and the Week screen says
 * "Approximate" — which is honest, because Sadiq's own records still hold what it saw itself.
 *
 * **The schedule is built here, not injected.** `dayRows` needs the sleep gate to attribute night
 * usage, and the gate comes from the same evaluator the engine uses. Binding `ScheduleEvaluator`
 * through Hilt would make this file depend on WP-13's module, which does not exist when WP-12 is
 * built; constructing the pure evaluator locally is the smallest honest alternative and cannot
 * disagree with the engine, because both run `DefaultScheduleEvaluator` with the caller's config.
 */
@Singleton
class UsageHistorySourceImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : UsageHistorySource {

    /** The same schedule the engine evaluates, for the sleep gate only. */
    private val schedule: ScheduleEvaluator = DefaultScheduleEvaluator()

    /**
     * Whether usage access is granted.
     *
     * Answered from the app-op rather than from a caught exception: `queryEvents` silently returns
     * nothing without the permission, which is exactly the case that must not be mistaken for "the
     * user did not use his phone".
     */
    override fun hasAccess(): Boolean = SystemPermissions.usageAccess(context)

    override suspend fun spans(fromWall: Long, toWall: Long): List<UsageSpan> =
        withContext(Dispatchers.IO) {
            if (toWall <= fromWall) return@withContext emptyList()
            val manager = context.getSystemService(UsageStatsManager::class.java)
                ?: return@withContext emptyList()
            val events = try {
                manager.queryEvents(fromWall + 1, toWall)
            } catch (_: Throwable) {
                // Some ROMs throw instead of returning an empty stream when the permission was
                // revoked mid-session. There is no partial answer to give.
                return@withContext emptyList()
            }
            UsageSpans.fold(readEvents(events), toWall)
        }

    /**
     * Foreground rows for the guarded apps of [config], one row per app per logical day.
     *
     * Scoped to the guarded apps on purpose (the contract passes the settings "so guarded packages
     * are known"): the Week screen's headline is "in guarded apps", and writing a row for every
     * package on the phone would bloat the table with system UI and launchers nobody asked about.
     *
     * `nightMs` is the part of the row that falls inside a sleep gate of the same logical day, which
     * is what makes a 23:40 session and a 00:10 session both count as the night they belong to. The
     * gate is computed for a padded range, because tonight's gate ends tomorrow morning and would
     * otherwise be missing from today's row.
     */
    override suspend fun dailyRows(
        days: IntRange,
        zoneId: String,
        config: GuardConfig,
    ): List<UsageDayRow> = withContext(Dispatchers.IO) {
        if (days.isEmpty()) return@withContext emptyList()
        val guarded: Set<String> = config.apps.mapTo(HashSet()) { app -> app.packageName }
        if (guarded.isEmpty()) return@withContext emptyList()

        val from = LogicalDays.startWall(days.first, zoneId)
        val to = LogicalDays.startWall(days.last + 1, zoneId) - 1
        val sessions = spans(from, to).filter { span -> span.packageName in guarded }
        if (sessions.isEmpty()) return@withContext emptyList()

        val nights = nightWindows(from, to, zoneId, config)
        val rows = ArrayList<UsageDayRow>(sessions.size)

        for (day in days) {
            val dayStart = LogicalDays.startWall(day, zoneId)
            val dayEnd = LogicalDays.startWall(day + 1, zoneId)
            val perPackage = HashMap<String, Accumulator>()
            for (session in sessions) {
                val start = maxOf(session.startWall, dayStart)
                val end = minOf(session.endWall, dayEnd)
                if (end <= start) continue
                val accumulator = perPackage.getOrPut(session.packageName) { Accumulator() }
                accumulator.foregroundMs += end - start
                // One open per session, counted on the day it started: a session that runs past
                // 04:00 is one open, not two.
                if (session.startWall >= dayStart && session.startWall < dayEnd) {
                    accumulator.opens += 1
                }
                accumulator.nightMs += overlapMs(start, end, nights)
            }
            perPackage.forEach { (packageName, accumulator) ->
                rows.add(
                    UsageDayRow(
                        logicalDay = day,
                        packageName = packageName,
                        foregroundMs = accumulator.foregroundMs,
                        opens = accumulator.opens,
                        nightMs = accumulator.nightMs,
                    )
                )
            }
        }

        rows.sortedWith(compareBy<UsageDayRow> { row -> row.logicalDay }.thenBy { row -> row.packageName })
    }

    /**
     * Foreground milliseconds per hour of the day, 24 slots, summed over [days].
     *
     * A session that crosses an hour boundary is split at the boundary and charged to the hour each
     * part falls in, so a 22:40–23:20 session gives twenty minutes to 22:00 and twenty to 23:00.
     * The bucket index is the local hour in [zoneId], which is the zone the Week screen draws in.
     *
     * @param packages the apps to count; an empty set counts every app.
     */
    override suspend fun hourlyMs(
        days: IntRange,
        zoneId: String,
        packages: Set<String>,
    ): LongArray = withContext(Dispatchers.IO) {
        val hours = LongArray(HOURS_PER_DAY)
        if (days.isEmpty()) return@withContext hours
        val zone = runCatching { ZoneId.of(zoneId) }.getOrNull() ?: return@withContext hours
        val from = LogicalDays.startWall(days.first, zoneId)
        val to = LogicalDays.startWall(days.last + 1, zoneId) - 1
        val sessions = spans(from, to).filter { span ->
            packages.isEmpty() || span.packageName in packages
        }

        for (session in sessions) {
            var cursor = session.startWall
            while (cursor < session.endWall) {
                val zoned = Instant.ofEpochMilli(cursor).atZone(zone)
                val nextHour = zoned.truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant().toEpochMilli()
                // A zone that jumps forward can collapse the hour boundary onto the cursor. Charging
                // the rest of the session to the wrong hour would be worse than dropping it.
                if (nextHour <= cursor) break
                val chunkEnd = minOf(nextHour, session.endWall)
                hours[zoned.hour] += chunkEnd - cursor
                cursor = chunkEnd
            }
        }
        hours
    }

    /** Copies the platform's event stream into the pure model. The event object is reused by design. */
    private fun readEvents(events: UsageEvents): List<UsageEvent> {
        val collected = ArrayList<UsageEvent>()
        val cursor = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(cursor)
            collected.add(
                UsageEvent(
                    packageName = cursor.packageName,
                    kind = kindOf(cursor.eventType),
                    wallMs = cursor.timeStamp,
                )
            )
        }
        return collected
    }

    /** Maps `UsageEvents.Event` types to the fold's vocabulary; anything unknown changes nothing. */
    private fun kindOf(eventType: Int): UsageEventKind = when (eventType) {
        UsageEvents.Event.ACTIVITY_RESUMED -> UsageEventKind.RESUMED
        UsageEvents.Event.ACTIVITY_PAUSED -> UsageEventKind.PAUSED
        UsageEvents.Event.ACTIVITY_STOPPED -> UsageEventKind.STOPPED
        UsageEvents.Event.SCREEN_NON_INTERACTIVE -> UsageEventKind.SCREEN_OFF
        UsageEvents.Event.SCREEN_INTERACTIVE -> UsageEventKind.SCREEN_ON
        UsageEvents.Event.DEVICE_SHUTDOWN -> UsageEventKind.SHUTDOWN
        else -> UsageEventKind.OTHER
    }

    /**
     * The sleep gates around `[fromWall, toWall]`, padded by two days on each side.
     *
     * A gate that started before the range still ends inside it, and tonight's gate ends after it;
     * the pad makes both visible so no session is left without its night.
     */
    private fun nightWindows(
        fromWall: Long,
        toWall: Long,
        zoneId: String,
        config: GuardConfig,
    ): List<ProtectedSpan> = runCatching {
        schedule.endedProtectedWindows(
            fromWall - GATE_PAD_MS,
            toWall + GATE_PAD_MS,
            zoneId,
            config,
        )
    }.getOrDefault(emptyList())
        .filter { span -> span.kind == WindowKind.SLEEP_GATE }

    /** The overlap of `[startWall, endWall]` with a set of non-overlapping spans. */
    private fun overlapMs(startWall: Long, endWall: Long, spans: List<ProtectedSpan>): Long {
        var total = 0L
        for (span in spans) {
            val from = maxOf(startWall, span.startWall)
            val to = minOf(endWall, span.endWall)
            if (to > from) total += to - from
        }
        return total
    }

    /** One package's totals for one logical day, while the spans are being folded in. */
    private class Accumulator {
        var foregroundMs: Long = 0L
        var opens: Int = 0
        var nightMs: Long = 0L
    }

    private companion object {
        const val HOURS_PER_DAY: Int = 24

        /** Two days either side of a range: enough for last night's gate and tonight's. */
        const val GATE_PAD_MS: Long = 2L * 24 * 60 * 60 * 1000
    }
}
