package com.ramish.sadiq.data

import com.ramish.sadiq.core.economy.StarAward
import com.ramish.sadiq.core.economy.StarKind
import com.ramish.sadiq.core.engine.Grant
import com.ramish.sadiq.core.engine.GrantEnd
import com.ramish.sadiq.core.engine.GrantKind
import com.ramish.sadiq.core.engine.OpenOutcome
import com.ramish.sadiq.core.engine.RecordEvent
import com.ramish.sadiq.core.engine.SurfaceKind
import com.ramish.sadiq.core.engine.FocusState
import com.ramish.sadiq.core.insight.ReflectionReason
import com.ramish.sadiq.core.insight.UsageDayRow
import com.ramish.sadiq.core.model.GuardConfig
import com.ramish.sadiq.core.model.GuardedApp
import com.ramish.sadiq.core.model.SurfaceMode
import com.ramish.sadiq.core.model.WindowKind
import com.ramish.sadiq.core.sound.SoundKind
import com.ramish.sadiq.data.db.Converters
import com.ramish.sadiq.data.db.HistoryDao
import com.ramish.sadiq.data.db.StarDao
import com.ramish.sadiq.data.db.FocusOutcome
import com.ramish.sadiq.data.db.FocusSessionEntity
import com.ramish.sadiq.data.db.GrantEntity
import com.ramish.sadiq.data.db.OpenEventEntity
import com.ramish.sadiq.data.db.ReflectionEntity
import com.ramish.sadiq.data.db.StarEntity
import com.ramish.sadiq.data.db.toEntity
import com.ramish.sadiq.data.db.toGuardedApp
import com.ramish.sadiq.data.db.toRecordOpen
import com.ramish.sadiq.data.db.toRecordReflected
import com.ramish.sadiq.data.db.toSkyStar
import com.ramish.sadiq.data.db.toStarAward
import com.ramish.sadiq.data.db.UsageDayEntity
import com.ramish.sadiq.data.db.toUsageDayRow
import com.ramish.sadiq.data.repo.HistoryRepositoryImpl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

/**
 * Every [RecordEvent] lands on the column §3.4 names, and the TEXT enum columns survive a write and
 * a read unchanged.
 */
class RecordMappingTest {

    @Test
    fun openEvent_mapsEveryColumnAndRoundTrips() {
        val event = RecordEvent.Open(
            at = 1_790_000_000_000L,
            logicalDay = 20_690,
            packageName = "com.instagram.android",
            domain = "instagram.com",
            surface = SurfaceKind.PAUSE,
            outcome = OpenOutcome.LEFT,
            decisionMs = 1_400L,
            grantId = "g42",
        )

        val row: OpenEventEntity = event.toEntity()

        assertEquals(0L, row.id)
        assertEquals(1_790_000_000_000L, row.at)
        assertEquals(20_690, row.logicalDay)
        assertEquals("com.instagram.android", row.packageName)
        assertEquals("instagram.com", row.domain)
        assertEquals(SurfaceKind.PAUSE, row.surface)
        assertEquals(OpenOutcome.LEFT, row.outcome)
        assertEquals(1_400L, row.decisionMs)
        assertEquals("g42", row.grantId)
        assertEquals(event, row.toRecordOpen())
    }

    @Test
    fun grantClosed_mapsEveryColumn() {
        val grant = Grant(
            id = "g42",
            kind = GrantKind.PRAYER_STAY,
            grantedMs = 300_000L,
            consumedMs = 120_000L,
            startedWall = 1_790_000_000_000L,
            lastAccountedElapsed = 4_000L,
            heldSinceElapsed = null,
            pausedSinceElapsed = null,
            windowAtStart = WindowKind.PRAYER,
            firstPackage = "com.instagram.android",
        )

        val row: GrantEntity = RecordEvent.GrantClosed(
            grant = grant,
            endedAt = 1_790_000_130_000L,
            end = GrantEnd.DONE_EARLY,
            logicalDay = 20_690,
        ).toEntity()

        assertEquals("g42", row.id)
        assertEquals(GrantKind.PRAYER_STAY, row.kind)
        assertEquals(1_790_000_000_000L, row.startedAt)
        assertEquals(300_000L, row.grantedMs)
        assertEquals(120_000L, row.consumedMs)
        assertEquals(1_790_000_130_000L, row.endedAt)
        assertEquals(GrantEnd.DONE_EARLY, row.endReason)
        assertEquals("com.instagram.android", row.firstPackage)
        assertEquals(WindowKind.PRAYER, row.window)
        assertEquals(20_690, row.logicalDay)
    }

    @Test
    fun starLit_mapsToAnUnassignedStarRow() {
        val award = StarAward(
            kind = StarKind.KEPT_WORD,
            litWall = 1_790_000_000_000L,
            logicalDay = 20_690,
            window = WindowKind.NONE,
            sourcePackage = "com.instagram.android",
            sourceRef = "g42",
        )

        val row: StarEntity = RecordEvent.StarLit(award).toEntity()

        assertEquals(0L, row.id)
        assertEquals(StarKind.KEPT_WORD, row.kind)
        assertEquals(1_790_000_000_000L, row.litAt)
        assertEquals(20_690, row.logicalDay)
        assertEquals(WindowKind.NONE, row.window)
        assertEquals("com.instagram.android", row.sourcePackage)
        assertEquals("g42", row.sourceRef)
        assertEquals(award, row.toStarAward())
    }

    @Test
    fun focusClosed_writesCompletedOutcomeWhenItRanToTheEnd() {
        val row = focusClosed(completed = true)

        assertEquals(FocusOutcome.COMPLETED, row.outcome)
        assertEquals(1_500_000L, row.completedMs)
        assertEquals(1_790_000_000_000L, row.endedAt)
        assertEquals(3, row.stars)
        assertEquals(SoundKind.RAIN, row.sound)
        assertEquals(20_690, row.logicalDay)
    }

    @Test
    fun focusClosed_writesEndedEarlyOutcomeWhenItWasStopped() {
        val row = focusClosed(completed = false)

        assertEquals(FocusOutcome.ENDED_EARLY, row.outcome)
        assertEquals("f7", row.id)
        assertEquals(1_500_000L, row.plannedMs)
    }

    @Test
    fun reflected_roundTrips() {
        val event = RecordEvent.Reflected(
            at = 1_790_000_000_000L,
            logicalDay = 20_690,
            reason = ReflectionReason.AVOIDING,
            packageName = "com.instagram.android",
            opensInWindow = 4,
        )

        val row: ReflectionEntity = event.toEntity()

        assertEquals(ReflectionReason.AVOIDING, row.reason)
        assertEquals(4, row.opensInWindow)
        assertEquals(event, row.toRecordReflected())
    }

    @Test
    fun usageDay_roundTrips() {
        val row = UsageDayRow(
            logicalDay = 20_690,
            packageName = "com.instagram.android",
            foregroundMs = 2_400_000L,
            opens = 12,
            nightMs = 0L,
        )

        assertEquals(row, row.toEntity().toUsageDayRow())
    }

    @Test
    fun guardedApp_keepsItsOriginalAddedAt() {
        val app = GuardedApp(
            packageName = "com.google.android.youtube",
            label = "YouTube",
            surfaceMode = SurfaceMode.SHORTS_ONLY,
        )

        val row = app.toEntity(addedAt = 1_700_000_000_000L)

        assertEquals("com.google.android.youtube", row.packageName)
        assertEquals("YouTube", row.label)
        assertEquals(1_700_000_000_000L, row.addedAt)
        assertEquals(SurfaceMode.SHORTS_ONLY, row.surfaceMode)
        assertEquals(app, row.toGuardedApp())
    }

    @Test
    fun starEntity_skyProjectionUsesTheRowIdAsSkyOrder() {
        val row = StarEntity(
            id = 7L,
            kind = StarKind.QUIET_NIGHT,
            litAt = 1_790_000_000_000L,
            logicalDay = 20_690,
            window = WindowKind.SLEEP_GATE,
            sourcePackage = null,
            sourceRef = null,
        )

        val skyStar = row.toSkyStar()

        assertEquals(7, skyStar.index)
        assertEquals(StarKind.QUIET_NIGHT, skyStar.kind)
        assertEquals(1_790_000_000_000L, skyStar.litWall)
        assertEquals(20_690, skyStar.logicalDay)
        assertNull(skyStar.sourcePackage)
    }

    @Test
    fun converters_roundTripEveryColumnEnum() {
        val converters = Converters()

        StarKind.entries.forEach {
            assertEquals(it, converters.stringToStarKind(converters.starKindToString(it)))
        }
        WindowKind.entries.forEach {
            assertEquals(it, converters.stringToWindowKind(converters.windowKindToString(it)))
        }
        SurfaceKind.entries.forEach {
            assertEquals(it, converters.stringToSurfaceKind(converters.surfaceKindToString(it)))
        }
        OpenOutcome.entries.forEach {
            assertEquals(it, converters.stringToOpenOutcome(converters.openOutcomeToString(it)))
        }
        GrantKind.entries.forEach {
            assertEquals(it, converters.stringToGrantKind(converters.grantKindToString(it)))
        }
        GrantEnd.entries.forEach {
            assertEquals(it, converters.stringToGrantEnd(converters.grantEndToString(it)))
        }
        FocusOutcome.entries.forEach {
            assertEquals(it, converters.stringToFocusOutcome(converters.focusOutcomeToString(it)))
        }
        SoundKind.entries.forEach {
            assertEquals(it, converters.stringToSoundKind(converters.soundKindToString(it)))
        }
        ReflectionReason.entries.forEach {
            assertEquals(it, converters.stringToReflectionReason(converters.reflectionReasonToString(it)))
        }
        SurfaceMode.entries.forEach {
            assertEquals(it, converters.stringToSurfaceMode(converters.surfaceModeToString(it)))
        }
    }

    @Test
    fun converters_storeEnumNamesNotOrdinals() {
        val converters = Converters()

        assertEquals("PRAYER_STAY", converters.grantKindToString(GrantKind.PRAYER_STAY))
        assertEquals("LEFT_GESTURE", converters.openOutcomeToString(OpenOutcome.LEFT_GESTURE))
        assertEquals("QUIET_NIGHT", converters.starKindToString(StarKind.QUIET_NIGHT))
    }

    @Test
    fun historyDeleteAll_clearsExactlyTheFiveHistoryTables() = runTest {
        val history = RecordingHistoryDao()
        val stars = RecordingStarDao()

        HistoryRepositoryImpl(history, stars).deleteAll()

        assertEquals(
            listOf("open_events", "grants", "focus_sessions", "reflections", "usage_days"),
            history.deletedTables,
        )
        assertFalse(stars.deleted, "the sky is not history: StarRepository owns its deletion")
    }

    @Test
    fun recordStarLit_writesThroughTheStarDao() = runTest {
        val history = RecordingHistoryDao()
        val stars = RecordingStarDao()

        HistoryRepositoryImpl(history, stars).record(
            RecordEvent.StarLit(
                StarAward(
                    kind = StarKind.LEAVE,
                    litWall = 1L,
                    logicalDay = 20_690,
                    window = WindowKind.NONE,
                    sourcePackage = null,
                    sourceRef = null,
                ),
            ),
        )

        assertEquals(1, stars.inserted.size)
        assertEquals(StarKind.LEAVE, stars.inserted.single().kind)
        assertTrue(history.insertedEvents.isEmpty())
    }

    @Test
    fun weekInput_foldsTheDayRangeIntoTheContractShape() = runTest {
        val grantStartedAt = 1_790_000_000_000L
        val history = RecordingHistoryDao(
            usageRows = listOf(
                UsageDayEntity(20_690, "com.instagram.android", 2_400_000L, opens = 9, nightMs = 0L),
                UsageDayEntity(20_691, "com.instagram.android", 600_000L, opens = 3, nightMs = 120_000L),
            ),
            starRows = listOf(
                starRow(20_690, StarKind.LEAVE),
                starRow(20_691, StarKind.QUIET_NIGHT),
                starRow(20_692, StarKind.QUIET_NIGHT),
                starRow(20_693, StarKind.QUIET_NIGHT),
            ),
            grantRows = listOf(
                GrantEntity(
                    id = "g1",
                    kind = GrantKind.STAY,
                    startedAt = grantStartedAt,
                    grantedMs = 300_000L,
                    consumedMs = 90_000L,
                    endedAt = grantStartedAt + 90_000L,
                    endReason = GrantEnd.LEFT,
                    firstPackage = "com.instagram.android",
                    window = WindowKind.NONE,
                    logicalDay = 20_690,
                ),
            ),
            focusTotalMs = 3_600_000L,
        )

        val input = HistoryRepositoryImpl(history, RecordingStarDao())
            .weekInput(days = 20_690..20_696, prevDays = 20_683..20_689, config = GuardConfig())

        assertEquals(20_690..20_696, input.days)
        assertEquals(2, input.usage.size)
        assertEquals(60, input.focusMinutes)
        assertEquals(3, input.quietNights, "one quiet night per day, not one per star")
        assertEquals(24, input.hourlyMs.size)
        assertEquals(90_000L, input.hourlyMs.sum())
        assertEquals(1, input.hourlyMs.count { it > 0L }, "the whole grant lands in a single hour")
        assertFalse(input.approximate)
    }

    @Test
    fun weekInput_isApproximateUntilUsageStatsHasRows() = runTest {
        val history = RecordingHistoryDao(starRows = listOf(starRow(20_690, StarKind.LEAVE)))

        val input = HistoryRepositoryImpl(history, RecordingStarDao())
            .weekInput(days = 20_690..20_696, prevDays = 20_683..20_689, config = GuardConfig())

        assertTrue(input.approximate)
        assertEquals(0, input.focusMinutes)
        assertEquals(0, input.quietNights)
    }

    private fun focusClosed(completed: Boolean): FocusSessionEntity {
        val focus = FocusState(
            id = "f7",
            plannedMs = 1_500_000L,
            elapsedMs = 1_500_000L,
            lastAccountedElapsed = 1_500_000L,
            startedWall = 1_790_000_000_000L,
            sound = SoundKind.RAIN,
        )
        return RecordEvent.FocusClosed(
            focus = focus,
            endedAt = 1_790_000_000_000L,
            completed = completed,
            stars = 3,
            logicalDay = 20_690,
        ).toEntity()
    }
}

/** Records writes and serves canned rows, so the repository can be exercised without Room. */
private class RecordingHistoryDao(
    private val usageRows: List<UsageDayEntity> = emptyList(),
    private val starRows: List<StarEntity> = emptyList(),
    private val openRows: List<OpenEventEntity> = emptyList(),
    private val reflectionRows: List<ReflectionEntity> = emptyList(),
    private val grantRows: List<GrantEntity> = emptyList(),
    private val focusTotalMs: Long = 0L,
) : HistoryDao {

    val deletedTables: MutableList<String> = mutableListOf()
    val insertedEvents: MutableList<Any> = mutableListOf()

    override suspend fun insertOpen(row: OpenEventEntity) {
        insertedEvents += row
    }

    override suspend fun insertGrant(row: GrantEntity) {
        insertedEvents += row
    }

    override suspend fun insertFocus(row: FocusSessionEntity) {
        insertedEvents += row
    }

    override suspend fun insertReflection(row: ReflectionEntity) {
        insertedEvents += row
    }

    override suspend fun upsertUsageDays(rows: List<UsageDayEntity>) {
        insertedEvents.addAll(rows)
    }

    override suspend fun opensBetween(fromDay: Int, toDay: Int): List<OpenEventEntity> = openRows

    override suspend fun reflectionsBetween(fromDay: Int, toDay: Int): List<ReflectionEntity> = reflectionRows

    override suspend fun starsBetween(fromDay: Int, toDay: Int): List<StarEntity> = starRows

    override suspend fun usageBetween(fromDay: Int, toDay: Int): List<UsageDayEntity> = usageRows

    override suspend fun grantsBetween(fromDay: Int, toDay: Int): List<GrantEntity> = grantRows

    override suspend fun focusMsBetween(fromDay: Int, toDay: Int): Long = focusTotalMs

    override suspend fun deleteOpens() {
        deletedTables += "open_events"
    }

    override suspend fun deleteGrants() {
        deletedTables += "grants"
    }

    override suspend fun deleteFocusSessions() {
        deletedTables += "focus_sessions"
    }

    override suspend fun deleteReflections() {
        deletedTables += "reflections"
    }

    override suspend fun deleteUsageDays() {
        deletedTables += "usage_days"
    }
}

private class RecordingStarDao : StarDao {

    val inserted: MutableList<StarEntity> = mutableListOf()
    var deleted: Boolean = false

    override fun observeAll(): Flow<List<StarEntity>> = flowOf(emptyList())

    override suspend fun insert(entity: StarEntity): Long {
        inserted += entity
        return inserted.size.toLong()
    }

    override suspend fun count(): Int = inserted.size

    override suspend fun deleteAll() {
        deleted = true
    }
}

private fun starRow(logicalDay: Int, kind: StarKind): StarEntity = StarEntity(
    kind = kind,
    litAt = logicalDay.toLong() * 86_400_000L,
    logicalDay = logicalDay,
    window = WindowKind.NONE,
    sourcePackage = null,
    sourceRef = null,
)
