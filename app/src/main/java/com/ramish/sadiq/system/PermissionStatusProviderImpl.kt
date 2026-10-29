package com.ramish.sadiq.system

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.view.accessibility.AccessibilityManager
import androidx.core.content.ContextCompat
import com.ramish.sadiq.contract.PermissionSnapshot
import com.ramish.sadiq.contract.PermissionStatusProvider
import com.ramish.sadiq.contract.Tri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The live binding state of the accessibility service, reported by the component that knows it.
 *
 * CONTRACT-GAP: `PermissionSnapshot.accessibilityConnected` asks whether `GuardAccessibilityService`
 * is bound right now, and only the service itself can answer that. `GuardServiceHandle` (WP-13)
 * owns the service's own view of it, but WP-12 builds before WP-13 exists, so this holder is the
 * seam between them:
 *
 * - WP-14 calls [setConnected] from `onServiceConnected` (true) and `onUnbind`/`onDestroy` (false);
 * - WP-13 may delegate `GuardServiceHandle.connected` to [connected] so both names agree;
 * - [PermissionStatusProviderImpl] reads it, and [SystemEntryPoint] hands it to non-Hilt components.
 *
 * Until the first report, the provider believes this class only inside the process's startup grace
 * window, and calls it not connected after that: `onServiceConnected` arrives within a second or two
 * of a cold start, so a report that has not arrived after ten seconds means a service that is not
 * there — the state HyperOS leaves behind when it kills the process (A4-14). [AccessibilityConnection]
 * holds that rule; this class only carries the two facts it needs.
 */
@Singleton
class AccessibilityBinding @Inject constructor() {

    private val mutableConnected: MutableStateFlow<Boolean> = MutableStateFlow(false)

    private val listeners: CopyOnWriteArrayList<() -> Unit> = CopyOnWriteArrayList()

    private var everReported: Boolean = false

    /** The service's own answer, once it has given one. */
    val connected: StateFlow<Boolean> = mutableConnected.asStateFlow()

    /** Whether the service has reported at least once since this process started. */
    val reported: Boolean
        get() = synchronized(this) { everReported }

    /**
     * Records a connect or disconnect.
     *
     * Only a change (or the very first report) wakes the listeners; the service reports on every
     * bind, and re-reading four permissions on each is pointless work on the main thread.
     *
     * @param value true when the service is bound, false when it is going away.
     */
    fun setConnected(value: Boolean) {
        val notify = synchronized(this) {
            val first = !everReported
            val changed = mutableConnected.value != value
            everReported = true
            first || changed
        }
        mutableConnected.value = value
        if (notify) {
            listeners.forEach { listener -> runCatching { listener() } }
        }
    }

    /**
     * Registers a callback for [setConnected] changes.
     *
     * The listener is held for the life of the process; the only caller is the singleton
     * [PermissionStatusProviderImpl], so there is nothing to unregister.
     */
    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }
}

/**
 * Every permission Sadiq depends on, read from the platform and never assumed.
 *
 * The snapshot is deliberately pessimistic: an answer Sadiq cannot read is [Tri.UNKNOWN], not
 * `false` and not `true`, and a battery-optimisation or notification query that throws is reported
 * as "not granted" only where the platform itself would refuse to work. The wizard uses the same
 * values to decide whether a step is done, so a guess here would either strand the user on a step or
 * let him past one that will break interception later.
 *
 * **Freshness.** [refresh] is the contract's only write path, and the wizard and the Sky screen call
 * it on resume. [snapshot] additionally re-reads when its last answer is more than a second old, so
 * a screen that forgot to refresh still cannot show yesterday's grants. Both paths are guarded by
 * one lock: the reads are a handful of binder calls and two of them running at once would only
 * duplicate work.
 *
 * **What is not here.** The self-reported HyperOS steps (`autostart_confirmed`,
 * `battery_saver_confirmed`, `recents_locked_confirmed`) live in `UiPrefs`, not in the snapshot:
 * they are the user's word, and merging them is the wizard's decision, not a permission read.
 */
@Singleton
class PermissionStatusProviderImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val miuiAppOps: MiuiAppOps,
    private val accessibilityBinding: AccessibilityBinding,
) : PermissionStatusProvider {

    /**
     * When this process started, on the elapsed clock, which bounds the startup grace window.
     *
     * Declared before the first snapshot below on purpose: property initialisers run in order, so a
     * snapshot taken in the constructor would otherwise read an uninitialised zero.
     *
     * `Process.getStartElapsedRealtime()` answers zero when the platform cannot date the process.
     * Zero is passed through rather than papered over here — [AccessibilityConnection] reads it as a
     * process that has just been born, which keeps the window instead of failing closed on a
     * service that is very likely running.
     */
    private val processStartElapsedMs: Long =
        runCatching { Process.getStartElapsedRealtime() }.getOrDefault(0L)

    private val mutableChanges: MutableStateFlow<PermissionSnapshot> = MutableStateFlow(read())

    override val changes: StateFlow<PermissionSnapshot> = mutableChanges.asStateFlow()

    /** When [mutableChanges] was last filled, on the elapsed clock so a wall-clock change is irrelevant. */
    private var lastReadElapsedMs: Long = SystemClock.elapsedRealtime()

    /** The main looper, for the one re-read that ends the startup grace window. */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Guards the single post below: a read must not schedule a second one. */
    private val graceClosePosted = AtomicBoolean(false)

    init {
        // Two ways the answer can change without the app asking: the service binds or unbinds, and
        // the user toggles the service in Settings while Sadiq is in the background.
        accessibilityBinding.addListener { refresh() }
        context.getSystemService(AccessibilityManager::class.java)
            ?.addAccessibilityStateChangeListener { refresh() }
        closeGraceWindow()
    }

    override fun snapshot(): PermissionSnapshot {
        if (SystemClock.elapsedRealtime() - lastReadElapsedMs > FRESH_MS) refresh()
        return mutableChanges.value
    }

    @Synchronized
    override fun refresh() {
        lastReadElapsedMs = SystemClock.elapsedRealtime()
        mutableChanges.value = read()
    }

    /**
     * Re-reads once when the startup grace window closes.
     *
     * [AccessibilityConnection] is a function of the clock, but nothing emits when the clock passes
     * its boundary. Without this post, a surface that only collects [changes] and never calls
     * [refresh] — the Sky and the home card both do — would hold the cold-start answer until the
     * next bind or the next resume, which is the same "Sadiq is fine" that A4-14 is about. The post
     * is one-shot and only made while the service has still said nothing; the binding's own listener
     * covers every later change.
     */
    private fun closeGraceWindow() {
        if (accessibilityBinding.reported) return
        val delay = AccessibilityConnection.graceRemainingMs(
            processStartElapsedMs = processStartElapsedMs,
            nowElapsedMs = SystemClock.elapsedRealtime(),
        )
        if (delay <= 0L) return
        if (!graceClosePosted.compareAndSet(false, true)) return
        mainHandler.postDelayed({ refresh() }, delay)
    }

    /** Reads every permission once. Never throws: each platform query has its own honest fallback. */
    private fun read(): PermissionSnapshot {
        val xiaomi = HyperOs.isXiaomiFamily(Build.MANUFACTURER, Build.BRAND)
        val accessibilityEnabled = accessibilityEnabled()
        return PermissionSnapshot(
            accessibilityEnabled = accessibilityEnabled,
            accessibilityConnected = accessibilityEnabled && accessibilityConnected(),
            notifications = notificationsGranted(),
            usageAccess = SystemPermissions.usageAccess(context),
            batteryUnrestricted = batteryUnrestricted(),
            isXiaomiFamily = xiaomi,
            hyperOsMajor = HyperOs.major(Build.MANUFACTURER, Build.BRAND, Build.VERSION.INCREMENTAL),
            backgroundPopups = if (xiaomi) miuiAppOps.backgroundPopups() else Tri.UNKNOWN,
            autostart = if (xiaomi) miuiAppOps.autostart() else Tri.UNKNOWN,
            liveUpdates = liveUpdates(),
            sdkInt = Build.VERSION.SDK_INT,
        )
    }

    /**
     * Whether the service is listed as enabled.
     *
     * `AccessibilityManager.isEnabled` alone is not enough on HyperOS: it only says that *some*
     * accessibility service is on, and the phone ships several. The enabled-service list is filtered
     * down to Sadiq's own component.
     */
    private fun accessibilityEnabled(): Boolean {
        val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
        if (!manager.isEnabled) return false
        return manager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { info -> isOurService(info.resolveInfo?.serviceInfo) }
    }

    /** Matches Sadiq's service by package and class, tolerating a manifest-relative class name. */
    private fun isOurService(service: ServiceInfo?): Boolean {
        if (service == null || service.packageName != context.packageName) return false
        val name: String = service.name ?: return false
        return name == IntentCandidates.ACCESSIBILITY_SERVICE_CLASS ||
            name.endsWith(".guard.service.GuardAccessibilityService")
    }

    /**
     * Whether the bound state is known and true.
     *
     * The service is the only component that can answer, and it answers through
     * [AccessibilityBinding]. Before it has spoken, the answer is bounded by the age of this process
     * rather than assumed: [AccessibilityConnection] documents why a few seconds of belief is the
     * honest default and why that belief has to expire (A4-14).
     */
    private fun accessibilityConnected(): Boolean = AccessibilityConnection.isConnected(
        reported = accessibilityBinding.reported,
        connected = accessibilityBinding.connected.value,
        processStartElapsedMs = processStartElapsedMs,
        nowElapsedMs = SystemClock.elapsedRealtime(),
    )

    /**
     * `POST_NOTIFICATIONS`, which does not exist before API 33.
     *
     * Below 33 the permission is implicit and notifications are allowed until the user turns them
     * off app-wide, which is a state the notification code handles by itself.
     */
    private fun notificationsGranted(): Boolean =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            true
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        }

    /** Whether HyperOS would leave the process alone, which is the only battery answer that matters. */
    private fun batteryUnrestricted(): Boolean = runCatching {
        context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true
    }.getOrDefault(false)

    /**
     * Whether a promoted ongoing notification is possible right now (API 36+).
     *
     * Below 36 the feature does not exist, so the answer is a definite [Tri.NO] rather than
     * unknown. On 36+ it needs the normal `POST_PROMOTED_NOTIFICATIONS` permission *and* the user's
     * toggle; a query that throws is [Tri.UNKNOWN].
     */
    private fun liveUpdates(): Tri {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return Tri.NO
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_PROMOTED_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return Tri.NO
        return try {
            val manager = context.getSystemService(NotificationManager::class.java)
                ?: return Tri.UNKNOWN
            if (manager.canPostPromotedNotifications()) Tri.YES else Tri.NO
        } catch (_: Throwable) {
            Tri.UNKNOWN
        }
    }

    private companion object {
        /** How long a snapshot is trusted before [snapshot] re-reads it. */
        const val FRESH_MS: Long = 1_000L
    }
}

/**
 * The platform permission reads that more than one reader needs, kept in one place so the snapshot
 * and the usage source can never disagree about whether usage access is granted.
 */
internal object SystemPermissions {

    /**
     * `PACKAGE_USAGE_STATS`, which is an app-op rather than a runtime permission.
     *
     * Only an explicit "allowed" counts. The default mode means the user has never granted it, and a
     * throw (a ROM hiding the op, a missing service) means it is not usable — in both cases the
     * honest answer is false, because every read that follows would return nothing.
     *
     * @param context any context; the check is for Sadiq's own uid.
     */
    fun usageAccess(context: Context): Boolean {
        val manager = context.getSystemService(AppOpsManager::class.java) ?: return false
        return try {
            manager.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            ) == AppOpsManager.MODE_ALLOWED
        } catch (_: Throwable) {
            false
        }
    }
}
