package com.ramish.sadiq.session

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.ramish.sadiq.contract.GuardController
import com.ramish.sadiq.contract.LiveNotifier
import com.ramish.sadiq.contract.NotificationContract
import com.ramish.sadiq.contract.SettingsRepository
import com.ramish.sadiq.contract.SoundController
import com.ramish.sadiq.core.engine.Accounting
import com.ramish.sadiq.core.engine.EngineState
import com.ramish.sadiq.core.engine.LiveState
import com.ramish.sadiq.core.engine.Phase
import com.ramish.sadiq.core.sound.SoundKind
import com.ramish.sadiq.core.time.Clock
import com.ramish.sadiq.notify.NotificationFactory
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The foreground service that keeps Sadiq's timers, sound and notification alive (§3.5).
 *
 * It owns notification id 1 — the single ongoing notification — and it is the only component that
 * tells the system Sadiq is doing work that must not be frozen. It decides nothing: [FgsPolicy] says
 * whether to run and with which `foregroundServiceType` bits, [LiveNotifier] builds the
 * notification, and the engine owns the state. This class only keeps the system's picture of the app
 * in step with the engine's, on each of the three inputs the policy has:
 *
 * 1. every engine state change (a grant, a focus session, an idle evening);
 * 2. every change of [SoundController.playing] (the `mediaPlayback` type and the wake lock);
 * 3. every change of the user's keep-alive preference.
 *
 * **What it is not.** It never starts the engine, never intercepts anything and never posts a
 * per-open notice. Without notification permission it still runs: the countdown is invisible but the
 * timers and the sound are not, which is the documented degradation.
 *
 * **Restarts.** `START_STICKY`, because the whole point is that HyperOS killing the service must not
 * end a grant: the system restarts it with a null intent, [onStartCommand] re-reads the state and
 * puts the foreground notification back.
 *
 * CONTRACT-GAP: `LiveNotifier` hands out a `Notification` object only for the keep-alive
 * (`keepAlive()`); a live notification can only be posted through `show(state, now)`, which
 * de-duplicates its updates and cannot be used as the argument of `startForeground`. The service
 * therefore builds that one notification with the same `NotificationFactory` — see [notificationFor] —
 * instead of asking the notifier for it. A `LiveNotifier.notificationFor(state, now): Notification`
 * would close the gap.
 */
@AndroidEntryPoint
class SessionService : Service() {

    @Inject
    lateinit var controller: GuardController

    @Inject
    lateinit var notifier: LiveNotifier

    @Inject
    lateinit var sound: SoundController

    @Inject
    lateinit var settings: SettingsRepository

    @Inject
    lateinit var factory: NotificationFactory

    @Inject
    lateinit var clock: Clock

    @Inject
    lateinit var alarms: FocusAlarm

    /** The service's own lifetime: everything it collects lives exactly as long as it does. */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The last keep-alive preference seen. True until the first emission, which is the documented default. */
    private var keepAliveNow: Boolean = DEFAULT_KEEP_ALIVE

    /** The last sound state seen, so an unrelated state change re-evaluates with the truth. */
    private var soundNow: SoundKind = SoundKind.NONE

    private var foregroundActive: Boolean = false

    private var foregroundTypes: Int = 0

    override fun onCreate() {
        super.onCreate()
        observe()
    }

    /**
     * Apply the policy now, then keep it applied.
     *
     * The immediate call matters: a cold start from `BootReceiver`, from the accessibility service or
     * from a `START_STICKY` restart has seconds to enter the foreground, and the collector's first
     * emission may be behind a DataStore read.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        apply(controller.state.value)
        return if (foregroundActive) Service.START_STICKY else Service.START_NOT_STICKY
    }

    /** A started service, never a bound one. */
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ── observation ─────────────────────────────────────────────────────────────────────────────

    /**
     * Follow the three inputs of [FgsPolicy] as one stream.
     *
     * `combine` rather than three collectors: the policy is a function of the three together, and a
     * single collector keeps the evaluation ordered and off any shared mutable state. Nothing is
     * missed by starting here — every flow emits its current value on subscription.
     */
    private fun observe() {
        scope.launch {
            combine(
                controller.state,
                settings.prefs.map { it.keepAlive }.distinctUntilChanged(),
                sound.playing
            ) { state, keepAlive, playing -> Triple(state, keepAlive, playing) }
                .collect { (state, keepAlive, playing) ->
                    keepAliveNow = keepAlive
                    soundNow = playing
                    apply(state)
                }
        }
    }

    /**
     * Put the system's picture in step with one engine state.
     *
     * The foreground is entered again only when the required type set changes. A call while it is
     * already correct would re-post id 1, and id 1 is shared with the running countdown: an
     * unnecessary `startForeground` is the one way this class could erase a timer it does not own.
     *
     * @param state the engine state to honour.
     */
    private fun apply(state: EngineState) {
        val need: FgsNeed = FgsPolicy.required(state, keepAliveNow, soundNow != SoundKind.NONE)
        syncAlarm(state)
        if (!need.run) {
            stop()
            return
        }
        if (foregroundActive && need.types == foregroundTypes) return
        enterForeground(state, need.types)
    }

    /**
     * Become a foreground service with [types].
     *
     * @param state the state the opening notification describes.
     * @param types the bits [FgsPolicy] asked for.
     */
    private fun enterForeground(state: EngineState, types: Int) {
        try {
            val notification: Notification = notificationFor(state)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NotificationContract.ID_SESSION, notification, types)
            } else {
                // `specialUse` only exists from API 34, so the manifest of an Android 12 or 13 build
                // cannot declare it and `startForeground` refuses a type the service does not
                // declare. Below 34 no type is required at all, so the plain call is both legal and
                // the only thing those platforms understand.
                startForeground(NotificationContract.ID_SESSION, notification)
            }
            foregroundActive = true
            foregroundTypes = types
        } catch (refused: RuntimeException) {
            // Android 12+ refuses a foreground start from the background
            // (ForegroundServiceStartNotAllowedException), and a ROM may refuse a type. Interception
            // is untouched — the engine lives in the process the accessibility binding keeps alive —
            // so the only thing lost is the visible timer, exactly as §3.5 documents.
            Log.w(TAG, "foreground start refused (${refused.javaClass.simpleName}): ${refused.message}")
            foregroundActive = false
            foregroundTypes = 0
            stopSelf()
        }
    }

    /** Leave the foreground and finish. Stopping the service is what removes the notification. */
    private fun stop() {
        val wasForeground: Boolean = foregroundActive
        foregroundActive = false
        foregroundTypes = 0
        if (wasForeground) stopForeground(Service.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Keep the focus-end backup in step.
     *
     * The alarm is the screen-off fallback for a session that outlives the process (§3.6); it is
     * armed once per session by [FocusAlarm] itself and dropped as soon as the session is gone.
     */
    private fun syncAlarm(state: EngineState) {
        val focus = state.focus
        if (focus == null) {
            alarms.cancel()
        } else {
            alarms.schedule(focus)
        }
    }

    /**
     * The notification that opens the foreground.
     *
     * With nothing running this is [LiveNotifier.keepAlive]. With a grant or a focus session it is the
     * same notification [LiveNotifier] would post — built through the same factory — because opening
     * the foreground with the keep-alive text would replace a countdown the user is watching with
     * "Sadiq is on" until the next engine event, which can be twenty-five minutes away.
     */
    private fun notificationFor(state: EngineState): Notification {
        val live: LiveState = liveStateFor(state)
        return if (live == LiveState.None) notifier.keepAlive() else factory.live(live, clock.now())
    }

    private companion object {

        /** `keep_alive` defaults to true (§3.4), so a cold start is a foreground start. */
        const val DEFAULT_KEEP_ALIVE: Boolean = true

        const val TAG = "SP/Session"
    }
}

/**
 * What the running grant or focus session looks like to a notification.
 *
 * This mirrors the `Live` effect the engine emits alongside the transition, because the service needs
 * a [Notification] object rather than a request to post one, and [LiveNotifier.show] is deliberately
 * de-duplicated. It only ever runs at the moment the foreground is entered; the engine's own `Live`
 * effect owns every later update.
 *
 * It is pure and lives outside the service so the whole reading can be tested without a device: a
 * mistake here would either name the wrong app in the status bar or turn a countdown into "saved for
 * later".
 *
 * @param state the engine state the notification describes.
 * @return the running state, or [LiveState.None] when there is nothing to count down.
 */
internal fun liveStateFor(state: EngineState): LiveState {
    val focus = state.focus
    if (focus != null) {
        return LiveState.FocusRunning(
            plannedMs = focus.plannedMs,
            remainingMs = (focus.plannedMs - focus.elapsedMs).coerceAtLeast(0L),
            sound = focus.sound
        )
    }
    val grant = state.grant ?: return LiveState.None
    val remainingMs: Long = Accounting.grantRemainingMs(grant)
    // A saved grant is explicit; a prompt over a paused grant is still "saved for later".
    val saved: Boolean = state.phase == Phase.PAUSED || grant.pausedSinceElapsed != null
    return when {
        state.phase == Phase.GRANTED -> LiveState.GrantRunning(
            packageName = state.foregroundPackage ?: grant.firstPackage,
            kind = grant.kind,
            grantedMs = grant.grantedMs,
            remainingMs = remainingMs
        )
        saved -> LiveState.GrantHeld(remainingMs = remainingMs, explicitPause = true)
        else -> LiveState.GrantHeld(remainingMs = remainingMs, explicitPause = false)
    }
}
