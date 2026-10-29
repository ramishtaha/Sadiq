package com.ramish.sadiq.session

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.ramish.sadiq.AppInitializer
import com.ramish.sadiq.contract.GuardController
import com.ramish.sadiq.contract.SettingsRepository
import com.ramish.sadiq.contract.SoundController
import com.ramish.sadiq.core.sound.SoundKind
import com.ramish.sadiq.di.GuardWork
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps `SessionService` matching [FgsPolicy] for the whole life of the process.
 *
 * The service is started and stopped from exactly one place. `BootReceiver` covers the one case this
 * class cannot — a reboot, when nothing of Sadiq is running — and after that every change of engine
 * state, every sound and every toggle of the keep-alive preference is decided here.
 *
 * It is an [AppInitializer] rather than a plain class because it has to run at process start: the
 * accessibility service can be rebound with the app's UI never opened, and a grant saved before a
 * HyperOS kill must be counting again the moment the process comes back. Registering it through the
 * `Set<AppInitializer>` seam is also what keeps `SadiqApp` from knowing the session exists.
 *
 * Starting a foreground service from the background is restricted on Android 12+. Two exemptions
 * apply in practice — the app is in the foreground (the usual case: the user is looking at Sadiq) and
 * the app has a bound accessibility service (the keep-alive case) — and when neither holds the start
 * is refused, logged, and retried on the next change. Interception is unaffected either way.
 */
@Singleton
class SessionServiceStarter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val controller: GuardController,
    private val settings: SettingsRepository,
    private val sound: SoundController,
    @GuardWork private val scope: CoroutineScope
) : AppInitializer {

    /** Whether the service was last asked to run, so a repeated need costs no binder call. */
    private var running: Boolean = false

    private val started: AtomicBoolean = AtomicBoolean(false)

    override fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            combine(
                controller.state,
                settings.prefs.map { it.keepAlive }.distinctUntilChanged(),
                sound.playing
            ) { state, keepAlive, playing ->
                FgsPolicy.required(state, keepAlive, playing != SoundKind.NONE)
            }
                .distinctUntilChanged()
                .collect { need -> apply(need) }
        }
    }

    /**
     * Start or stop the service, on a change of [FgsNeed] only.
     *
     * @param need the policy's answer for the latest state, preference and sound.
     */
    private fun apply(need: FgsNeed) {
        if (need.run == running) return
        running = need.run
        val intent = Intent(context, SessionService::class.java)
        if (need.run) start(intent) else context.stopService(intent)
    }

    /**
     * Ask for the service to be started in the foreground.
     *
     * `running` is rolled back when the platform refuses, so the next state change tries again: a
     * refusal at boot (before HyperOS has bound the accessibility service) must not disable the
     * keep-alive for the rest of the process.
     */
    private fun start(intent: Intent) {
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (refused: RuntimeException) {
            running = false
            Log.w(TAG, "session service not started (${refused.javaClass.simpleName}): ${refused.message}")
        }
    }

    private companion object {
        const val TAG = "SP/Session"
    }
}
