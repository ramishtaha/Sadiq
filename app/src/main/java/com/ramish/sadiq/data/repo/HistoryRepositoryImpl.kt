package com.ramish.sadiq.data.repo

import com.ramish.sadiq.contract.HistoryRepository
import com.ramish.sadiq.core.economy.StarKind
import com.ramish.sadiq.core.engine.RecordEvent
import com.ramish.sadiq.core.insight.UsageDayRow
import com.ramish.sadiq.core.insight.WeeklyInput
import com.ramish.sadiq.core.model.GuardConfig
import com.ramish.sadiq.data.db.GrantEntity
import com.ramish.sadiq.data.db.HistoryDao
import com.ramish.sadiq.data.db.StarDao
import com.ramish.sadiq.data.db.toEntity
import com.ramish.sadiq.data.db.toRecordOpen
import com.ramish.sadiq.data.db.toRecordReflected
import com.ramish.sadiq.data.db.toStarAward
import com.ramish.sadiq.data.db.toUsageDayRow
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Every [RecordEvent] becomes rows here, and the Week screen reads them back as a [WeeklyInput].
 *
 * Writes are one row per event; reads ask for a day range and do the folding in memory, because a
 * week is at most a few thousand rows and the queries stay trivial to reason about.
 */
@Singleton
class HistoryRepositoryImpl @Inject constructor(
    private val historyDao: HistoryDao,
    private val starDao: StarDao,
) : HistoryRepository {

    override suspend fun record(event: RecordEvent) {
        when (event) {
            is RecordEvent.Open -> historyDao.insertOpen(event.toEntity())
            is RecordEvent.GrantClosed -> historyDao.insertGrant(event.toEntity())
            // The star is history too: the RecordEvent carries it, so it lands in `stars` here.
            is RecordEvent.StarLit -> starDao.insert(event.toEntity())
            is RecordEvent.FocusClosed -> historyDao.insertFocus(event.toEntity())
            is RecordEvent.Reflected -> historyDao.insertReflection(event.toEntity())
        }
    }

    override suspend fun weekInput(days: IntRange, prevDays: IntRange, config: GuardConfig): WeeklyInput {
        val usage = historyDao.usageBetween(days.first, days.last).map { it.toUsageDayRow() }
        val prevUsage = historyDao.usageBetween(prevDays.first, prevDays.last).map { it.toUsageDayRow() }
        val stars = historyDao.starsBetween(days.first, days.last).map { it.toStarAward() }
        val opens = historyDao.opensBetween(days.first, days.last).map { it.toRecordOpen() }
        val reflections = historyDao.reflectionsBetween(days.first, days.last).map { it.toRecordReflected() }
        val focusMs = historyDao.focusMsBetween(days.first, days.last)
        val grants = historyDao.grantsBetween(days.first, days.last)

        return WeeklyInput(
            days = days,
            usage = usage,
            prevUsage = prevUsage,
            stars = stars,
            opens = opens,
            reflections = reflections,
            focusMinutes = (focusMs / MILLIS_PER_MINUTE).toInt(),
            quietNights = stars.asSequence()
                .filter { it.kind == StarKind.QUIET_NIGHT }
                .map { it.logicalDay }
                .distinct()
                .count(),
            hourlyMs = hourlyBuckets(grants),
            config = config,
            approximate = usage.isEmpty(),
        )
    }

    override suspend fun upsertUsageDays(rows: List<UsageDayRow>) {
        if (rows.isEmpty()) return
        historyDao.upsertUsageDays(rows.map { it.toEntity() })
    }

    /**
     * Clears the five history tables and nothing else (§3.4): open events, grants, focus sessions,
     * reflections and reconciled usage. Stars and the guard list are not history and are deleted
     * only by their own, explicit actions.
     *
     * The statements are independent, so a process death between them leaves a partially cleared
     * history — never a half-written row — and tapping "Delete all history" again finishes the job.
     */
    override suspend fun deleteAll() {
        historyDao.deleteOpens()
        historyDao.deleteGrants()
        historyDao.deleteFocusSessions()
        historyDao.deleteReflections()
        historyDao.deleteUsageDays()
    }

    /**
     * UsageStats gives day totals only, so the hour distribution comes from our own grants: the
     * consumed milliseconds of a grant land on the local hour it started in. That is exactly the
     * "most of it between 23:00 and 01:00" line, and it needs no permission.
     */
    private fun hourlyBuckets(grants: List<GrantEntity>): LongArray {
        val buckets = LongArray(HOURS_PER_DAY)
        val zone = ZoneId.systemDefault()
        grants.forEach { grant ->
            val hour = Instant.ofEpochMilli(grant.startedAt).atZone(zone).hour
            buckets[hour] = buckets[hour] + grant.consumedMs
        }
        return buckets
    }

    private companion object {
        const val HOURS_PER_DAY = 24
        const val MILLIS_PER_MINUTE = 60_000L
    }
}
