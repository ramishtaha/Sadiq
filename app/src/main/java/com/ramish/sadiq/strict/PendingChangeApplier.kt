package com.ramish.sadiq.strict

import android.util.Log
import com.ramish.sadiq.AppInitializer
import com.ramish.sadiq.contract.GuardController
import com.ramish.sadiq.contract.PendingChangesRepository
import com.ramish.sadiq.contract.SettingsRepository
import com.ramish.sadiq.core.time.Clock
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Applies settings changes whose "Think it over" wait has ended.
 *
 * A pending change is only as real as the moment it lands, and the app has no reliable background
 * scheduler: HyperOS kills timers, and an exact alarm for a settings edit would be a permission
 * request for something the user will see the next time he opens Sadiq anyway. So the queue is
 * drained at the two moments it can be:
 *
 * - on **any engine input**, which is every accessibility window change, screen broadcast, deadline
 *   and surface choice — the app is demonstrably in use at that point;
 * - every [POLL_MS] while the process is alive, so a change due while the user is sitting on the Sky
 *   screen still lands without a restart.
 *
 * Both triggers funnel into [applyDue], which is serialised by a mutex: two inputs arriving together
 * must not apply the same row twice.
 *
 * The engine-input path first checks the cached queue size, so an accessibility burst does not turn
 * into a database query per event. The five-minute path always checks, because the cache is only as
 * fresh as the last Room emission.
 *
 * Each row is removed **after** its change was written. A write that fails leaves the row queued for
 * the next pass rather than dropping a change the user asked for.
 */
@Singleton
class PendingChangeApplier @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val pendingChanges: PendingChangesRepository,
    private val changeGate: ChangeGateImpl,
    private val controller: GuardController,
    private val clock: Clock,
) : AppInitializer {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val mutex = Mutex()

    /** How many rows the last Room emission carried; zero means an engine input can skip the query. */
    private val queued = AtomicInteger(0)

    /**
     * Start the two triggers. Called once per process by `SadiqApp`, after Hilt injection.
     *
     * Idempotence is not needed: `SadiqApp` starts each initializer once, and a second call would
     * only add a duplicate poll loop, which the mutex makes harmless.
     */
    override fun start() {
        scope.launch {
            pendingChanges.observe().collect { rows -> queued.set(rows.size) }
        }
        scope.launch { poll() }
        scope.launch {
            controller.state.collect {
                if (queued.get() > 0) applyDueOnce()
            }
        }
    }

    /**
     * Apply every change whose time has come.
     *
     * @param nowWall the wall time to judge the queue against; the engine clock by default, so a test
     *   can place itself in the future without waiting.
     * @return how many changes were written.
     */
    suspend fun applyDue(nowWall: Long = clock.now().wallMs): Int = mutex.withLock {
        var applied = 0
        for (pending in pendingChanges.due(nowWall)) {
            try {
                // The bypass is what stops the gate from deferring the edit a second time: this row
                // has already waited, and re-judging it would restart its clock forever.
                changeGate.applyingDueChange { settingsRepository.apply(pending.change) }
                pendingChanges.remove(pending.id)
                applied++
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                // Leave the row in place. The alternative is losing a change the user confirmed and
                // waited for because one write happened to fail.
                Log.w(TAG, "pending change ${pending.id} not applied: ${failure.message}", failure)
            }
        }
        applied
    }

    /** One poll iteration, isolated so a database failure cannot kill the loop. */
    private suspend fun applyDueOnce() {
        try {
            applyDue()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            Log.w(TAG, "pending change pass failed: ${failure.message}", failure)
        }
    }

    /** The five-minute heartbeat while the app is open. */
    private suspend fun poll() {
        while (true) {
            applyDueOnce()
            delay(POLL_MS)
        }
    }

    private companion object {
        const val TAG = "SP/Strict"

        /** §WP-27: every five minutes while the app is open. */
        const val POLL_MS: Long = 5 * 60_000L
    }
}
