package com.ramish.sadiq.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Brings Sadiq's keep-alive back after a reboot or an app update.
 *
 * This is the one component the OS starts when nothing of Sadiq is running: no activity, no
 * service, no Hilt-injected field. It therefore reaches the graph through [SystemEntryPoint] rather
 * than `@AndroidEntryPoint`, reads the one preference it needs, and starts `SessionService` **by
 * class name** — a string, so this file does not have to depend on WP-16's source.
 *
 * **What it does not do.** It does not start interception: accessibility is rebound by the system on
 * its own, and the process that hosts it is the one the service keeps alive. It also does not insist:
 * if keep-alive is off, if HyperOS refused the foreground start, or if this build does not carry the
 * service yet, the receiver finishes quietly. The user still gets interception as soon as
 * accessibility rebinds (§3.5), and the only thing lost is the silent notification.
 *
 * **HyperOS Autostart.** If Autostart is off, this receiver is never called at all — the OEM drops
 * the broadcast. That is exactly why O10 exists, and why the Health check reports the op rather than
 * assuming this code ran.
 *
 * `goAsync` is used because reading DataStore is suspending and `onReceive` must return quickly; the
 * pending result is finished in a `finally`, so a failed read cannot leave the broadcast hanging.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action: String = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val applicationContext: Context = context.applicationContext
        val pending = goAsync()
        scope.launch {
            try {
                restore(applicationContext)
            } catch (_: Throwable) {
                // A reboot must never turn into a crash loop: whatever failed here is retried when
                // the accessibility service rebinds.
            } finally {
                pending.finish()
            }
        }
    }

    /** Starts the keep-alive service when the user asked for it. */
    private suspend fun restore(context: Context) {
        val prefs = systemEntryPoint(context).settingsRepository().prefs.first()
        if (!prefs.keepAlive) return
        startSessionService(context)
    }

    /**
     * Starts `SessionService` by class name.
     *
     * `setClassName` rather than a class literal keeps WP-12 independent of WP-16. The existence
     * check keeps an intermediate build (or a stripped variant) from crashing at boot for a service
     * that is not there; it does not initialise the class.
     */
    private fun startSessionService(context: Context) {
        val present = runCatching {
            Class.forName(SESSION_SERVICE_CLASS, false, context.classLoader)
        }.isSuccess
        if (!present) return

        val intent = Intent().setClassName(context.packageName, SESSION_SERVICE_CLASS)
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (_: Exception) {
            // ForegroundServiceStartNotAllowedException on a restricted boot, or a ROM that refuses
            // the specialUse type. Interception is unaffected; only the notification is missing.
        }
    }

    private companion object {

        /**
         * `SessionService`'s fully qualified name, exactly as the manifest declares it.
         *
         * A string, not `SessionService::class.java`: WP-12 builds before WP-16 exists.
         */
        const val SESSION_SERVICE_CLASS: String = "com.ramish.sadiq.session.SessionService"

        /** Outlives one receiver instance; a broadcast receiver object is thrown away after `onReceive`. */
        val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
