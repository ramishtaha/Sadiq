package com.ramish.sadiq.session

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ramish.sadiq.contract.GuardController
import com.ramish.sadiq.contract.NotificationContract
import com.ramish.sadiq.core.engine.Choice
import com.ramish.sadiq.core.engine.GuardInput
import com.ramish.sadiq.core.sound.SoundKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One notification action, already translated into what it means.
 *
 * Parsing is kept apart from delivery so the table of buttons can be held by a unit test. A
 * mis-wired action is the one bug in this class that fails silently: the shade closes, nothing
 * crashes, and a grant simply cannot be closed from the notification any more.
 */
internal sealed interface SessionAction {

    /** Submit [input] to the engine, exactly as the equivalent button on a surface does. */
    data class ToEngine(val input: GuardInput) : SessionAction

    /** Start or stop the soundscape. The player, not the engine, owns what is audible. */
    data object ToggleSound : SessionAction

    /** [FocusAlarm]'s broadcast: wake the engine so it can close an expired session. */
    data object Tick : SessionAction

    /** Not a Sadiq action: drop it. */
    data object Ignore : SessionAction
}

/**
 * Translates a notification action string into a [SessionAction].
 *
 * The five actions of [NotificationContract] are part of the app's external surface — a notification
 * posted by an older build must still be answerable after an update — so they are matched by their
 * exact constants, and anything else is ignored rather than guessed at.
 *
 * @param action the intent's action, or null when the intent carries none.
 * @return what to do with it, or [SessionAction.Ignore] for anything Sadiq did not send.
 */
internal fun sessionActionOf(action: String?): SessionAction = when (action) {
    NotificationContract.ACTION_DONE_EARLY -> SessionAction.ToEngine(GuardInput.Chose(Choice.DoneEarly))
    NotificationContract.ACTION_PAUSE -> SessionAction.ToEngine(GuardInput.Chose(Choice.PauseGrant))
    NotificationContract.ACTION_DISCARD -> SessionAction.ToEngine(GuardInput.Chose(Choice.DiscardPaused))
    NotificationContract.ACTION_END_FOCUS -> SessionAction.ToEngine(GuardInput.Chose(Choice.EndFocus))
    NotificationContract.ACTION_TOGGLE_SOUND -> SessionAction.ToggleSound
    FocusAlarm.ACTION_FOCUS_ALARM -> SessionAction.Tick
    else -> SessionAction.Ignore
}

/**
 * Turns the notification's buttons into engine inputs (§3.5, §4.6).
 *
 * The shade is the only place a grant or a focus session can be answered without opening Sadiq, so
 * this receiver is what keeps "I'm done", "Save the rest", "Let it go" and "End" honest: each one
 * becomes the same [Choice] the equivalent surface button submits, and nothing else. The engine
 * decides what the choice means; this class only translates.
 *
 * It also delivers [FocusAlarm]'s broadcast. That alarm is an explicit broadcast to this component
 * because it is the one receiver Sadiq declares for the session, and a broadcast is delivered to a
 * cold process where a background service start would be refused.
 *
 * `goAsync()` is used because the sound toggle reads a preference, which is a suspending DataStore
 * read, and because the focus alarm holds the broadcast until the tick has been processed. The
 * pending result is finished in a `finally`, so a failure can never leave the broadcast hanging.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action: SessionAction = sessionActionOf(intent.action)
        if (action == SessionAction.Ignore) return
        val applicationContext: Context = context.applicationContext
        val pending = goAsync()
        scope.launch {
            try {
                handle(applicationContext, action)
            } catch (_: Throwable) {
                // A button in the shade must never crash the process that keeps the timer alive.
                // The action is lost, which is the same thing that happens when the engine ignores
                // a choice it cannot apply.
            } finally {
                pending.finish()
            }
        }
    }

    /** Dispatch one already-parsed action. */
    private suspend fun handle(context: Context, action: SessionAction) {
        val entry: SessionEntryPoint = sessionEntryPoint(context)
        val controller: GuardController = entry.guardController()
        when (action) {
            is SessionAction.ToEngine -> controller.submit(action.input)
            SessionAction.ToggleSound -> toggleSound(entry)
            SessionAction.Tick -> tick(controller)
            SessionAction.Ignore -> Unit
        }
    }

    /**
     * Start or stop the focus soundscape.
     *
     * The engine owns the sound during a session, so this is the one input that is not a [Choice]:
     * the notification's label says "Sound on" or "Sound off", and the player is already the single
     * source of the truth about what is audible.
     */
    private suspend fun toggleSound(entry: SessionEntryPoint) {
        val sound = entry.soundController()
        if (sound.playing.value != SoundKind.NONE) {
            sound.stop()
            return
        }
        val kind = soundToOffer(entry)
        if (kind != SoundKind.NONE) {
            sound.play(kind, durationMs = null)
        }
    }

    /**
     * Which soundscape "Sound on" should start.
     *
     * The session's own choice first, because a focus started with rain should come back as rain.
     * A session started in silence has none, so the preference set on the Focus screen is used, and
     * rain is the last resort: the button must do something, and rain is the app's wind-down sound.
     */
    private suspend fun soundToOffer(entry: SessionEntryPoint): SoundKind {
        val session = entry.guardController().state.value.focus?.sound
        if (session != null && session != SoundKind.NONE) return session
        val preferred = entry.settingsRepository().prefs.first().focusDefaultSound
        return if (preferred != SoundKind.NONE) preferred else SoundKind.RAIN
    }

    /**
     * Wake the engine and make sure the tick is processed.
     *
     * [GuardController.submit] only queues the input, so returning from `onReceive` immediately after
     * it could let a cold process be collected before the engine closes the session. Waiting for the
     * published state to move is bounded by [TICK_HOLD_MS], so a busy engine cannot hold the
     * broadcast past the platform's limit.
     */
    private suspend fun tick(controller: GuardController) {
        val before: Long = controller.state.value.lastSeenElapsed
        controller.submit(GuardInput.Tick)
        withTimeoutOrNull(TICK_HOLD_MS) {
            controller.state.first { it.lastSeenElapsed != before }
        }
    }

    private companion object {

        /** A broadcast receiver object is thrown away after `onReceive`; this outlives it. */
        val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /** Long enough for one engine step on a cold start, short enough for a broadcast's budget. */
        const val TICK_HOLD_MS: Long = 2_000L
    }
}
