package com.ramish.sadiq.contract

import android.app.Notification
import android.graphics.drawable.Drawable
import android.view.accessibility.AccessibilityNodeInfo
import com.ramish.sadiq.core.engine.AppSurface
import com.ramish.sadiq.core.engine.InterceptUi
import com.ramish.sadiq.core.engine.LiveState
import com.ramish.sadiq.core.engine.UsageSpan
import com.ramish.sadiq.core.insight.UsageDayRow
import com.ramish.sadiq.core.model.GuardConfig
import com.ramish.sadiq.core.sound.SoundKind
import com.ramish.sadiq.core.time.TimeSnapshot
import kotlinx.coroutines.flow.StateFlow

/**
 * Puts an interception surface on screen and takes it away again.
 *
 * The presenter owns the choice between `InterceptActivity` and the accessibility overlay, including
 * the 600 ms visibility check (§3.6). The engine only ever says "show this".
 */
interface SurfacePresenter {
    /** Show a surface. @param ui the surface to render. */
    fun show(ui: InterceptUi)

    /** Redraw the surface that is already up. @param ui the new surface. */
    fun update(ui: InterceptUi)

    /** Take the surface away. */
    fun dismiss()
}

/** Sends the user home, or brings Sadiq itself to the front. */
interface HomeAction {
    /** @return true when the home action was performed by the accessibility service. */
    fun goHome(): Boolean

    /** Bring Sadiq to the front on [route]. @param route one of `Routes`. */
    fun openSadiq(route: String)
}

/** Lowers other apps' audio while a surface is up, so the question is not competing with a reel. */
interface MediaDucker {
    /** Duck. */
    fun duck()

    /** Release the duck. Safe to call when nothing is ducked. */
    fun release()
}

/**
 * The ongoing notification: the silent keep-alive, the grant/focus timer, the Live Update.
 *
 * All construction lives behind this interface so the session service never builds a notification
 * itself and the strings stay in one place.
 */
interface LiveNotifier {
    /**
     * Update the ongoing notification.
     *
     * @param state what is running.
     * @param now the clocks, for the chronometer and the Live Update progress.
     */
    fun show(state: LiveState, now: TimeSnapshot)

    /** The minimal keep-alive notification, used when the service starts. */
    fun keepAlive(): Notification

    /** Announce a finished focus session. @param stars how many stars it lit. */
    fun focusComplete(stars: Int)

    /** The "Sadiq is asleep" attention notification, at most once per 24 h. */
    fun asleep()
}

/** The soundscape player: one sound at a time, generated offline. */
interface SoundController {
    /** What is playing; [SoundKind.NONE] when silent. */
    val playing: StateFlow<SoundKind>

    /**
     * Play a soundscape.
     *
     * @param kind which sound.
     * @param durationMs how long, or null to play until [stop].
     */
    fun play(kind: SoundKind, durationMs: Long?)

    /** Stop, fading out. */
    fun stop()

    /** Play a short preview from the Settings picker. */
    fun preview(kind: SoundKind)
}

/** A three-valued answer for permissions that HyperOS will not report honestly. */
enum class Tri {
    YES,
    NO,
    UNKNOWN
}

/**
 * One reading of every permission Sadiq depends on.
 *
 * @property accessibilityEnabled whether the service is enabled in Settings.
 * @property accessibilityConnected whether it is actually bound right now.
 * @property notifications whether `POST_NOTIFICATIONS` is granted (always true below API 33).
 * @property usageAccess whether `PACKAGE_USAGE_STATS` is granted.
 * @property batteryUnrestricted whether the app is exempt from battery optimisation.
 * @property isXiaomiFamily whether the ROM is HyperOS or MIUI.
 * @property hyperOsMajor the HyperOS major version, or null when unknown.
 * @property backgroundPopups HyperOS "display pop-up windows while running in the background".
 * @property autostart HyperOS autostart.
 * @property liveUpdates whether promoted ongoing notifications are available and enabled.
 * @property sdkInt `Build.VERSION.SDK_INT`.
 */
data class PermissionSnapshot(
    val accessibilityEnabled: Boolean,
    val accessibilityConnected: Boolean,
    val notifications: Boolean,
    val usageAccess: Boolean,
    val batteryUnrestricted: Boolean,
    val isXiaomiFamily: Boolean,
    val hyperOsMajor: Int?,
    val backgroundPopups: Tri,
    val autostart: Tri,
    val liveUpdates: Tri,
    val sdkInt: Int
)

/** The truth about permissions, refreshed on resume and on every relevant broadcast. */
interface PermissionStatusProvider {
    /** The latest reading; never blocks on a binder call from the main thread more than once. */
    fun snapshot(): PermissionSnapshot

    /** The latest reading as a flow, emitting on every [refresh]. */
    val changes: StateFlow<PermissionSnapshot>

    /** Re-read every permission. */
    fun refresh()
}

/** A settings page Sadiq can send the user to. */
enum class SettingsTarget {
    ACCESSIBILITY,
    ACCESSIBILITY_DETAILS,
    APP_INFO,
    NOTIFICATIONS,
    USAGE_ACCESS,
    BATTERY_OPT_REQUEST,
    MIUI_BATTERY_SAVER,
    MIUI_AUTOSTART,
    MIUI_OTHER_PERMISSIONS,
    LIVE_UPDATES,
    CHANNEL_GUARD_STATUS
}

/** Opens an OS settings page, with a fallback chain so a missing OEM activity never dead-ends. */
interface OemSettingsLauncher {
    /** @param target the page to open. @return true when some activity was actually started. */
    fun open(target: SettingsTarget): Boolean
}

/**
 * A launchable app.
 *
 * @property packageName the Android package name.
 * @property label the launcher label.
 * @property isSuggestedFeed whether it is one of the known short-video feeds, shown first in the picker.
 */
data class AppInfo(
    val packageName: String,
    val label: String,
    val isSuggestedFeed: Boolean
)

/** The launchable apps, for the app picker. */
interface InstalledAppsSource {
    /** Every launchable app, sorted by label. */
    suspend fun launchableApps(): List<AppInfo>

    /**
     * The app's icon, for a list row.
     *
     * @param packageName the app.
     * @return the icon, or null when the package is gone.
     */
    fun icon(packageName: String): Drawable?
}

/** Usage stats: reconciliation after the process was down, and the Week screen's numbers. */
interface UsageHistorySource {
    /** Whether usage access is granted right now. */
    fun hasAccess(): Boolean

    /**
     * Foreground spans since [fromWall].
     *
     * @param fromWall exclusive lower bound.
     * @param toWall inclusive upper bound.
     */
    suspend fun spans(fromWall: Long, toWall: Long): List<UsageSpan>

    /**
     * Aggregate usage rows for a range of days.
     *
     * @param days the days to read.
     * @param zoneId the zone that decides where a day starts.
     * @param config the settings, so guarded packages are known.
     */
    suspend fun dailyRows(days: IntRange, zoneId: String, config: GuardConfig): List<UsageDayRow>

    /**
     * Foreground milliseconds per hour of the day, for the Week screen's peak band.
     *
     * @param days the days to read.
     * @param zoneId the zone the hours are expressed in.
     * @param packages the packages to count.
     */
    suspend fun hourlyMs(days: IntRange, zoneId: String, packages: Set<String>): LongArray
}

/**
 * What an inspector found in a window's content tree.
 *
 * @property domain the matched guarded domain, if the address bar yielded one.
 * @property surface which surface of the app is in front.
 */
data class InspectResult(
    val domain: String?,
    val surface: AppSurface
)

/**
 * Reads a window's content tree for one package.
 *
 * Only the address bar of listed browsers and the known view ids of YouTube and Instagram are read;
 * text the user types is never touched and URLs are never stored (§3.9). Contributions are optional:
 * an empty `Set<ContentInspector>` disables content events entirely.
 */
interface ContentInspector {
    /** The packages this inspector is interested in. */
    val packages: Set<String>

    /**
     * Inspect the root node of the window.
     *
     * @param root the window's root node.
     * @param packageName the package in front.
     * @return what was found, or null when nothing conclusive was read.
     */
    fun inspect(root: AccessibilityNodeInfo, packageName: String): InspectResult?
}
