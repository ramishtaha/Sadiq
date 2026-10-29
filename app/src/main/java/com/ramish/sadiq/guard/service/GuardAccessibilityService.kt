package com.ramish.sadiq.guard.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import com.ramish.sadiq.contract.GuardController
import com.ramish.sadiq.contract.SettingsRepository
import com.ramish.sadiq.core.apps.AppCatalog
import com.ramish.sadiq.guard.GuardServiceHandle
import com.ramish.sadiq.contract.InstalledAppsSource
import com.ramish.sadiq.contract.PermissionStatusProvider
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class GuardAccessibilityService : AccessibilityService() {

    @Inject lateinit var guardServiceHandle: GuardServiceHandle
    @Inject lateinit var inspectorDispatcher: InspectorDispatcher
    @Inject lateinit var guardController: GuardController
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var installedAppsSource: InstalledAppsSource

    @Inject lateinit var accessibilityBinding: com.ramish.sadiq.system.AccessibilityBinding
    @Inject lateinit var permissionStatusProvider: PermissionStatusProvider
    @Inject lateinit var appCatalog: AppCatalog

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * The de-duplication between window events and the engine. AUDIT-2 G-01: it is the engine's only
     * protection against submitting the same package twice, so it must be forgotten whenever the
     * foreground genuinely stopped being that app — Sadiq's own window in front, screen off, and a
     * fresh connection are the three places that happens. See [ForegroundGate].
     */
    private val foregroundGate = ForegroundGate()

    private val screenStateReceiver = ScreenStateReceiver(
        onScreenOff = { foregroundGate.reset() },
        onScreenOn = { submitCurrentForeground() },
    )

    private lateinit var ownPackage: String

    /**
     * Packages Sadiq must never interrupt. Used to keep content events from being inspected for
     * them; it is *not* what the resolver filters on (that is [transparentPackages], G-01).
     */
    @Volatile
    private var neverGuardPackages: Set<String> = emptySet()

    /**
     * Packages the resolver looks straight through: Sadiq, the enabled keyboards and the permission
     * dialogs. Empty until the IO resolution below finishes, which is safe because the resolver
     * always compares against [ownPackage] as well.
     */
    @Volatile
    private var transparentPackages: Set<String> = emptySet()

    /** AUDIT-2 G-06: `onUnbind` then `onDestroy` both run on disable; the receiver goes once. */
    private var receiverRegistered = false

    override fun onCreate() {
        super.onCreate()
        ownPackage = packageName
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "GuardAccessibilityService connected")
        guardServiceHandle.attach(this)
        accessibilityBinding.setConnected(true)

        // A fresh connection has no memory of what was in front (G-01), so the first window event
        // always reaches the engine.
        foregroundGate.reset()

        // Configure service info for content events if inspectors are present
        serviceInfo?.let { info ->
            inspectorDispatcher.configureServiceInfo(info)
            this.serviceInfo = info
        }

        // Register ScreenStateReceiver
        if (!receiverRegistered) {
            val screenFilter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            runCatching { registerReceiver(screenStateReceiver, screenFilter) }
                .onSuccess { receiverRegistered = true }
                .onFailure { Log.e(TAG, "Could not register ScreenStateReceiver", it) }
        }

        // Start SessionService if keep-alive is on
        scope.launch {
            val prefs = settingsRepository.prefs.first()
            if (prefs.keepAlive) {
                startSessionService(applicationContext)
            }
        }

        // Resolve the never-guard set (for inspectors) and the transparent set (for the resolver)
        scope.launch(Dispatchers.IO) {
            val defaultHomePackage = installedAppsSource.launchableApps()
                .firstOrNull { it.packageName == packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName }?.packageName
            val enabledImePackages = (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .enabledInputMethodList.map { it.packageName }.toSet()

            neverGuardPackages = appCatalog.neverGuard(
                ownPackage = ownPackage,
                defaultHomePackage = defaultHomePackage,
                enabledImePackages = enabledImePackages
            )
            transparentPackages = ForegroundResolver.transparentPackages(
                ownPackage = ownPackage,
                enabledImePackages = enabledImePackages
            )
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val eventPackageName = event.packageName?.toString()

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> submitCurrentForeground()

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (eventPackageName != null && eventPackageName != ownPackage && eventPackageName !in neverGuardPackages) {
                    inspectorDispatcher.dispatch(event, eventPackageName)
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "GuardAccessibilityService interrupted")
    }

    /**
     * Reads the window stack and submits the foreground, unless it repeats the last submission.
     *
     * A read that finds Sadiq's own window in front is not a decision, but it does end the run of
     * identical foregrounds: after the question closes, the app underneath must be submitted again
     * even though its package never changed (AUDIT-2 G-01, the Recents and second-open cases).
     */
    private fun submitCurrentForeground() {
        val stack = windows ?: return
        val input = foregroundGate.submitFor(
            ForegroundResolver.resolve(stack, ownPackage, transparentPackages),
        ) ?: return
        Log.d(TAG, "Foreground changed: $input")
        guardController.submit(input)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "GuardAccessibilityService unbound")
        accessibilityBinding.setConnected(false)
        guardServiceHandle.detach()
        unregisterScreenStateReceiver()
        foregroundGate.reset()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Log.i(TAG, "GuardAccessibilityService destroyed")
        guardServiceHandle.detach()
        unregisterScreenStateReceiver()
        foregroundGate.reset()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Unregisters the screen receiver exactly once.
     *
     * Disabling the service runs `onUnbind` and then `onDestroy`, and the second
     * `unregisterReceiver` used to throw `IllegalArgumentException` and take the process down with
     * it (AUDIT-2 G-06). The flag makes the pair idempotent; `runCatching` covers a receiver the
     * system has already dropped.
     */
    private fun unregisterScreenStateReceiver() {
        if (!receiverRegistered) return
        receiverRegistered = false
        runCatching { unregisterReceiver(screenStateReceiver) }
            .onFailure { Log.w(TAG, "ScreenStateReceiver was already unregistered", it) }
    }

    private fun startSessionService(context: Context) {
        val present = runCatching {
            Class.forName(SESSION_SERVICE_CLASS, false, context.classLoader)
        }.isSuccess
        if (!present) return

        val intent = Intent().setClassName(context.packageName, SESSION_SERVICE_CLASS)
        try {
            startForegroundService(intent)
        } catch (_: Exception) {
            Log.e(TAG, "Failed to start SessionService")
        }
    }

    companion object {
        private const val TAG = "SP/GuardAccessibilityService"
        const val SESSION_SERVICE_CLASS: String = "com.ramish.sadiq.session.SessionService"
    }
}
