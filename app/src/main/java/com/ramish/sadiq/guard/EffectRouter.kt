package com.ramish.sadiq.guard

import android.util.Log
import com.ramish.sadiq.contract.GuardController
import com.ramish.sadiq.contract.HomeAction
import com.ramish.sadiq.contract.LiveNotifier
import com.ramish.sadiq.contract.MediaDucker
import com.ramish.sadiq.contract.SoundController
import com.ramish.sadiq.contract.SurfacePresenter
import com.ramish.sadiq.core.engine.Effect
import com.ramish.sadiq.core.time.Clock
import com.ramish.sadiq.di.GuardWork
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Performs the reducer's effects (§3.5).
 *
 * The engine decides; this class only carries the decision to the one collaborator that can act on
 * it. Every [Effect] maps to exactly one call, which is what makes the routing auditable: a surface
 * effect can never also send the user home, and a record can never also play a sound.
 *
 * Dispatch is sequential and on one collector coroutine, so the effects of a transition happen in
 * the order the reducer listed them — `DuckMedia` before `ShowSurface` before `Record` for a guarded
 * open, for instance.
 *
 * A failing collaborator is logged and skipped rather than allowed to tear down the collector: a
 * Room write that fails must not stop the next question from appearing.
 */
@Singleton
class EffectRouter @Inject constructor(
    private val controller: GuardController,
    private val surfaces: SurfacePresenter,
    private val home: HomeAction,
    private val media: MediaDucker,
    private val live: LiveNotifier,
    private val sound: SoundController,
    private val recorder: Recorder,
    private val clock: Clock,
    @GuardWork private val scope: CoroutineScope,
) {

    private val started = AtomicBoolean(false)

    /**
     * Subscribe to the controller's effects. Idempotent; called once per process by `GuardStartup`.
     *
     * The collector lives on the background scope rather than on the engine's single thread, because
     * a few of the collaborators block: starting an activity, writing to Room, requesting audio
     * focus. None of that may sit in front of the next input.
     */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            controller.effects.collect { effect ->
                try {
                    dispatch(effect)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Throwable) {
                    Log.w(TAG, "effect ${effect::class.simpleName} failed: ${failure.message}", failure)
                }
            }
        }
    }

    /**
     * Perform one effect.
     *
     * Public and suspend so tests can drive it directly, without a running engine or a scheduler.
     *
     * @param effect the effect to perform.
     */
    suspend fun dispatch(effect: Effect) {
        when (effect) {
            is Effect.ShowSurface -> surfaces.show(effect.ui)
            is Effect.UpdateSurface -> surfaces.update(effect.ui)
            Effect.DismissSurface -> surfaces.dismiss()
            Effect.GoHome -> home.goHome()
            is Effect.OpenSadiq -> home.openSadiq(effect.route)
            Effect.DuckMedia -> media.duck()
            Effect.ReleaseMedia -> media.release()
            is Effect.Live -> live.show(effect.state, clock.now())
            is Effect.PlaySound -> sound.play(effect.kind, effect.durationMs)
            Effect.StopSound -> sound.stop()
            is Effect.Record -> recorder.record(effect.event)
            is Effect.FocusCompleted -> live.focusComplete(effect.stars)
        }
    }

    private companion object {
        const val TAG = "SP/Effects"
    }
}
