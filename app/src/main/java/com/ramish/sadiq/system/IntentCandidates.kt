package com.ramish.sadiq.system

import com.ramish.sadiq.contract.SettingsTarget

/**
 * One way to reach a settings page, described without Android types.
 *
 * A candidate is data, not an `Intent`, because the ordering rules of §4.7 are product logic: they
 * must be readable and testable without a device, and WP-22 verifies the same table against the
 * Poco F7. [OemSettingsLauncherImpl] turns each candidate into an `Intent` at the moment it is
 * opened, filling in the values only the running app knows (its package, its label, its uid).
 *
 * @property action the intent action, e.g. `android.settings.APPLICATION_DETAILS_SETTINGS`; null
 *   when the component alone identifies the page, which is how MIUI starts several of its own
 *   activities.
 * @property componentPackage the OEM package to target, or null to let the system resolve the
 *   action.
 * @property componentClass the activity inside [componentPackage], or null to let the system pick
 *   one.
 * @property data what to put in the intent's data URI.
 * @property extras the extras to attach, keyed by the exact extra name.
 */
data class IntentCandidate(
    val action: String? = null,
    val componentPackage: String? = null,
    val componentClass: String? = null,
    val data: IntentData = IntentData.NONE,
    val extras: Map<String, IntentExtra> = emptyMap(),
)

/** The data URI a candidate needs. */
enum class IntentData {
    /** No data URI. */
    NONE,

    /** `package:<our package>`, which is how Android addresses an app's own settings pages. */
    PACKAGE_URI,
}

/**
 * The value of one intent extra.
 *
 * The launcher resolves these; nothing here holds a real package name except [Literal], which is
 * for fixed strings such as the notification channel id.
 */
sealed interface IntentExtra {

    /** Sadiq's own package name. */
    data object PackageName : IntentExtra

    /** Sadiq's own launcher label, which `com.miui.powerkeeper` shows as the page title. */
    data object PackageLabel : IntentExtra

    /** Sadiq's own uid, which older notification-settings builds still read. */
    data object PackageUid : IntentExtra

    /** Sadiq's `AccessibilityService` component, as `EXTRA_COMPONENT_NAME`. */
    data object SelfAccessibilityComponent : IntentExtra

    /** A fixed string. @property value the string to put in the extra. */
    data class Literal(val value: String) : IntentExtra
}

/**
 * The ordered deep-link chains of §4.7, and the fallback rule that makes them safe.
 *
 * **Every chain ends on the app's own info page** (`android.settings.APPLICATION_DETAILS_SETTINGS`
 * with a `package:` URI), which exists on every Android 11+ device and from which the user can reach
 * every other page in two taps. A missing OEM activity, a renamed HyperOS menu or an activity that
 * is no longer exported therefore costs one extra tap, never a dead end.
 *
 * **The chains are static.** They include the MIUI activities on every device, not only on Xiaomi
 * phones: the onboarding wizard shows OEM steps only when `HyperOs.isXiaomiFamily`, so a Pixel never
 * calls these targets, and a static table is the thing WP-22 can verify once on the Poco F7. When a
 * MIUI activity does not resolve, the launcher simply moves to the next candidate.
 *
 * Actions are string constants rather than `Settings.ACTION_*` so this file stays a pure description
 * of the plan; each constant names the platform constant it matches.
 */
object IntentCandidates {

    // ── Actions ────────────────────────────────────────────────────────────────────────────────

    /** `Settings.ACTION_ACCESSIBILITY_SETTINGS`. */
    const val ACTION_ACCESSIBILITY_SETTINGS: String = "android.settings.ACCESSIBILITY_SETTINGS"

    /**
     * `Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS`.
     *
     * Carries the service component in `EXTRA_COMPONENT_NAME`. Some builds ignore the extra and open
     * the service list, which is still the right screen.
     */
    const val ACTION_ACCESSIBILITY_DETAILS_SETTINGS: String = "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"

    /** `Settings.ACTION_APPLICATION_DETAILS_SETTINGS`; needs a `package:` URI. */
    const val ACTION_APPLICATION_DETAILS_SETTINGS: String = "android.settings.APPLICATION_DETAILS_SETTINGS"

    /** `Settings.ACTION_APP_NOTIFICATION_SETTINGS`; needs `EXTRA_APP_PACKAGE`. */
    const val ACTION_APP_NOTIFICATION_SETTINGS: String = "android.settings.APP_NOTIFICATION_SETTINGS"

    /** `Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS`; needs `EXTRA_APP_PACKAGE` and `EXTRA_CHANNEL_ID`. */
    const val ACTION_CHANNEL_NOTIFICATION_SETTINGS: String = "android.settings.CHANNEL_NOTIFICATION_SETTINGS"

    /** `Settings.ACTION_USAGE_ACCESS_SETTINGS`. */
    const val ACTION_USAGE_ACCESS_SETTINGS: String = "android.settings.USAGE_ACCESS_SETTINGS"

    /** `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; shows the system dialog for a `package:` URI. */
    const val ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS: String =
        "android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"

    /** `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`; the list of exempt apps. */
    const val ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS: String =
        "android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS"

    /** MIUI's per-app permission editor (O7). */
    const val ACTION_MIUI_APP_PERM_EDITOR: String = "miui.intent.action.APP_PERM_EDITOR"

    // ── Extras ─────────────────────────────────────────────────────────────────────────────────

    /** `Intent.EXTRA_COMPONENT_NAME`. */
    const val EXTRA_COMPONENT_NAME: String = "android.intent.extra.COMPONENT_NAME"

    /** `Settings.EXTRA_APP_PACKAGE`. */
    const val EXTRA_APP_PACKAGE: String = "android.provider.extra.APP_PACKAGE"

    /** `Settings.EXTRA_CHANNEL_ID`. */
    const val EXTRA_CHANNEL_ID: String = "android.provider.extra.CHANNEL_ID"

    /** The extra MIUI's permission editor reads, in place of a package URI. */
    const val EXTRA_MIUI_PKG_NAME: String = "extra_pkgname"

    /** The extra `com.miui.powerkeeper` reads for the app whose battery saver page to open. */
    const val EXTRA_POWERKEEPER_PACKAGE: String = "package_name"

    /** The extra `com.miui.powerkeeper` shows as that page's title. */
    const val EXTRA_POWERKEEPER_LABEL: String = "package_label"

    /** The pre-Android-11 notification-settings extra; still read by some MIUI builds. */
    const val EXTRA_LEGACY_APP_PACKAGE: String = "app_package"

    /** The pre-Android-11 notification-settings uid extra. */
    const val EXTRA_LEGACY_APP_UID: String = "app_uid"

    // ── Components ─────────────────────────────────────────────────────────────────────────────

    /** `com.miui.securitycenter`, the app that owns the HyperOS permission pages. */
    const val MIUI_SECURITY_CENTER: String = "com.miui.securitycenter"

    /** The per-app permission editor used by HyperOS 2 and 3. */
    const val MIUI_PERMISSIONS_EDITOR: String = "com.miui.permcenter.permissions.PermissionsEditorActivity"

    /** The same editor under its older name, which some MIUI 12–14 builds still expose. */
    const val MIUI_PERMISSIONS_EDITOR_ALT: String =
        "com.miui.permcenter.permissions.AppPermissionsEditorActivity"

    /** HyperOS autostart management. */
    const val MIUI_AUTOSTART: String = "com.miui.permcenter.autostart.AutoStartManagementActivity"

    /** The Security Center's own home, the fallback when the autostart page is missing. */
    const val MIUI_SECURITY_MAIN: String = "com.miui.securitycenter.MainActivity"

    /** The Security Center's app-details page, which also shows "Autostart". */
    const val MIUI_APP_DETAILS: String = "com.miui.appmanager.ApplicationsDetailsActivity"

    /** `com.miui.powerkeeper`, which owns HyperOS battery saver. */
    const val MIUI_POWER_KEEPER: String = "com.miui.powerkeeper"

    /** HyperOS per-app battery saver, where "No restrictions" is chosen. */
    const val MIUI_HIDDEN_APPS_CONFIG: String = "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"

    /** The notification channel that carries the keep-alive line; its page is a real settings screen. */
    const val CHANNEL_GUARD_STATUS: String = "guard_status"

    /**
     * Sadiq's accessibility service, exactly as the manifest declares it.
     *
     * A string rather than a class reference: the class belongs to WP-14, and this table must not
     * depend on that work package having landed.
     */
    const val ACCESSIBILITY_SERVICE_CLASS: String = "com.ramish.sadiq.guard.service.GuardAccessibilityService"

    /** The app-info page every chain ends on. */
    private val appInfo: IntentCandidate = IntentCandidate(
        action = ACTION_APPLICATION_DETAILS_SETTINGS,
        data = IntentData.PACKAGE_URI,
    )

    /**
     * The ordered candidates for [target], best first, app info last.
     *
     * The order comes straight from §4.7: the exact page the plan names, then the nearest general
     * page that a build lacking the first still has, then app info.
     *
     * @param target the page the user asked to be taken to.
     * @return at least two candidates for every target, the last of which is an app-info page.
     */
    fun candidates(target: SettingsTarget): List<IntentCandidate> = when (target) {
        SettingsTarget.ACCESSIBILITY -> accessibilityChain()
        SettingsTarget.ACCESSIBILITY_DETAILS -> accessibilityChain()
        SettingsTarget.APP_INFO -> listOf(appInfo, miuiAppDetails())
        SettingsTarget.NOTIFICATIONS -> notificationChain()
        SettingsTarget.CHANNEL_GUARD_STATUS -> channelChain()
        SettingsTarget.USAGE_ACCESS -> usageChain()
        SettingsTarget.BATTERY_OPT_REQUEST -> batteryOptimisationChain()
        SettingsTarget.MIUI_BATTERY_SAVER -> miuiBatterySaverChain()
        SettingsTarget.MIUI_AUTOSTART -> miuiAutostartChain()
        SettingsTarget.MIUI_OTHER_PERMISSIONS -> miuiOtherPermissionsChain()
        SettingsTarget.LIVE_UPDATES -> liveUpdatesChain()
    }

    /**
     * O5: the service's own page, then the accessibility list, then app info.
     *
     * `ACTION_ACCESSIBILITY_DETAILS_SETTINGS` is the only link that lands directly on the "Use
     * Sadiq" toggle; when the Settings app ignores the component extra the user still arrives on
     * the list that contains it.
     */
    private fun accessibilityChain(): List<IntentCandidate> = listOf(
        IntentCandidate(
            action = ACTION_ACCESSIBILITY_DETAILS_SETTINGS,
            extras = mapOf(EXTRA_COMPONENT_NAME to IntentExtra.SelfAccessibilityComponent),
        ),
        IntentCandidate(action = ACTION_ACCESSIBILITY_SETTINGS),
        appInfo,
    )

    /** O6: the app's notification page, its pre-Android-11 shape, then app info. */
    private fun notificationChain(): List<IntentCandidate> = listOf(
        IntentCandidate(
            action = ACTION_APP_NOTIFICATION_SETTINGS,
            extras = mapOf(EXTRA_APP_PACKAGE to IntentExtra.PackageName),
        ),
        IntentCandidate(
            action = ACTION_APP_NOTIFICATION_SETTINGS,
            extras = mapOf(
                EXTRA_LEGACY_APP_PACKAGE to IntentExtra.PackageName,
                EXTRA_LEGACY_APP_UID to IntentExtra.PackageUid,
            ),
        ),
        appInfo,
    )

    /** The keep-alive channel's own page, then its app's notification page, then app info. */
    private fun channelChain(): List<IntentCandidate> = listOf(
        IntentCandidate(
            action = ACTION_CHANNEL_NOTIFICATION_SETTINGS,
            extras = mapOf(
                EXTRA_APP_PACKAGE to IntentExtra.PackageName,
                EXTRA_CHANNEL_ID to IntentExtra.Literal(CHANNEL_GUARD_STATUS),
            ),
        ),
        IntentCandidate(
            action = ACTION_APP_NOTIFICATION_SETTINGS,
            extras = mapOf(EXTRA_APP_PACKAGE to IntentExtra.PackageName),
        ),
        appInfo,
    )

    /** O12: usage access for Sadiq, then the usage-access list, then app info. */
    private fun usageChain(): List<IntentCandidate> = listOf(
        IntentCandidate(
            action = ACTION_USAGE_ACCESS_SETTINGS,
            data = IntentData.PACKAGE_URI,
        ),
        IntentCandidate(action = ACTION_USAGE_ACCESS_SETTINGS),
        appInfo,
    )

    /** O8: the system exemption dialog, then the exemption list, then app info. */
    private fun batteryOptimisationChain(): List<IntentCandidate> = listOf(
        IntentCandidate(
            action = ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            data = IntentData.PACKAGE_URI,
        ),
        IntentCandidate(action = ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        appInfo,
    )

    /** O9: HyperOS battery saver for Sadiq, then the system exemption list, then app info. */
    private fun miuiBatterySaverChain(): List<IntentCandidate> = listOf(
        IntentCandidate(
            componentPackage = MIUI_POWER_KEEPER,
            componentClass = MIUI_HIDDEN_APPS_CONFIG,
            extras = mapOf(
                EXTRA_POWERKEEPER_PACKAGE to IntentExtra.PackageName,
                EXTRA_POWERKEEPER_LABEL to IntentExtra.PackageLabel,
            ),
        ),
        IntentCandidate(action = ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        appInfo,
    )

    /** O10: the autostart list, then Security Center's home, then app info. */
    private fun miuiAutostartChain(): List<IntentCandidate> = listOf(
        IntentCandidate(
            componentPackage = MIUI_SECURITY_CENTER,
            componentClass = MIUI_AUTOSTART,
        ),
        IntentCandidate(
            componentPackage = MIUI_SECURITY_CENTER,
            componentClass = MIUI_SECURITY_MAIN,
        ),
        appInfo,
    )

    /** O7: the permission editor, its older activity, then app info. */
    private fun miuiOtherPermissionsChain(): List<IntentCandidate> = listOf(
        IntentCandidate(
            action = ACTION_MIUI_APP_PERM_EDITOR,
            componentPackage = MIUI_SECURITY_CENTER,
            componentClass = MIUI_PERMISSIONS_EDITOR,
            extras = mapOf(EXTRA_MIUI_PKG_NAME to IntentExtra.PackageName),
        ),
        IntentCandidate(
            action = ACTION_MIUI_APP_PERM_EDITOR,
            componentPackage = MIUI_SECURITY_CENTER,
            componentClass = MIUI_PERMISSIONS_EDITOR_ALT,
            extras = mapOf(EXTRA_MIUI_PKG_NAME to IntentExtra.PackageName),
        ),
        appInfo,
    )

    /**
     * Live Updates: the notification settings page in API 36 has a promotion section.
     *
     * There is no public intent that opens "promoted notifications" alone, so the app's notification
     * page is the honest target; the wizard explains the toggle above it.
     */
    private fun liveUpdatesChain(): List<IntentCandidate> = listOf(
        IntentCandidate(
            action = ACTION_APP_NOTIFICATION_SETTINGS,
            extras = mapOf(EXTRA_APP_PACKAGE to IntentExtra.PackageName),
        ),
        IntentCandidate(
            action = ACTION_CHANNEL_NOTIFICATION_SETTINGS,
            extras = mapOf(
                EXTRA_APP_PACKAGE to IntentExtra.PackageName,
                EXTRA_CHANNEL_ID to IntentExtra.Literal(CHANNEL_GUARD_STATUS),
            ),
        ),
        appInfo,
    )

    /** MIUI's app-details page, which repeats the app-info actions with MIUI's own layout. */
    private fun miuiAppDetails(): IntentCandidate = IntentCandidate(
        componentPackage = MIUI_SECURITY_CENTER,
        componentClass = MIUI_APP_DETAILS,
        extras = mapOf(EXTRA_MIUI_PKG_NAME to IntentExtra.PackageName),
    )

    /**
     * Whether [candidate] is an app-info page: the system one, or MIUI's own.
     *
     * The wizard uses this to know it has reached the end of a chain, and the test uses it to hold
     * the "always ends in App info" rule.
     */
    fun isAppInfo(candidate: IntentCandidate): Boolean =
        candidate.action == ACTION_APPLICATION_DETAILS_SETTINGS ||
            (candidate.componentPackage == MIUI_SECURITY_CENTER && candidate.componentClass == MIUI_APP_DETAILS)
}
