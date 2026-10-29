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
import com.ramish.sadiq.core.engine.GuardInput
import com.ramish.sadiq.core.engine.GuardInput.ForegroundChanged
import com.ramish.sadiq.guard.GuardServiceHandle
import com.ramish.sadiq.contract.InstalledAppsSource
import com.ramish.sadiq.contract.PermissionStatusProvider
import com.ramish.sadiq.system.systemEntryPoint
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    @Inject lateinit var permissionStatusProvider: PermissionStatusProvider
    @Inject lateinit var appCatalog: AppCatalog

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val screenStateReceiver = ScreenStateReceiver()

    private var lastForegroundChanged: ForegroundChanged? = null
    private lateinit var ownPackage: String
    /**
     * Packages Sadiq must never interrupt. Seeded with our own package so the first event that
     * arrives before the IO resolution cannot touch an uninitialised field (it crashed the service).
     */
    @Volatile
    private var neverGuardPackages: Set<String> = emptySet()

    override fun onCreate() {
        super.onCreate()
        ownPackage = packageName
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "GuardAccessibilityService connected")
        guardServiceHandle.attach(this)

        // Configure service info for content events if inspectors are present
        serviceInfo?.let { info ->
            inspectorDispatcher.configureServiceInfo(info)
            this.serviceInfo = info
        }

        // Register ScreenStateReceiver
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenStateReceiver, screenFilter)

        // Start SessionService if keep-alive is on
        scope.launch {
            val prefs = settingsRepository.prefs.first()
            if (prefs.keepAlive) {
                startSessionService(applicationContext)
            }
        }

        // Resolve neverGuardPackages
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
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val eventPackageName = event.packageName?.toString()

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            // Refresh window list to get the latest state
            val currentWindows = windows

            val newForeground = ForegroundResolver.resolve(currentWindows, ownPackage, neverGuardPackages)

            if (newForeground != null && newForeground != lastForegroundChanged) {
                Log.d(TAG, "Foreground changed: $newForeground")
                guardController.submit(newForeground)
                lastForegroundChanged = newForeground
            }
        } else if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (eventPackageName != null && eventPackageName != ownPackage && eventPackageName !in neverGuardPackages) {
                inspectorDispatcher.dispatch(event, eventPackageName)
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "GuardAccessibilityService interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "GuardAccessibilityService unbound")
        guardServiceHandle.detach()
        unregisterReceiver(screenStateReceiver)
        lastForegroundChanged = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Log.i(TAG, "GuardAccessibilityService destroyed")
        guardServiceHandle.detach()
        unregisterReceiver(screenStateReceiver)
        lastForegroundChanged = null
        super.onDestroy()
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
