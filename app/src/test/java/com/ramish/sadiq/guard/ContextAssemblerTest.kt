package com.ramish.sadiq.guard

import com.ramish.sadiq.core.model.ActiveWindow
import com.ramish.sadiq.core.model.GuardConfig
import com.ramish.sadiq.core.model.GuardContext
import com.ramish.sadiq.core.model.PrayerDay
import com.ramish.sadiq.core.model.ProtectedSpan
import com.ramish.sadiq.core.model.WindowKind
import com.ramish.sadiq.core.schedule.DefaultScheduleEvaluator
import com.ramish.sadiq.core.schedule.ScheduleEvaluator
import com.ramish.sadiq.core.time.TimeSnapshot
import com.ramish.sadiq.testing.FakeClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The one thing the assembler must get right: the context it hands the reducer describes *this*
 * instant and still knows when the previous input was.
 *
 * A window that ended while nothing was running is only reported because [GuardContext] carries
 * `endedProtected`, and that list is computed from `lastSeenWall` — pass the current wall time
 * instead and an untouched night earns nothing (§3.7).
 */
class ContextAssemblerTest {

    @Test
    fun passesThePreviousWallTimeAsTheLowerBound() {
        val schedule = RecordingSchedule()
        val assembler = ContextAssembler(schedule)
        val now = FakeClock.EVENING
        val lastSeenWall = now.wallMs - 90 * 60_000L

        assembler.assemble(now = now, config = GuardConfig(), lastSeenWall = lastSeenWall)

        assertEquals(lastSeenWall, schedule.seenLastSeenWall)
        assertEquals(now.wallMs, schedule.seenToWall)
    }

    @Test
    fun passesTheClockAndTheSettingsInForceThrough() {
        val schedule = RecordingSchedule()
        val assembler = ContextAssembler(schedule)
        val config = GuardConfig(apps = emptyList(), webGuardEnabled = true)

        assembler.assemble(now = FakeClock.EVENING, config = config, lastSeenWall = 0L)

        assertSame(config, schedule.seenConfig)
        assertSame(FakeClock.EVENING, schedule.seenNow)
    }

    @Test
    fun returnsTheEvaluatorsContextUnchanged() {
        val schedule = RecordingSchedule()
        val assembler = ContextAssembler(schedule)

        val ctx = assembler.assemble(FakeClock.EVENING, schedule.context, 1_234L)

        assertSame(schedule.context, ctx)
    }

    @Test
    fun anUntouchedNightStillReportsTheSleepGateThatEnded() {
        // 21:10 on 29 October to 07:00 the next morning: the phone was never picked up, and the gate
        // still ended at Fajr, which is the whole point of spending lastSeenWall on this call.
        val evening = FakeClock.EVENING
        val morning = evening.copy(wallMs = evening.wallMs + 9 * HOUR + 50 * MINUTE_MS)
        val assembler = ContextAssembler(DefaultScheduleEvaluator())

        val ctx = assembler.assemble(now = morning, config = GuardConfig(), lastSeenWall = evening.wallMs)

        assertTrue(
            ctx.endedProtected.any { it.kind == WindowKind.SLEEP_GATE },
            "expected the sleep gate in ${ctx.endedProtected}",
        )
    }

    @Test
    fun aStartWithNoSavedStateReportsNothingEnded() {
        // The controller passes `now.wallMs` when there is no snapshot, which must not fabricate a
        // night that never happened.
        val assembler = ContextAssembler(DefaultScheduleEvaluator())
        val now = FakeClock.EVENING

        val ctx = assembler.assemble(now = now, config = GuardConfig(), lastSeenWall = now.wallMs)

        assertTrue(ctx.endedProtected.isEmpty(), "expected nothing, got ${ctx.endedProtected}")
    }

    private companion object {
        const val MINUTE_MS = 60_000L
        const val HOUR = 60 * MINUTE_MS
    }
}

/**
 * A [ScheduleEvaluator] that records its arguments and returns whatever the test told it to.
 *
 * Distinct from the real evaluator on purpose: this test is about the wiring of `lastSeenWall`, not
 * about Adhan's arithmetic, which `DefaultScheduleEvaluator`'s own tests already pin.
 */
private class RecordingSchedule : ScheduleEvaluator {

    var seenNow: TimeSnapshot? = null
    var seenConfig: GuardConfig? = null
    var seenLastSeenWall: Long? = null
    var seenToWall: Long? = null

    /** The context [contextAt] returns. */
    val context: GuardContext = contextOf(FakeClock.EVENING, GuardConfig())

    override fun contextAt(now: TimeSnapshot, config: GuardConfig, lastSeenWall: Long): GuardContext {
        seenNow = now
        seenConfig = config
        seenLastSeenWall = lastSeenWall
        seenToWall = now.wallMs
        return context
    }

    override fun endedProtectedWindows(
        fromWall: Long,
        toWall: Long,
        zoneId: String,
        config: GuardConfig,
    ): List<ProtectedSpan> = emptyList()
}

/** The smallest complete [GuardContext] a test can build: no window, no prayers, nothing ended. */
internal fun contextOf(
    now: TimeSnapshot,
    config: GuardConfig,
    logicalDay: Int = 0,
    endedProtected: List<ProtectedSpan> = emptyList(),
): GuardContext = GuardContext(
    config = config,
    window = ActiveWindow(kind = WindowKind.NONE, startWall = null, endWall = null),
    logicalDay = logicalDay,
    today = PrayerDay(
        epochDay = logicalDay,
        fajr = 0L,
        sunrise = 0L,
        dhuhr = 0L,
        asr = 0L,
        maghrib = 0L,
        isha = 0L,
        nextFajr = 0L,
        isFriday = false,
    ),
    nextPrayer = null,
    nextPrayerWall = null,
    ramadanActive = false,
    minuteOfDay = 0,
    endedProtected = endedProtected,
)
