package com.ramish.sadiq.guard.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityWindowInfo
import com.ramish.sadiq.core.engine.AppSurface
import com.ramish.sadiq.core.engine.GuardInput
import com.ramish.sadiq.core.engine.GuardInput.ForegroundChanged

/**
 * Resolves the foreground app from a stack of accessibility windows.
 *
 * It is a pure function that does not depend on Android classes or the system's current notion of
 * 'active' or 'focused'. The package name, bounds and PiP state are extracted by the service and
 * handed over. It ignores IME, system UI, our own package (transparent) and permission dialogs (§3.6).
 */
object ForegroundResolver {

    /**
     * Resolves the foreground app from a stack of accessibility windows.
     *
     * @param windows the current window stack, from `AccessibilityService.getWindows()`.
     * @param ownPackage Sadiq's own package name, so it can be ignored.
     * @param neverGuardPackages the set of packages that must never be guarded (§3.6).
     * @return a [ForegroundChanged] input, or null when nothing conclusive was found.
     */
    fun resolve(
        windows: List<AccessibilityWindowInfo>,
        ownPackage: String,
        neverGuardPackages: Set<String>,
    ): ForegroundChanged? {
        val snapshots = windows.map { WindowSnapshot.of(it) }

        // Find the topmost active application window that is not our own, an IME, or system UI.
        val candidate = snapshots.firstOrNull { window ->
            window.isActive &&
                window.packageName != null &&
                window.packageName != ownPackage &&
                window.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                window.packageName !in neverGuardPackages
        }

        if (candidate != null) {
            return ForegroundChanged(
                packageName = candidate.packageName,
                surface = candidate.appSurface,
            )
        }

        // Fallback: if no active application window is found, look for the topmost focused application window.
        // This can happen when a transparent activity is on top, or a system dialog.
        val fallbackCandidate = snapshots.firstOrNull { window ->
            window.isFocused &&
                window.packageName != null &&
                window.packageName != ownPackage &&
                window.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                window.packageName !in neverGuardPackages
        }

        if (fallbackCandidate != null) {
            return ForegroundChanged(
                packageName = fallbackCandidate.packageName,
                surface = fallbackCandidate.appSurface,
            )
        }

        return null
    }
}
