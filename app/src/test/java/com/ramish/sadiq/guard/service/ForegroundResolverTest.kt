package com.ramish.sadiq.guard.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityWindowInfo
import com.ramish.sadiq.core.apps.AppCatalog
import com.ramish.sadiq.core.engine.AppSurface
import com.ramish.sadiq.guard.service.ForegroundResolver.Result
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AUDIT-2 G-01: never-guard is not the same thing as invisible.
 *
 * The resolver used to return `null` for every package in `AppCatalog.neverGuard`, which made going
 * home, Recents, the camera, the dialer and Settings invisible to the engine: a grant kept draining
 * there, TIME_UP appeared over the camera, and the second open of the same app was de-duplicated
 * away. These cases pin the split the fix introduced — *transparent* (Sadiq, keyboards, permission
 * dialogs, non-application windows) versus *never guarded but still a foreground*.
 */
class ForegroundResolverTest {

    private val own = "com.ramish.sadiq"
    private val instagram = "com.instagram.android"
    private val youtube = "com.google.android.youtube"
    private val launcher = "com.miui.home"
    private val camera = "com.android.camera"
    private val dialer = "com.google.android.dialer"
    private val settings = "com.android.settings"
    private val gboard = "com.google.android.inputmethod.latin"
    private val permissionController = "com.google.android.permissioncontroller"

    private val transparent = ForegroundResolver.transparentPackages(
        ownPackage = own,
        enabledImePackages = setOf(gboard),
    )

    @Test
    fun theLauncherIsAForegroundSoGoingHomeIsVisible() {
        assertTrue(launcher in AppCatalog.baseNeverGuard, "the launcher is never guarded")
        assertEquals(Result.App(launcher, AppSurface.APP), resolve(window(launcher)))
    }

    @Test
    fun theCameraTheDialerAndSettingsAreForegroundsToo() {
        // Every one of these is in the never-guard list and used to be reported as null.
        assertEquals(Result.App(camera, AppSurface.APP), resolve(window(camera)))
        assertEquals(Result.App(dialer, AppSurface.APP), resolve(window(dialer)))
        assertEquals(Result.App(settings, AppSurface.APP), resolve(window(settings)))
    }

    @Test
    fun sadiqsOwnWindowIsItsOwnAnswerNotAForeground() {
        assertEquals(Result.OwnWindow, resolve(window(own)))
    }

    @Test
    fun sadiqsOwnWindowOnTopOfTheAppIsOwnWindow() {
        // The question is showing over Instagram: the read must say "Sadiq", so the service forgets
        // the last foreground and the same app asks again when it comes back.
        val windows = listOf(window(own), window(instagram, isActive = false, isFocused = false))
        assertEquals(Result.OwnWindow, resolve(windows))
    }

    @Test
    fun anEnabledKeyboardIsLookedThrough() {
        // Keyboards are usually TYPE_INPUT_METHOD, but a ROM may hand one over as an application
        // window; it must never become the foreground he is asked about.
        assertTrue(gboard in transparent, "the enabled keyboard is in the transparent set")
        val windows = listOf(window(gboard), window(instagram, isActive = false, isFocused = true))
        assertEquals(Result.App(instagram, AppSurface.APP), resolve(windows))
    }

    @Test
    fun aPermissionDialogIsLookedThrough() {
        val windows = listOf(
            window(permissionController),
            window(instagram, isActive = false, isFocused = true),
        )
        assertEquals(Result.App(instagram, AppSurface.APP), resolve(windows))
    }

    @Test
    fun theMiuiPermissionManagerIsLookedThrough() {
        val miuiSecurity = "com.lbe.security.miui"
        val windows = listOf(
            window(miuiSecurity),
            window(instagram, isActive = false, isFocused = true),
        )
        assertEquals(Result.App(instagram, AppSurface.APP), resolve(windows))
    }

    @Test
    fun aSystemWindowIsNeverAForeground() {
        val windows = listOf(
            window("com.android.systemui", type = AccessibilityWindowInfo.TYPE_SYSTEM),
            window(instagram, isActive = false, isFocused = true),
        )
        assertEquals(Result.App(instagram, AppSurface.APP), resolve(windows))
    }

    @Test
    fun onlySystemWindowsIsUnknown() {
        val windows = listOf(
            window("com.android.systemui", type = AccessibilityWindowInfo.TYPE_SYSTEM),
            window(null, type = AccessibilityWindowInfo.TYPE_SYSTEM),
        )
        assertEquals(Result.Unknown, resolve(windows))
    }

    @Test
    fun anEmptyStackIsUnknown() {
        assertEquals(Result.Unknown, resolve(emptyList()))
    }

    @Test
    fun aWindowWithNoPackageNameIsSkipped() {
        // A window whose root is not readable reports no package; it must not hide the app below.
        assertEquals(
            Result.App(instagram, AppSurface.APP),
            resolve(window(null), window(instagram, isActive = false, isFocused = true)),
        )
    }

    @Test
    fun aFocusedWindowIsUsedWhenNothingIsActive() {
        // A translucent activity on top, or a dialog holding the focus: the app underneath is still
        // what he is looking at.
        assertEquals(Result.App(instagram, AppSurface.APP), resolve(window(instagram, isActive = false, isFocused = true)))
    }

    @Test
    fun theTopmostActiveWindowBeatsAFocusedOneBelow() {
        val windows = listOf(
            window(youtube, isActive = true, isFocused = false),
            window(instagram, isActive = false, isFocused = true),
        )
        assertEquals(Result.App(youtube, AppSurface.APP), resolve(windows))
    }

    @Test
    fun aPictureInPictureWindowKeepsItsSurface() {
        assertEquals(
            Result.App(instagram, AppSurface.PIP),
            resolve(window(instagram, inPip = true)),
        )
    }

    @Test
    fun youtubeInPipOverTheLauncherIsYouTubeWithThePipSurface() {
        val windows = listOf(
            window(youtube, inPip = true),
            window(launcher, isActive = false, isFocused = false),
        )
        assertEquals(Result.App(youtube, AppSurface.PIP), resolve(windows))
    }

    @Test
    fun splitScreenResolvesToOneAppWithTheActiveWindow() {
        // Two application windows side by side: the one being touched is the one that counts.
        val windows = listOf(
            window(instagram, isActive = true, isFocused = false),
            window(youtube, isActive = false, isFocused = false),
        )
        assertEquals(Result.App(instagram, AppSurface.APP), resolve(windows))
    }

    @Test
    fun theTransparentSetIsSadiqTheKeyboardsAndTheDialogsOnly() {
        assertEquals(
            setOf(own, gboard) + ForegroundResolver.permissionDialogPackages,
            transparent,
        )
        // The launcher must not be in it: that was the bug.
        assertTrue(launcher !in transparent)
        assertTrue(camera !in transparent)
        assertTrue(settings !in transparent)
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    private var nextWindowId = 0

    private fun resolve(windows: List<WindowSnapshot>): Result =
        ForegroundResolver.resolveSnapshots(windows, ownPackage = own, transparentPackages = transparent)

    private fun resolve(vararg windows: WindowSnapshot): Result = resolve(windows.toList())

    private fun window(
        packageName: String?,
        type: Int = AccessibilityWindowInfo.TYPE_APPLICATION,
        isActive: Boolean = true,
        isFocused: Boolean = isActive,
        inPip: Boolean = false,
    ): WindowSnapshot = WindowSnapshot(
        windowId = nextWindowId++,
        type = type,
        packageName = packageName,
        bounds = Rect(0, 0, 1080, 2400),
        isActive = isActive,
        isFocused = isFocused,
        isInPictureInPictureMode = inPip,
        appSurface = if (inPip) AppSurface.PIP else AppSurface.APP,
    )
}
