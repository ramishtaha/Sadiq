package com.ramish.sadiq.guard

import android.util.Log
import com.ramish.sadiq.contract.EngineSnapshotStore
import com.ramish.sadiq.contract.GuardController
import com.ramish.sadiq.contract.PermissionStatusProvider
import com.ramish.sadiq.contract.SettingsRepository
import com.ramish.sadiq.contract.UsageHistorySource
import com.ramish.sadiq.core.engine.Effect
import com.ramish.sadiq.core.engine.EngineState
import com.ramish.sadiq.core.engine.GuardInput
import com.ramish.sadiq.core.engine.GuardReducer
import com.ramish.sadiq.core.model.ActiveWindow
import com.ramish.sadiq.core.model.GuardConfig
import com.ramish.sadiq.core.model.GuardContext
import com.ramish.sadiq.core.model.PrayerDay
import com.ramish.sadiq.core.model.WindowKind
import com.ramish.sadiq.core.time.Clock
import com.ramish.sadiq.core.time.LogicalDays
import com.ramish.sadiq.core.time.TimeSnapshot
import com.ramish.sadiq.di.GuardLoop
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The engine's driver: one queue, one consumer, one published state (§3.5).
 *
 * Every event in the process — an accessibility window change, a screen broadcast, a notification
 * action, a tap on a surface, the Focus tab, a deadline — becomes a [GuardInput] and is handed to
 * [submit]. Inputs land in an unlimited channel and are read by exactly one coroutine, so the pure
 * reducer can never see two inputs at once and the order the user caused is the order the engine
 * sees.
 *
 * For each input the controller does four things, in this order:
 * 1. assemble the [GuardContext] from the clocks and the settings in force;
 * 2. run [GuardReducer.reduce];
 * 3. publish the new state, *then* emit its effects — a screen reacting to an effect already sees
 *    the state that produced it;
 * 4. persist the state (at once on a phase change, otherwise after 300 ms) and re-arm the deadline
 *    timer.
 *
 * The controller never decides anything itself. It does not pick a surface, award a star or close a
 * grant; that is the reducer's job, and keeping it that way is what makes every rule of §3.6
 * testable without Android.
 *
 * CONTRACT-GAP: neither the contract nor `ShellModule` declares an application-scope
 * `CoroutineScope` (WP-09 noted the same gap for `SettingsRepositoryImpl`). The engine owns its
 * lifetime scope, injected with the [GuardLoop] qualifier and bound in `GuardModule` to
 * `Dispatchers.Default.limitedParallelism(1)`.
 */
@Singleton
class GuardControllerImpl @Inject constructor(
    private val reducer: GuardReducer,
    private val clock: Clock,
    private val context: ContextAssembler,
    private val settings: SettingsRepository,
    private val snapshots: EngineSnapshotStore,
    private val usage: UsageHistorySource,
    private val permissions: PermissionStatusProvider,
    @GuardLoop private val scope: CoroutineScope,
) : GuardController {

    /**
     * The engine state, always readable.
     *
     * Before [start] it carries [bootstrapState]; from the moment the first input is processed it
     * carries the saved snapshot or `initial()` plus every transition since.
     */
    private val mutableState = MutableStateFlow(bootstrapState())

    override val state: StateFlow<EngineState> = mutableState.asStateFlow()

    /**
     * Effects in the order the reducer produced them.
     *
     * No replay: an effect is an action, and replaying "show this surface" to a collector that
     * attaches five minutes later would be wrong. A full buffer suspends the input loop instead of
     * dropping an effect, because losing a `GoHome` or a `DismissSurface` is worse than a slow tick.
     */
    private val mutableEffects = MutableSharedFlow<Effect>(
        replay = 0,
        extraBufferCapacity = EFFECT_BUFFER,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )

    override val effects: SharedFlow<Effect> = mutableEffects.asSharedFlow()

    private val inputs = Channel<Envelope>(capacity = Channel.UNLIMITED)

    private val deadlines = DeadlineScheduler(clock = clock, scope = scope) {
        // Re-queued rather than processed here: the timer coroutine must not run engine code on its
        // own stack, or a deadline would be the one input that could re-enter the reducer.
        inputs.trySend(Envelope.Input(GuardInput.Tick))
    }

    private val saveRequests = Channel<SaveRequest>(capacity = Channel.CONFLATED)

    private val started = AtomicBoolean(false)

    /**
     * Start the consumer and the snapshot writer. Idempotent.
     *
     * Called once per process by `GuardStartup`, after Hilt has constructed everything. Splitting
     * start-up out of the constructor keeps construction cheap: injecting the controller happens on
     * the main thread at process start, and neither the snapshot read nor the usage query belongs
     * there.
     */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch { consume() }
        scope.launch { writeSnapshots() }
    }

    override fun submit(input: GuardInput) {
        // trySend on an unlimited channel never blocks and never fails, which is what lets the
        // accessibility callback and the main thread call this without a coroutine.
        inputs.trySend(Envelope.Input(input))
    }

    /**
     * Replace the engine state with a fresh `initial()`.
     *
     * A debug facility only — `DebugCommandsReceiver`'s `reset`. It is not a [GuardInput] because
     * the reducer deliberately has no "forget everything" transition: the product never resets the
     * engine, and inventing an input for it would put a debug path inside the state machine.
     */
    fun reset() {
        inputs.trySend(Envelope.Reset)
    }

    // ── the consumer ────────────────────────────────────────────────────────────────────────────

    private suspend fun consume() {
        startUp()
        for (envelope in inputs) {
            when (envelope) {
                is Envelope.Input -> process(envelope.input)
                Envelope.Reset -> resetToInitial()
            }
        }
    }

    /**
     * Load the snapshot, publish it, arm the deadline and reconcile.
     *
     * A missing or unreadable snapshot is not an error: the store returns null and the engine starts
     * from `initial()` (§3.4). The state is saved immediately afterwards so the very next process
     * death resumes from a real document instead of from scratch.
     */
    private suspend fun startUp() {
        val now = clock.now()
        val saved = snapshots.load()
        val ctx = context.assemble(
            now = now,
            config = settings.config.value,
            lastSeenWall = saved?.lastSeenWall ?: now.wallMs,
        )
        val restored = saved ?: reducer.initial(now, ctx)
        mutableState.value = restored
        requestSave(restored, immediate = true)
        deadlines.schedule(reducer.nextDeadlineElapsed(restored, now, ctx))
        reconcile()
    }

    /**
     * Charge guarded use that happened while the process was down (§3.5, T32).
     *
     * Only when usage access is granted: without it there is nothing to read, and the Week screen
     * says "Approximate" instead. The query runs beside the input loop rather than in front of it —
     * a busy UsageStats read must never delay the first interception — and its result is submitted
     * as a normal input so it is ordered with everything else.
     */
    private fun reconcile() {
        if (!permissions.snapshot().usageAccess) return
        val fromWall = mutableState.value.lastSeenWall
        scope.launch {
            val now = clock.now()
            val spans = try {
                usage.spans(fromWall = fromWall, toWall = now.wallMs)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                // UsageStats can throw on a vendor ROM that revokes the app-op mid-flight. Losing
                // one reconciliation is a rounding error, not a reason to stop the engine.
                Log.w(TAG, "usage reconciliation skipped (${failure.message})")
                emptyList()
            }
            if (spans.isNotEmpty()) submit(GuardInput.Reconcile(spans))
        }
    }

    /** One engine step: context, transition, publish, effects, persist, re-arm. */
    private suspend fun process(input: GuardInput) {
        val before = mutableState.value
        val now = clock.now()
        val ctx = context.assemble(now, settings.config.value, before.lastSeenWall)
        val transition = reducer.reduce(state = before, input = input, now = now, ctx = ctx)

        // State first, effects second. The contract promises this order so a screen that reacts to
        // `ShowSurface` can read the phase that produced it.
        mutableState.value = transition.state
        for (effect in transition.effects) {
            mutableEffects.emit(effect)
        }

        requestSave(transition.state, immediate = transition.state.phase != before.phase)
        deadlines.schedule(reducer.nextDeadlineElapsed(transition.state, now, ctx))
    }

    /** Replace the state with the fresh-install one, save it and drop any timer. */
    private suspend fun resetToInitial() {
        val now = clock.now()
        val ctx = context.assemble(now, settings.config.value, now.wallMs)
        val fresh = reducer.initial(now, ctx)
        mutableState.value = fresh
        requestSave(fresh, immediate = true)
        deadlines.schedule(reducer.nextDeadlineElapsed(fresh, now, ctx))
    }

    // ── persistence ─────────────────────────────────────────────────────────────────────────────

    /**
     * Ask for the state to be written.
     *
     * A phase change is written at once: the phase is what the user is in the middle of, and losing
     * it to a HyperOS kill would resume a grant as a prompt. Everything else is debounced by
     * [SAVE_DEBOUNCE_MS], because a grant's remaining milliseconds move on every input and a write
     * per input would be a write every few seconds for no gain.
     *
     * The request goes through a conflated channel, so a newer state replaces a pending older one and
     * a save that is already writing is never cancelled halfway through.
     */
    private fun requestSave(state: EngineState, immediate: Boolean) {
        saveRequests.trySend(SaveRequest(state, debounced = !immediate))
    }

    /**
     * The single writer: one save at a time, always the newest requested state.
     *
     * A debounced request waits [SAVE_DEBOUNCE_MS], and any newer request restarts that wait — except
     * an immediate one, which ends it. That is how a phase change inside the 300 ms window is still
     * on disk immediately, without cancelling a write that has already begun.
     */
    private suspend fun writeSnapshots() {
        while (true) {
            var request = saveRequests.receive()
            while (request.debounced) {
                val newer = withTimeoutOrNull(SAVE_DEBOUNCE_MS) { saveRequests.receive() }
                if (newer == null) break
                request = newer
            }
            snapshots.save(request.state)
        }
    }

    // ── bootstrap ───────────────────────────────────────────────────────────────────────────────

    /**
     * The state the flow carries between construction and [start].
     *
     * It is built from a *bootstrap* context — no active window, no prayer day — because assembling
     * the real one would run Adhan on whichever thread injects the controller, and that thread is
     * the main thread at process start. Nothing ever reduces on this value: [startUp] replaces it
     * with the saved state or `initial()` before the first queued input is read, so a UI that
     * observes it for a few milliseconds sees an idle, full-budget engine — which is true.
     */
    private fun bootstrapState(): EngineState {
        val now = clock.now()
        val config = settings.config.value
        return reducer.initial(now, bootstrapContext(now, config))
    }

    /** A context with the real settings and logical day but no schedule. See [bootstrapState]. */
    private fun bootstrapContext(now: TimeSnapshot, config: GuardConfig): GuardContext = GuardContext(
        config = config,
        window = ActiveWindow(kind = WindowKind.NONE, startWall = null, endWall = null),
        logicalDay = LogicalDays.of(now.wallMs, now.zoneId),
        today = PrayerDay(
            epochDay = 0,
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
        endedProtected = emptyList(),
    )

    // ── envelopes ───────────────────────────────────────────────────────────────────────────────

    /** What the queue carries: an engine input, or the debug reset. */
    private sealed interface Envelope {
        /** A normal input. @property input the input. */
        data class Input(val input: GuardInput) : Envelope

        /** Replace the state with a fresh one. */
        data object Reset : Envelope
    }

    /**
     * One save request.
     *
     * @property state the state to write.
     * @property debounced whether to wait [SAVE_DEBOUNCE_MS] first.
     */
    private data class SaveRequest(val state: EngineState, val debounced: Boolean)

    private companion object {
        const val TAG = "SP/Guard"

        /** Enough room for a burst of transitions without ever dropping one. */
        const val EFFECT_BUFFER = 64

        /** §3.6: a non-phase-change snapshot is written at most this often. */
        const val SAVE_DEBOUNCE_MS = 300L
    }
}
