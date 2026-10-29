package com.ramish.sadiq.guard.service

import com.ramish.sadiq.core.engine.AppSurface
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.accessibilityservice.AccessibilityServiceInfo
import com.ramish.sadiq.contract.ContentInspector
import com.ramish.sadiq.contract.GuardController
import com.ramish.sadiq.core.engine.GuardInput.ForegroundChanged
import com.ramish.sadiq.core.time.Clock
import com.ramish.sadiq.di.GuardWork
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Dispatches accessibility content change events to a set of [ContentInspector]s, with throttling.
 *
 * It is a singleton because it holds the throttling state. It lives in the guard.service package
 * because it is driven by the accessibility service.
 */
@Singleton
class InspectorDispatcher @Inject constructor(
    private val inspectors: Set<@JvmSuppressWildcards ContentInspector>,
    private val guardController: GuardController,
    private val clock: Clock,
    @GuardWork private val scope: CoroutineScope,
) {

    private val lastDispatch = ConcurrentHashMap<String, Long>()
    private val pendingJobs = ConcurrentHashMap<String, Job>()

    /**
     * Adds `TYPE_WINDOW_CONTENT_CHANGED` to the service info if any inspectors are present.
     *
     * Called from `onServiceConnected`.
     * @param info the service info to modify.
     */
    fun configureServiceInfo(info: AccessibilityServiceInfo) {
        if (inspectors.isNotEmpty()) {
            info.eventTypes = info.eventTypes or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        }
    }

    /**
     * Dispatches a content change event to the relevant inspectors, with throttling.
     *
     * If multiple inspectors are interested in the same package, they all run, and their results are
     * merged into one [ForegroundChanged] event, with the domain of the first non-null result.
     *
     * @param event the event to dispatch.
     * @param packageName the package that the event belongs to.
     */
    fun dispatch(event: AccessibilityEvent, packageName: String) {
        if (inspectors.isEmpty()) return
        if (packageName.isBlank()) return

        val interestedInspectors = inspectors.filter { it.packages.contains(packageName) }
        if (interestedInspectors.isEmpty()) return

        val now = clock.now().wallMs
        val last = lastDispatch[packageName] ?: 0L

        if (now - last < THROTTLE_MS) {
            // Throttle: schedule a dispatch for later if no job is pending.
            if (pendingJobs.putIfAbsent(packageName, scope.launch {
                    delay(THROTTLE_MS - (now - last))
                    pendingJobs.remove(packageName)
                    doDispatch(event, packageName, interestedInspectors)
                }) == null) {
                Log.d(TAG, "Throttling content event for $packageName, scheduling for later")
            }
            return
        }

        // No throttling, dispatch immediately.
        doDispatch(event, packageName, interestedInspectors)
    }

    private fun doDispatch(
        event: AccessibilityEvent,
        packageName: String,
        interestedInspectors: List<ContentInspector>,
    ) {
        lastDispatch[packageName] = clock.now().wallMs
        val rootNode = event.source ?: return

        var domain: String? = null
        var appSurface = AppSurface.APP // Assuming AppSurface.APP

        for (inspector in interestedInspectors) {
            val result = inspector.inspect(rootNode, packageName)
            if (result != null) {
                if (domain == null) domain = result.domain
                appSurface = result.surface
                // For now, we only take the surface from the first result if it's not the default.
                // If multiple inspectors return non-default surfaces, the first one wins.
                if (appSurface != AppSurface.APP) break
            }
        }

        guardController.submit(ForegroundChanged(packageName, domain, appSurface))
    }

    companion object {
        private const val TAG = "SP/InspectorDispatcher"
        private const val THROTTLE_MS = 1000L
    }
}
