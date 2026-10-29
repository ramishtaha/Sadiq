package com.ramish.sadiq.notify

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * AUDIT-4 A4-10: "Focus complete" is posted only to a phone that is in his pocket, and only when the
 * session actually lit a star.
 *
 * The rule is a plain truth table so it can be pinned here; the Android half (reading
 * `PowerManager.isInteractive`) is one call in [LiveNotifierImpl.focusComplete]. The `+N` line itself
 * is pinned by [LiveTextTest].
 */
class FocusCompletePostTest {

    @Test
    fun theCompletionPostNeedsTheScreenOff() {
        assertFalse(LiveNotifierImpl.shouldPostFocusComplete(stars = 3, screenInteractive = true))
    }

    @Test
    fun aSessionThatLitNothingIsNeverAnnounced() {
        // The focus cap and the daily cap both end a session with no award, and the only line left
        // would be "+0 stars in your sky" (§2.2).
        assertFalse(LiveNotifierImpl.shouldPostFocusComplete(stars = 0, screenInteractive = false))
        assertFalse(LiveNotifierImpl.shouldPostFocusComplete(stars = 0, screenInteractive = true))
    }

    @Test
    fun aLitSessionWithTheScreenOffIsAnnounced() {
        assertTrue(LiveNotifierImpl.shouldPostFocusComplete(stars = 1, screenInteractive = false))
    }
}
