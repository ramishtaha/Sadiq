package com.ramish.sadiq.guard.service

import com.ramish.sadiq.core.engine.GuardInput.ForegroundChanged

/**
 * The de-duplication between window events and the engine (AUDIT-2 G-01).
 *
 * The accessibility framework delivers several `TYPE_WINDOWS_CHANGED` events for one visual change,
 * so the service submits a foreground only when it differs from the last one it submitted. What
 * matters is *when the memory ends*: not only when the package changes, but on every event that
 * means "the foreground genuinely stopped being that app". Sadiq's own surface sitting on top is
 * exactly such a moment — he opened Instagram, Sadiq asked, Sadiq's window became the foreground —
 * and forgetting the memory there is what makes the second open of the same app ask again instead
 * of being swallowed as a duplicate. Without it, going home, Recents, Camera and Settings were
 * invisible and the engine never learned that he had left.
 *
 * Main thread only: `onAccessibilityEvent` and the registered receiver's `onReceive` are both
 * delivered on it, so the field needs no synchronisation.
 */
class ForegroundGate {

    private var lastSubmitted: ForegroundChanged? = null

    /**
     * Decides what one window read should submit.
     *
     * @param result what [ForegroundResolver] made of the window stack.
     * @return the input to submit, or null when the read repeats the last one or is not a decision.
     */
    fun submitFor(result: ForegroundResolver.Result): ForegroundChanged? = when (result) {
        is ForegroundResolver.Result.App -> {
            val input = ForegroundChanged(packageName = result.packageName, surface = result.surface)
            if (input == lastSubmitted) {
                null
            } else {
                lastSubmitted = input
                input
            }
        }

        // Sadiq's own window ends the run: the next app is a new open even if it is the same one.
        ForegroundResolver.Result.OwnWindow -> {
            lastSubmitted = null
            null
        }

        // An unreadable or empty stack says nothing about what is in front, so it must not open the
        // door to a duplicate submission of the app that is still there.
        ForegroundResolver.Result.Unknown -> null
    }

    /** Forgets the last submission: called on screen off, and on a fresh service connection. */
    fun reset() {
        lastSubmitted = null
    }
}
