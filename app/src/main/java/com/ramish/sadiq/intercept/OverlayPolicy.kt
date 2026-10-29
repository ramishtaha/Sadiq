package com.ramish.sadiq.intercept

/**
 * The rule that decides whether the accessibility overlay should be on screen.
 *
 * It is a pure function for one reason: this decision is the part of the fallback that can be wrong
 * without a device. The host around it owns a `WindowManager`, a `ComposeView` and a lifecycle, none
 * of which a JVM test can hold; these five booleans are the whole of "should the question be drawn
 * right now", and every combination that matters is held still by `OverlayPolicyTest`.
 *
 * The overlay is a fallback, never the default (AUDIT-3 G-13). It is drawn only while the presenter
 * has asked for it — `InterceptStateHolder.overlayRequested`, set after `InterceptActivity` has
 * missed its 600 ms window — and it is removed the moment that request, the surface, the engine's
 * `PROMPTING` phase or the service binding goes away. None of those conditions may be inferred by
 * the window itself: a window that outlives its question covers the phone, and a question with no
 * window is the bug this whole path exists to fix.
 */
object OverlayPolicy {

    /** What the host should do with its window. */
    enum class Action {
        /** Nothing is asked for, and nothing is up. */
        NONE,

        /** No window yet: add one and render the surface into it. */
        SHOW,

        /** A window is already up and the question is still the same kind of question: redraw it. */
        UPDATE,

        /** A window is up and the question is over, or can no longer be drawn: take it down. */
        REMOVE,
    }

    /**
     * Decides the window's fate from the state the host can read synchronously.
     *
     * @param hasSurface whether the holder has a surface to draw, i.e. `InterceptStateHolder.ui`.
     * @param requested whether the presenter asked for the overlay fallback.
     * @param prompting whether the engine is still asking a question (`PROMPTING` with a prompt).
     * @param serviceBound whether the accessibility service is bound — the only context that may
     *   add a `TYPE_ACCESSIBILITY_OVERLAY` window, so without it there is nothing to draw on.
     * @param showing whether this host currently owns an attached window.
     * @return the action to take.
     */
    fun decide(
        hasSurface: Boolean,
        requested: Boolean,
        prompting: Boolean,
        serviceBound: Boolean,
        showing: Boolean,
    ): Action {
        val mayDraw = hasSurface && requested && prompting && serviceBound
        return when {
            // A stale window is worse than none: it covers the app the user is in with a question
            // the engine has already answered, and nothing will ever take it down again.
            showing && !mayDraw -> Action.REMOVE
            showing -> Action.UPDATE
            mayDraw -> Action.SHOW
            else -> Action.NONE
        }
    }
}
