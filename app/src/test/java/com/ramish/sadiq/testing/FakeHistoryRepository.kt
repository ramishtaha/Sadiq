package com.ramish.sadiq.testing

import com.ramish.sadiq.contract.HistoryRepository
import com.ramish.sadiq.core.engine.RecordEvent
import com.ramish.sadiq.core.insight.UsageDayRow
import com.ramish.sadiq.core.insight.WeeklyInput
import com.ramish.sadiq.core.model.GuardConfig

/**
 * A [HistoryRepository] held in memory.
 *
 * Tests assert on what was recorded: [events] is the write log, [usage] the merged usage rows.
 *
 * @param week the input the Week screen's builder will be handed; the fake restamps `days` and `config`.
 */
class FakeHistoryRepository(
    var week: WeeklyInput = EMPTY_WEEK
) : HistoryRepository {

    private val recordedEvents: MutableList<RecordEvent> = mutableListOf()
    private val usageRows: MutableList<UsageDayRow> = mutableListOf()

    /** Every event recorded, in order. */
    val events: List<RecordEvent> get() = recordedEvents

    /** Every usage row currently stored. */
    val usage: List<UsageDayRow> get() = usageRows

    /** How many times [deleteAll] was called. */
    var deleteAllCount: Int = 0
        private set

    override suspend fun record(event: RecordEvent) {
        recordedEvents += event
    }

    override suspend fun weekInput(days: IntRange, prevDays: IntRange, config: GuardConfig): WeeklyInput =
        week.copy(days = days, config = config)

    override suspend fun upsertUsageDays(rows: List<UsageDayRow>) {
        rows.forEach { row ->
            val existing = usageRows.indexOfFirst {
                it.logicalDay == row.logicalDay && it.packageName == row.packageName
            }
            if (existing >= 0) {
                usageRows[existing] = row
            } else {
                usageRows += row
            }
        }
    }

    override suspend fun deleteAll() {
        deleteAllCount += 1
        recordedEvents.clear()
        usageRows.clear()
    }

    /** Whether any recorded event is of the given type. @param type the event class. */
    fun hasEventOfType(type: Class<out RecordEvent>): Boolean = recordedEvents.any { type.isInstance(it) }

    companion object {
        /** An empty week: no usage, no stars, no opens, a flat hour histogram. */
        val EMPTY_WEEK: WeeklyInput = WeeklyInput(
            days = 0..0,
            usage = emptyList(),
            prevUsage = emptyList(),
            stars = emptyList(),
            opens = emptyList(),
            reflections = emptyList(),
            focusMinutes = 0,
            quietNights = 0,
            hourlyMs = LongArray(24),
            config = GuardConfig(),
            approximate = false
        )
    }
}
