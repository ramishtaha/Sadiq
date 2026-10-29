package com.ramish.sadiq.review

import com.ramish.sadiq.contract.HistoryRepository
import com.ramish.sadiq.contract.SettingsRepository
import com.ramish.sadiq.contract.UsageHistorySource
import com.ramish.sadiq.core.insight.UsageDayRow
import com.ramish.sadiq.core.time.Clock
import com.ramish.sadiq.core.time.LogicalDays
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes `usage_days` from the platform's usage stats: the past fourteen logical days, refreshed when
 * the Week tab opens and when the weekly review fires.
 *
 * Fourteen, not seven, because the review's delta needs the week before it. The rows are the guarded
 * apps only — `UsageHistorySource.dailyRows` scopes them — so the table stays small and the headline
 * can honestly say "in guarded apps".
 *
 * Everything here degrades to doing nothing: without usage access there is nothing to read, a ROM
 * that throws changes nothing, and the Week screen then shows Sadiq's own records labelled
 * "Approximate" instead of inventing numbers.
 */
@Singleton
class UsageDayAggregator @Inject constructor(
    private val usageHistory: UsageHistorySource,
    private val historyRepository: HistoryRepository,
    private val settingsRepository: SettingsRepository,
    private val clock: Clock,
) {

    /**
     * Refresh the stored usage rows.
     *
     * @param days how many logical days to read, ending today; the fourteen-day default covers a
     *   review and its comparison week.
     * @return how many rows were written, for a caller that wants to log or test the outcome.
     */
    suspend fun aggregate(days: Int = DAYS): Int {
        if (days <= 0) return 0
        if (!usageHistory.hasAccess()) return 0

        val now = clock.now()
        val today: Int = LogicalDays.of(now.wallMs, now.zoneId)
        val range: IntRange = (today - days + 1)..today
        val config = settingsRepository.config.value

        val rows: List<UsageDayRow> = runCatching {
            usageHistory.dailyRows(range, now.zoneId, config)
        }.getOrDefault(emptyList())
        if (rows.isEmpty()) return 0

        historyRepository.upsertUsageDays(rows)
        return rows.size
    }

    companion object {
        /** Two weeks: the reviewed seven days plus the seven they are compared against. */
        const val DAYS: Int = 14
    }
}
