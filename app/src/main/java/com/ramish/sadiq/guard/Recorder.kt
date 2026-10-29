package com.ramish.sadiq.guard

import android.util.Log
import com.ramish.sadiq.contract.HistoryRepository
import com.ramish.sadiq.core.engine.RecordEvent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/**
 * Writes [RecordEvent]s to history (§3.3).
 *
 * `HistoryRepository.record` already owns the mapping from an event to its row — including the star
 * table for [RecordEvent.StarLit] — so this class adds the one thing the effect pipeline needs and
 * the repository must not decide: a write failure is contained. History is a record of what
 * happened, never a precondition for what happens next, and a locked database or a full disk during
 * a pause must not take the interception path down with it.
 */
@Singleton
class Recorder @Inject constructor(
    private val history: HistoryRepository,
) {

    /**
     * Record one fact.
     *
     * @param event the fact to write.
     */
    suspend fun record(event: RecordEvent) {
        try {
            history.record(event)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            // One lost row is better than a broken effect pipeline: the surface still appears.
            Log.w(TAG, "record ${event::class.simpleName} failed: ${failure.message}", failure)
        }
    }

    private companion object {
        const val TAG = "SP/Record"
    }
}
