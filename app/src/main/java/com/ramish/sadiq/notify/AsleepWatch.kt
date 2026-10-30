package com.ramish.sadiq.notify

import com.ramish.sadiq.AppInitializer
import com.ramish.sadiq.contract.LiveNotifier
import com.ramish.sadiq.contract.PermissionStatusProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/**
 * The once-a-day note for a Sadiq whose switch is on while nothing is listening (A5-07).
 *
 * This is the process-start watcher, and it exists because the fault it reports is the one that
 * happens *while Sadiq is not on screen*: HyperOS kills the process overnight, or an update unbinds
 * the accessibility service, and the switch still reads "on". A screen that noticed would only
 * notice when he opened that screen; the honest moment is the first one at which Sadiq is running
 * again and can see the service is not.
 *
 * Three things keep it from becoming noise:
 * - [PermissionStatusProvider] handles the cold-start grace window, so a service that is merely a
 *   second late to bind is not reported as dead ([AccessibilityConnection] expires that belief).
 * - [LiveNotifier.asleep] re-reads the snapshot at the moment of posting and drops the note if the
 *   fault is gone, so a transient state cannot produce a false sentence.
 * - the notifier also owns the 24 h budget (`last_attention_notif_at`), so this collector may fire as
 *   often as the snapshot changes without posting twice in a day.
 */
@Singleton
class AsleepWatch @Inject constructor(
    private val permissionStatusProvider: PermissionStatusProvider,
    private val notifier: LiveNotifier,
) : AppInitializer {

    /** Process-scoped on purpose: the note must outlive any one screen, and this object is the app. */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun start() {
        scope.launch {
            permissionStatusProvider.changes
                // A startup initialiser that threw would take the process with it (SadiqApp starts
                // them without a guard). A watcher that cannot read permissions is a watcher that
                // posts nothing — never a crash loop.
                .catch { }
                .collect { snapshot ->
                    // "On but not bound" only. A service that is off has its own sentence, and the
                    // Sky's asleep card is the surface for it; this note is about the fault nobody sees.
                    if (snapshot.accessibilityEnabled && !snapshot.accessibilityConnected) {
                        notifier.asleep()
                    }
                }
        }
    }
}
