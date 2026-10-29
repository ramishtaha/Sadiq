package com.ramish.sadiq.guard

import com.ramish.sadiq.contract.EngineSnapshotStore
import com.ramish.sadiq.contract.UsageHistorySource
import com.ramish.sadiq.core.economy.DefaultStarEconomy
import com.ramish.sadiq.core.engine.Effect
import com.ramish.sadiq.core.engine.EngineState
import com.ramish.sadiq.core.engine.GuardEngine
import com.ramish.sadiq.core.engine.GuardInput
import com.ramish.sadiq.core.engine.GuardReducer
import com.ramish.sadiq.core.engine.Phase
import com.ramish.sadiq.core.engine.SurfaceKind
import com.ramish.sadiq.core.engine.Transition
import com.ramish.sadiq.core.engine.UsageSpan
import com.ramish.sadiq.core.engine.Choice
import com.ramish.sadiq.core.insight.DefaultNextActionSuggester
import com.ramish.sadiq.core.insight.DefaultReflectionPolicy
import com.ramish.sadiq.core.insight.UsageDayRow
import com.ramish.sadiq.core.model.GuardedApp
import com.ramish.sadiq.core.model.GuardConfig
import com.ramish.sadiq.core.model.GuardContext
import com.ramish.sadiq.core.model.ProtectedSpan
import com.ramish.sadiq.core.schedule.ScheduleEvaluator
import com.ramish.sadiq.core.time.TimeSnapshot
import com.ramish.sadiq.testing.FakeClock
import com.ramish.sadiq.testing.FakePermissionStatusProvider
import com.ramish.sadiq.testing.FakeSettingsRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/**
 * The five promises the controller makes: one input at a time, in order, state before effects, the
 * state on disk when it matters, and a timer that actually wakes the engine.
 *
 * The tests drive the *real* `GuardEngine` unless they are about the queue itself, so what is proven
 * here is the whole path an accessibility event takes: context, reducer, published state, effects,
 * snapshot, deadline.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GuardControllerImplTest {

    @Test
    fun inputsAreProcessedInOrderAndOneAtATime() = runTest {
        val reducer = ChainReducer()
        val rig = Rig(backgroundScope, reducer = reducer)
        rig.controller.start()
        runCurrent()

        // Four real threads, each pushing a quarter of the inputs: the channel is the only thing
        // that decides the order, and the controller must preserve whatever order it accepted.
        withContext(Dispatchers.Default) {
            coroutineScope {
                repeat(WORKERS) { worker ->
                    launch {
                        repeat(INPUTS_PER_WORKER) { index ->
                            rig.controller.submit(
                                GuardInput.ForegroundChanged("com.example.worker$worker.app$index")
                            )
                        }
                    }
                }
            }
        }

        advanceUntilIdle()
        runCurrent()

        val total = WORKERS * INPUTS_PER_WORKER
        assertEquals(total, reducer.seen.size, "every input must be processed")
        assertEquals(total, reducer.seen.distinct().size, "an input was processed twice")
        assertEquals(0, reducer.overlaps, "two inputs entered the reducer at once")
        // `seq` only advances if each call received the previous call's output, so this is the
        // ordering proof: a stale state anywhere in the chain would leave `seq` below the total.
        assertEquals(total.toLong(), rig.controller.state.value.seq)
    }

    @Test
    fun stateIsPublishedBeforeItsEffects() = runTest {
        val rig = Rig(backgroundScope)
        val observed = mutableListOf<Pair<Effect, Phase>>()
        val collector = backgroundScope.launch {
            rig.controller.effects.collect { effect ->
                observed += effect to rig.controller.state.value.phase
            }
        }
        rig.controller.start()
        runCurrent()

        rig.controller.submit(GuardInput.ForegroundChanged(INSTAGRAM))
        // `runCurrent` rather than `advanceUntilIdle`: the input's resumption is dispatched at the
        // current virtual time, and `advanceUntilIdle` does not run a task dispatched by the task
        // that is still executing.
        runCurrent()
        runCurrent()

        assertTrue(observed.any { it.first is Effect.ShowSurface }, "no surface was requested")
        for ((effect, phase) in observed) {
            assertEquals(Phase.PROMPTING, phase, "$effect was emitted while the state said $phase")
        }
        collector.cancel()
    }

    @Test
    fun aPhaseChangeIsSavedWithoutWaiting() = runTest {
        val rig = Rig(backgroundScope)
        rig.controller.start()
        runCurrent()
        val afterStartUp = rig.store.saves.size

        rig.controller.submit(GuardInput.ForegroundChanged(INSTAGRAM))
        runCurrent()

        assertEquals(afterStartUp + 1, rig.store.saves.size)
        assertEquals(Phase.PROMPTING, rig.store.saves.last().phase)
    }

    @Test
    fun aSamePhaseChangeWaitsForTheDebounce() = runTest {
        val rig = Rig(backgroundScope)
        rig.controller.start()
        runCurrent()
        rig.controller.submit(GuardInput.ForegroundChanged(INSTAGRAM))
        runCurrent()
        val afterPrompt = rig.store.saves.size

        // T18: another guarded app behind the same surface. The phase does not change, so this is
        // a debounced write rather than an immediate one.
        rig.controller.submit(GuardInput.ForegroundChanged(YOUTUBE))
        runCurrent()
        assertEquals(afterPrompt, rig.store.saves.size, "a same-phase change was written at once")

        advanceTimeBy(299)
        runCurrent()
        assertEquals(afterPrompt, rig.store.saves.size)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(afterPrompt + 1, rig.store.saves.size)
        assertEquals(YOUTUBE, rig.store.saves.last().prompt?.packageName)
    }

    @Test
    fun restartRestoresTheSavedState() = runTest {
        val clock = FakeClock()
        val settings = FakeSettingsRepository(initialConfig = GUARDED)
        val store = RecordingStore()

        val first = Rig(backgroundScope, clock = clock, settings = settings, store = store)
        first.controller.start()
        runCurrent()
        first.controller.submit(GuardInput.ForegroundChanged(INSTAGRAM))
        runCurrent()
        assertEquals(Phase.PROMPTING, store.saved?.phase)

        // A new controller over the same store is what a killed process looks like from the data
        // layer's side. Nothing is replayed from memory: only `load()` can produce this state.
        val second = Rig(backgroundScope, clock = clock, settings = settings, store = store)
        second.controller.start()
        runCurrent()

        assertEquals(Phase.PROMPTING, second.controller.state.value.phase)
        assertEquals(INSTAGRAM, second.controller.state.value.prompt?.packageName)
    }

    @Test
    fun aGrantDeadlineWakesTheEngineAtTheScheduledTime() = runTest {
        val rig = Rig(backgroundScope)
        rig.controller.start()
        runCurrent()
        rig.controller.submit(GuardInput.ForegroundChanged(INSTAGRAM))
        runCurrent()
        rig.controller.submit(GuardInput.Chose(Choice.Stay(2)))
        runCurrent()
        assertEquals(Phase.GRANTED, rig.controller.state.value.phase)
        assertEquals(0, rig.engine.inputs.count { it is GuardInput.Tick })

        advanceBy(rig, 119_999)
        assertEquals(Phase.GRANTED, rig.controller.state.value.phase, "the grant ended early")

        advanceBy(rig, 1)

        assertEquals(1, rig.engine.inputs.count { it is GuardInput.Tick }, "the deadline never fired")
        assertEquals(Phase.PROMPTING, rig.controller.state.value.phase)
        assertEquals(SurfaceKind.TIME_UP, rig.controller.state.value.prompt?.kind)
    }

    @Test
    fun usageIsReconciledOnStartWhenAccessIsGranted() = runTest {
        val permissions = FakePermissionStatusProvider().apply { update { it.copy(usageAccess = true) } }
        val now = FakeClock.EVENING
        val usage = FakeUsage(
            spans = listOf(UsageSpan(INSTAGRAM, now.wallMs - 10 * MINUTE_MS, now.wallMs - 5 * MINUTE_MS))
        )
        val rig = Rig(backgroundScope, usage = usage, permissions = permissions)

        rig.controller.start()
        runCurrent()
        runCurrent()

        assertEquals(now.wallMs, usage.requestedFromWall, "the query must start at lastSeenWall")
        assertNotNull(
            rig.engine.inputs.filterIsInstance<GuardInput.Reconcile>().singleOrNull(),
            "the spans never reached the engine",
        )
    }

    @Test
    fun usageIsNotQueriedWithoutAccess() = runTest {
        val usage = FakeUsage(spans = listOf(UsageSpan(INSTAGRAM, 1L, 2L)))
        val rig = Rig(backgroundScope, usage = usage)

        rig.controller.start()
        runCurrent()
        runCurrent()

        assertNull(usage.requestedFromWall)
        assertTrue(rig.engine.inputs.none { it is GuardInput.Reconcile })
    }

    @Test
    fun resetReturnsTheEngineToItsInitialState() = runTest {
        val rig = Rig(backgroundScope)
        rig.controller.start()
        runCurrent()
        rig.controller.submit(GuardInput.ForegroundChanged(INSTAGRAM))
        runCurrent()
        assertEquals(Phase.PROMPTING, rig.controller.state.value.phase)

        rig.controller.reset()
        runCurrent()

        assertEquals(Phase.IDLE, rig.controller.state.value.phase)
        assertNull(rig.controller.state.value.prompt)
        assertEquals(Phase.IDLE, rig.store.saves.last().phase)
    }

    /** Advance the virtual scheduler and the fake clock by the same number of milliseconds. */
    private fun TestScope.advanceBy(rig: Rig, millis: Long) {
        val current = rig.clock.current
        rig.clock.set(
            current.copy(
                wallMs = current.wallMs + millis,
                elapsedMs = current.elapsedMs + millis
            )
        )
        advanceTimeBy(millis)
        runCurrent()
    }

    // ── the rig ─────────────────────────────────────────────────────────────────────────────────

    private class Rig(
        scope: CoroutineScope,
        val clock: FakeClock = FakeClock(),
        val settings: FakeSettingsRepository = FakeSettingsRepository(initialConfig = GUARDED),
        val store: RecordingStore = RecordingStore(),
        val usage: FakeUsage = FakeUsage(),
        val permissions: FakePermissionStatusProvider = FakePermissionStatusProvider(),
        val engine: RecordingEngine = RecordingEngine(),
        reducer: GuardReducer = engine,
    ) {
        val controller: GuardControllerImpl = GuardControllerImpl(
            reducer = reducer,
            clock = clock,
            assembler = ContextAssembler(NoWindowSchedule),
            settings = settings,
            snapshots = store,
            usage = usage,
            permissions = permissions,
            scope = scope,
        )
    }

    private companion object {
        const val INSTAGRAM = "com.instagram.android"
        const val YOUTUBE = "com.youtube.android"
        const val MINUTE_MS = 60_000L
        const val WORKERS = 4
        const val INPUTS_PER_WORKER = 25

        /** Two guarded apps, reflection off: every guarded open becomes a plain pause. */
        val GUARDED: GuardConfig = GuardConfig(
            apps = listOf(
                GuardedApp(packageName = INSTAGRAM, label = "Instagram"),
                GuardedApp(packageName = YOUTUBE, label = "YouTube"),
            ),
            reflectionEnabled = false,
        )
    }

    /** Keeps the saved snapshot in memory and records every write. */
    private class RecordingStore : EngineSnapshotStore {
        var saved: EngineState? = null

        val saves = mutableListOf<EngineState>()

        override suspend fun load(): EngineState? = saved

        override suspend fun save(state: EngineState) {
            saved = state
            saves += state
        }
    }

    /** Usage stats the test controls, recording the window it was asked for. */
    private class FakeUsage(
        private val spans: List<UsageSpan> = emptyList(),
        private val access: Boolean = true,
    ) : UsageHistorySource {
        var requestedFromWall: Long? = null
            private set

        override fun hasAccess(): Boolean = access

        override suspend fun spans(fromWall: Long, toWall: Long): List<UsageSpan> {
            requestedFromWall = fromWall
            return spans
        }

        override suspend fun dailyRows(days: IntRange, zoneId: String, config: GuardConfig): List<UsageDayRow> =
            emptyList()

        override suspend fun hourlyMs(days: IntRange, zoneId: String, packages: Set<String>): LongArray =
            LongArray(24)
    }

    /** A schedule with no window and no prayers, so a test's surface choice is only the pause. */
    private object NoWindowSchedule : ScheduleEvaluator {
        override fun contextAt(now: TimeSnapshot, config: GuardConfig, lastSeenWall: Long): GuardContext =
            contextOf(now, config)

        override fun endedProtectedWindows(
            fromWall: Long,
            toWall: Long,
            zoneId: String,
            config: GuardConfig,
        ): List<ProtectedSpan> = emptyList()
    }

    /** The real engine, with a note of every input it was handed. */
    private class RecordingEngine : GuardReducer {
        private val delegate: GuardReducer = realEngine()

        val inputs = mutableListOf<GuardInput>()

        override fun initial(now: TimeSnapshot, ctx: GuardContext): EngineState =
            delegate.initial(now, ctx)

        override fun reduce(
            state: EngineState,
            input: GuardInput,
            now: TimeSnapshot,
            ctx: GuardContext,
        ): Transition {
            inputs += input
            return delegate.reduce(state, input, now, ctx)
        }

        override fun nextDeadlineElapsed(state: EngineState, now: TimeSnapshot, ctx: GuardContext): Long? =
            delegate.nextDeadlineElapsed(state, now, ctx)
    }

    /**
     * A reducer that only counts, and whose state carries the chain.
     *
     * It records re-entry (which would be a broken single-consumer guarantee) and advances `seq` by
     * one per call, so a stale state handed to a later input shows up as a missing increment.
     */
    private class ChainReducer : GuardReducer {
        private val delegate: GuardReducer = realEngine()

        val seen = mutableListOf<GuardInput>()

        var overlaps: Int = 0
            private set

        private var busy: Boolean = false

        override fun initial(now: TimeSnapshot, ctx: GuardContext): EngineState =
            delegate.initial(now, ctx)

        override fun reduce(
            state: EngineState,
            input: GuardInput,
            now: TimeSnapshot,
            ctx: GuardContext,
        ): Transition {
            if (busy) overlaps += 1
            busy = true
            try {
                seen += input
                return Transition(state.copy(seq = state.seq + 1), emptyList())
            } finally {
                busy = false
            }
        }

        override fun nextDeadlineElapsed(state: EngineState, now: TimeSnapshot, ctx: GuardContext): Long? =
            null
    }
}

/** The production reducer, built exactly as `GuardModule` builds it. */
private fun realEngine(): GuardReducer = GuardEngine(
    economy = DefaultStarEconomy(),
    reflection = DefaultReflectionPolicy(),
    suggester = DefaultNextActionSuggester(),
)
