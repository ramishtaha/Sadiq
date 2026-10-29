package com.ramish.sadiq.core.engine

import com.ramish.sadiq.core.model.GuardConfig
import com.ramish.sadiq.core.model.SurfaceMode

/**
 * Decides whether a foreground change is a guarded target, per §3.6 "What counts as a guarded
 * target".
 *
 * Three independent reasons make an app guarded: the whole app is listed, the app is listed for
 * Shorts only and the current surface is Shorts, or web guard is on and the domain matches. Our own
 * package and the never-guard list win over all three, so no configuration mistake can ever cover
 * the dialer, the launcher or Sadiq itself.
 *
 * CONTRACT-GAP: `AppCatalog.neverGuard` (WP-12) lives in `core/apps`; core cannot depend on a file
 * another package is still writing without risking the whole module's compilation, so the §3.6 list
 * is duplicated here as [neverGuard]. WP-13 should reconcile the two when it wires the catalog.
 */
object Guardedness {

    /** Sadiq's own package. A foreground change to it never changes what is in front (§3.6). */
    const val OWN_PACKAGE: String = "com.ramish.sadiq"

    /**
     * Packages that must never be guarded, even if the user adds them by mistake (§3.6):
     * dialer, contacts, SMS, camera, clock, Settings, launcher, system UI, IMEs, Xiaomi Security,
     * Sadiq and Rafiq.
     */
    val neverGuard: Set<String> = setOf(
        // Sadiq and Rafiq.
        OWN_PACKAGE,
        "com.rafiq.app",
        // Dialer, contacts, messaging.
        "com.android.dialer",
        "com.google.android.dialer",
        "com.android.contacts",
        "com.google.android.contacts",
        "com.android.mms",
        "com.google.android.apps.messaging",
        "com.samsung.android.dialer",
        // Camera.
        "com.android.camera",
        "com.android.camera2",
        "com.google.android.GoogleCamera",
        "com.google.android.apps.camera",
        "com.xiaomi.camera",
        // Clock.
        "com.android.deskclock",
        "com.google.android.deskclock",
        "com.xiaomi.calendar.clock", // MIUI clock on some builds
        // Settings, installer, permission UI.
        "com.android.settings",
        "com.google.android.settings",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        // System UI and launchers.
        "com.android.systemui",
        "com.google.android.apps.nexuslauncher",
        "com.android.launcher",
        "com.android.launcher3",
        "com.miui.home",
        "com.miui.securitycenter",
        // Keyboards: a guarded IME would cover the very keyboard he needs to type with.
        "com.google.android.inputmethod.latin",
        "com.android.inputmethod.latin",
        "com.sohu.inputmethod.sogou",
        "com.baidu.input",
        "com.miui.inputmethod",
        "com.miui.inputmethod.MiuiInputMethod",
    )

    /** True when nothing about this package/domain/surface may ever be intercepted. */
    fun isNeverGuarded(packageName: String): Boolean =
        packageName == OWN_PACKAGE || packageName in neverGuard

    /**
     * The guarded-target test of §3.6. [surface] only matters for [SurfaceMode.SHORTS_ONLY] apps;
     * [domain] only matters while web guard is on.
     */
    fun isGuarded(
        packageName: String?,
        domain: String?,
        surface: AppSurface,
        config: GuardConfig,
    ): Boolean {
        if (packageName.isNullOrEmpty()) return false
        if (isNeverGuarded(packageName)) return false

        val app = config.apps.firstOrNull { it.packageName == packageName }
        if (app != null) {
            when (app.surfaceMode) {
                SurfaceMode.WHOLE_APP -> return true
                SurfaceMode.SHORTS_ONLY -> if (surface == AppSurface.SHORTS) return true
            }
        }

        if (config.webGuardEnabled && !domain.isNullOrEmpty() && config.domains.isNotEmpty()) {
            val host = normalizeDomain(domain) ?: return false
            if (config.domains.any { domainMatches(host, it) }) return true
        }
        return false
    }

    /**
     * Whether a package is guarded at all, ignoring its surface mode. Used by reconciliation, where
     * usage spans carry no surface: a Shorts-only app is still his guarded app.
     */
    fun isGuardedPackage(packageName: String?, config: GuardConfig): Boolean {
        if (packageName.isNullOrEmpty()) return false
        if (isNeverGuarded(packageName)) return false
        return config.apps.any { it.packageName == packageName }
    }

    /**
     * Re-evaluates a showing prompt against a possibly changed config (T20).
     *
     * `Prompt` does not record the [AppSurface] it was opened with, so a Shorts-only app is treated
     * as still guarded here: dropping its surface on every later input would be worse than keeping
     * one surface alive across a settings change.
     */
    fun stillGuarded(prompt: Prompt, config: GuardConfig): Boolean {
        if (isNeverGuarded(prompt.packageName)) return false
        val app = config.apps.firstOrNull { it.packageName == prompt.packageName }
        if (app != null) return true
        if (config.webGuardEnabled && !prompt.domain.isNullOrEmpty() && config.domains.isNotEmpty()) {
            val host = normalizeDomain(prompt.domain) ?: return false
            return config.domains.any { domainMatches(host, it) }
        }
        return false
    }

    /** Lower-cased host of a raw URL or domain, with scheme, path, port, userinfo and `www.` gone. */
    fun normalizeDomain(raw: String?): String? {
        val trimmed = raw?.trim()?.lowercase().orEmpty()
        if (trimmed.isEmpty()) return null
        var host = trimmed.substringAfter("://", trimmed)
        host = host.substringBefore('/').substringBefore('?').substringBefore('#')
        host = host.substringAfter('@')
        host = host.substringBefore(':')
        host = host.removePrefix("www.")
        return host.ifEmpty { null }
    }

    /** `instagram.com` matches `instagram.com` and `cdn.instagram.com`, never `notinstagram.com`. */
    fun domainMatches(host: String, guardedDomain: String): Boolean {
        val guarded = normalizeDomain(guardedDomain) ?: return false
        val clean = normalizeDomain(host) ?: return false
        return clean == guarded || clean.endsWith(".$guarded")
    }
}
