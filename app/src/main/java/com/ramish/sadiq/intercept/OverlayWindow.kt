package com.ramish.sadiq.intercept

import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * The one window the fallback owns, and the only place a `WindowManager` is touched.
 *
 * `TYPE_ACCESSIBILITY_OVERLAY` is the only window a connected accessibility service may put over
 * another app without `SYSTEM_ALERT_WINDOW` (§3.5). That is why the fallback exists at all: Android
 * 12+ forbids the background activity start that would otherwise show the question, and an OEM that
 * refuses that start silently leaves the engine in `PROMPTING` with nothing on screen (G-13).
 *
 * Every method here is safe to call in any order and none of them throws. A window that cannot be
 * added or removed is logged and reported as `false`: an exception in this class would take the
 * accessibility service down with it, and the phone would stop being guarded at all.
 *
 * Main thread only. `addView` creates a `ViewRootImpl`, which needs the calling thread's `Looper`.
 *
 * @param windowManager the service's window manager, from `GuardServiceHandle.windowManager()`.
 */
internal class OverlayWindow(private val windowManager: WindowManager) {

    /** The attached view, or null when no window is up. Written on the main thread only. */
    private var view: View? = null

    /** Whether a window is attached right now. */
    val isShowing: Boolean get() = view != null

    /**
     * Attaches [content] as the overlay window, replacing anything this instance was already showing.
     *
     * @param content the full-screen view to draw.
     * @return true when the window is attached; false when the platform refused it.
     */
    fun show(content: View): Boolean {
        if (view === content) return true
        hide()
        return try {
            windowManager.addView(content, params())
            view = content
            true
        } catch (failure: Exception) {
            // SecurityException (no accessibility binding), BadTokenException (the service's window
            // session went away) and IllegalStateException (the view is already attached) all land
            // here, and they all mean one thing: no question on screen this time.
            Log.w(TAG, "could not add the window: ${failure.javaClass.simpleName}: ${failure.message}")
            runCatching { windowManager.removeViewImmediate(content) }
            false
        }
    }

    /** Removes the window. Silent when nothing is attached, and safe to call twice. */
    fun hide() {
        val attached = view ?: return
        view = null
        try {
            // Immediate, not animated: the question is gone, and a window that lingers for one more
            // frame is a question the user can still tap into nothing.
            windowManager.removeViewImmediate(attached)
        } catch (failure: Exception) {
            Log.w(TAG, "could not remove the window: ${failure.javaClass.simpleName}: ${failure.message}")
        }
    }

    companion object {
        /** The tag every line of the fallback is logged under. */
        const val TAG: String = "SP/Overlay"

        /**
         * Whether any host may draw the fallback at all.
         *
         * The single source of truth for "is there a backup layer": [OverlayHost] refuses to draw
         * unless this is true, and the presenter must not ask for it, count it, or promise it in copy
         * while it is false. False because the first device run crashed the process on the fallback's
         * first frame (AUDIT-4 A4-01) and has never been re-run since the fix.
         */
        const val HOST_ENABLED: Boolean = false

        /**
         * The window's parameters.
         *
         * Three flags matter and one absence does:
         *
         * - `FLAG_NOT_FOCUSABLE` is deliberately **not** set. A non-focusable window still gets
         *   touches, but it cannot take keys, and the plan's rule zero makes Back an answer on every
         *   surface (§3.6). The window is full-screen and above the app, so holding focus costs the
         *   app underneath nothing it was still allowed to do.
         * - `FLAG_LAYOUT_IN_SCREEN` lays the content out edge to edge, which is what
         *   `InterceptScreen` expects: it is the same composable the activity draws.
         * - `FLAG_HARDWARE_ACCELERATED` is required. A window added by a service does not inherit an
         *   activity's acceleration, and Compose draws nothing on a software canvas — the failure
         *   mode is a dark, blank cover.
         */
        fun params(): WindowManager.LayoutParams = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Names the window in `dumpsys window`, which is the only way to see it in the field.
            setTitle("Sadiq pause")
        }
    }
}

/**
 * The root of the overlay's view tree: a full-screen frame that turns Back into Leave.
 *
 * A window added by a service has no `OnBackPressedDispatcher` of its own, so a Back press never
 * reaches Compose's `BackHandler` — it arrives here. Both the down and the up event are consumed so
 * the app underneath never sees half a press, and the answer is sent on the up, exactly as the
 * "No, take me home" pill sends it.
 *
 * @param onBack what a completed Back press means: the same answer as leaving.
 */
internal class OverlayRootView(
    context: Context,
    private val onBack: () -> Unit,
) : FrameLayout(context) {

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_UP) onBack()
        return true
    }
}
