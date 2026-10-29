package com.ramish.sadiq.guard.service

import android.view.accessibility.AccessibilityWindowInfo
import com.ramish.sadiq.core.engine.AppSurface

/**
 * Resolves the foreground app from a stack of accessibility windows.
 *
 * It is a pure function: the package name, type and PiP state are extracted by the service into
 * [WindowSnapshot]s and handed over, so nothing here calls into the window manager.
 *
 * Two different ideas used to be conflated here, and separating them is the fix for AUDIT-2 G-01:
 *
 *  * **Transparent** — Sadiq itself, the keyboards the user enabled, the permission dialogs that
 *    ask on Sadiq's behalf, and every non-application window. None of them is a foreground.
 *  * **Never-guard** — the dialer, the camera, Settings, the launcher, the messaging apps (§3.6).
 *    They are never *intercepted*, but they are still *foregrounds*: the engine has to see them to
 *    hold a grant, to stop draining it, and to notice that he came back. Reporting them as `null`
 *    made going home, Recents, Camera and Settings invisible, so a grant kept draining over the
 *    camera and TIME_UP appeared there (G-01).
 */
object ForegroundResolver {

    /**
     * The packages that ask for permissions on Sadiq's behalf. A dialog of theirs is a window of
     * its own, never a foreground, so a permission prompt mid-Stay must neither hold nor end the
     * grant: it is skipped and the app underneath stays what is in front.
     *
     * `com.lbe.security.miui` is the MIUI permission manager, which shows its dialogs as ordinary
     * application windows (WP-14's acceptance names it beside the AOSP controller).
     */
    val permissionDialogPackages: Set<String> = setOf(
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.lbe.security.miui",
    )

    /**
     * The transparent set for a device: Sadiq, the keyboards he actually enabled, and the
     * permission dialogs.
     *
     * The rest of `AppCatalog.neverGuard` is deliberately absent. Those packages are never guarded,
     * but they are foregrounds the engine must be told about, so `Guardedness` — not this set — is
     * what keeps them from being intercepted.
     *
     * @param ownPackage Sadiq's own package, read from `context.packageName`.
     * @param enabledImePackages every enabled input method's package.
     * @return the packages a window read must look straight through.
     */
    fun transparentPackages(ownPackage: String, enabledImePackages: Set<String>): Set<String> {
        val transparent = LinkedHashSet<String>(enabledImePackages.size + permissionDialogPackages.size + 1)
        transparent.add(ownPackage)
        transparent.addAll(enabledImePackages.filter { it.isNotBlank() })
        transparent.addAll(permissionDialogPackages)
        return transparent
    }

    /** What one read of the window stack concluded. */
    sealed interface Result {

        /**
         * An app is in front, guarded or not. The engine decides whether that means a question, a
         * held grant or nothing at all.
         *
         * @property packageName the package in front.
         * @property surface which surface of it is in front (a PiP window is [AppSurface.PIP]).
         */
        data class App(val packageName: String, val surface: AppSurface) : Result

        /**
         * Sadiq's own window is what is in front. Not a decision — the engine treats it as
         * transparent — but it ends the run of identical foregrounds so that the next open of the
         * same app is submitted again instead of being de-duplicated away.
         */
        data object OwnWindow : Result

        /** Nothing readable is in front: an empty stack, only system windows, or a locked screen. */
        data object Unknown : Result
    }

    /**
     * Resolves the foreground from the live window stack.
     *
     * @param windows the current window stack, from `AccessibilityService.getWindows()`.
     * @param ownPackage Sadiq's own package name.
     * @param transparentPackages packages to look through; use [transparentPackages] to build it.
     * @return the [Result] of the read.
     */
    fun resolve(
        windows: List<AccessibilityWindowInfo>,
        ownPackage: String,
        transparentPackages: Set<String> = emptySet(),
    ): Result = resolveSnapshots(
        snapshots = windows.map { WindowSnapshot.of(it) },
        ownPackage = ownPackage,
        transparentPackages = transparentPackages,
    )

    /**
     * Resolves the foreground from snapshots that have already been taken. This is the pure half,
     * and the one the tests hold still.
     *
     * The topmost *active* application window wins. When no application window is active at all — a
     * translucent activity on top, a system dialog holding the interaction — the topmost *focused*
     * application window is used instead, which is how the app underneath a dialog is still found.
     *
     * @param snapshots the window stack, in the order the system returned it (topmost first).
     * @param ownPackage Sadiq's own package name.
     * @param transparentPackages packages to look straight through.
     * @return the [Result] of the read.
     */
    fun resolveSnapshots(
        snapshots: List<WindowSnapshot>,
        ownPackage: String,
        transparentPackages: Set<String> = emptySet(),
    ): Result {
        val applicationWindows = snapshots.filter {
            it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.packageName != null
        }
        val ordered = applicationWindows.filter { it.isActive } +
            applicationWindows.filter { !it.isActive && it.isFocused }

        for (window in ordered) {
            val packageName = window.packageName ?: continue
            if (packageName == ownPackage) return Result.OwnWindow
            if (packageName in transparentPackages) continue
            return Result.App(packageName, window.appSurface)
        }

        return Result.Unknown
    }
}
