package com.ramish.sadiq.guard.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityWindowInfo
import com.ramish.sadiq.core.engine.AppSurface

/**
 * A reading of the current window stack, for [ForegroundResolver].
 *
 * It is a pure data class so the resolver can be a pure function that does not depend on Android
 * classes or the system's current notion of 'active' or 'focused'. The package name, bounds and
 * PiP state are extracted by the service and handed over.
 *
 * @property windowId `AccessibilityWindowInfo.id`. Used as a stable key when diffing two snapshots.
 * @property type `AccessibilityWindowInfo.type`.
 * @property packageName the package name.
 * @property bounds the bounds in screen.
 * @property isActive `AccessibilityWindowInfo.isActive`.
 * @property isFocused `AccessibilityWindowInfo.isFocused`.
 * @property isInPictureInPictureMode `AccessibilityWindowInfo.isInPictureInPictureMode`.
 * @property appSurface the surface kind, determined by the window itself (e.g. PiP).
 */
data class WindowSnapshot(
    val windowId: Int,
    val type: Int,
    val packageName: String?,
    val bounds: Rect,
    val isActive: Boolean,
    val isFocused: Boolean,
    val isInPictureInPictureMode: Boolean,
    val appSurface: AppSurface,
) {
    companion object {
        /**
         * Builds a [WindowSnapshot] from an [AccessibilityWindowInfo].
         *
         * @param window the window to snapshot.
         * @return a snapshot; the bounds are empty when the system does not provide them.
         */
        fun of(window: AccessibilityWindowInfo): WindowSnapshot {
            val bounds = Rect()
            window.getBoundsInScreen(bounds)
            return WindowSnapshot(
                windowId = window.id,
                type = window.type,
                packageName = window.root?.packageName?.toString(),
                bounds = bounds,
                isActive = window.isActive,
                isFocused = window.isFocused,
                isInPictureInPictureMode = window.isInPictureInPictureMode,
                appSurface = if (window.isInPictureInPictureMode) AppSurface.PIP else AppSurface.APP,
            )
        }
    }
}
