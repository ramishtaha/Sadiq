package com.ramish.sadiq.system

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The three states of an accessibility binding, at the boundary where Health and the Sky change
 * their answer.
 *
 * A4-14 was one line — an unreported binding read as connected for ever — so the cases that matter
 * are the ones that line got wrong: a cold start (must not accuse a service that has not had its
 * chance) and a process HyperOS restarted with no service in it (must not claim to be guarding).
 * The clocks are plain numbers; the rule takes elapsed milliseconds so nothing here needs Android.
 */
class AccessibilityConnectionTest {

    /** Some milliseconds after boot; the value is irrelevant next to the differences below. */
    private val processStart = 1_000_000L

    @Test
    fun theGraceWindowIsTenSeconds() {
        // The number is AUDIT-4's, and the copy around it ("On but not running") assumes it is short.
        assertEquals(10_000L, AccessibilityConnection.STARTUP_GRACE_MS)
    }

    @Test
    fun anUnreportedBindingOneSecondIntoTheProcessIsBelieved() {
        // The cold Health check A4-14 says must not scream red: the service simply has not bound yet.
        assertTrue(
            AccessibilityConnection.isConnected(
                reported = false,
                connected = false,
                processStartElapsedMs = processStart,
                nowElapsedMs = processStart + 1_000L,
            )
        )
    }

    @Test
    fun anUnreportedBindingAtTheLastMillisecondOfTheWindowIsStillBelieved() {
        assertTrue(
            AccessibilityConnection.isConnected(
                reported = false,
                connected = false,
                processStartElapsedMs = processStart,
                nowElapsedMs = processStart + AccessibilityConnection.STARTUP_GRACE_MS - 1L,
            )
        )
    }

    @Test
    fun anUnreportedBindingAtTheEndOfTheWindowIsNot() {
        // The window is half-open: at exactly ten seconds the service owed a report and did not give
        // one, which is the state HyperOS leaves behind after it kills the process.
        assertFalse(
            AccessibilityConnection.isConnected(
                reported = false,
                connected = false,
                processStartElapsedMs = processStart,
                nowElapsedMs = processStart + AccessibilityConnection.STARTUP_GRACE_MS,
            )
        )
    }

    @Test
    fun anUnreportedBindingLongAfterTheWindowIsNot() {
        assertFalse(
            AccessibilityConnection.isConnected(
                reported = false,
                connected = false,
                processStartElapsedMs = processStart,
                nowElapsedMs = processStart + 60 * 60_000L,
            )
        )
    }

    @Test
    fun aReportedTrueIsConnectedWhateverTheAge() {
        assertTrue(
            AccessibilityConnection.isConnected(
                reported = true,
                connected = true,
                processStartElapsedMs = processStart,
                nowElapsedMs = processStart + 5_000L,
            )
        )
        assertTrue(
            AccessibilityConnection.isConnected(
                reported = true,
                connected = true,
                processStartElapsedMs = processStart,
                nowElapsedMs = processStart + 60 * 60_000L,
            )
        )
    }

    @Test
    fun aReportedFalseWinsEvenInsideTheWindow() {
        // What `onDestroy` now reports: the service said it is gone, so its age is not a defence.
        assertFalse(
            AccessibilityConnection.isConnected(
                reported = true,
                connected = false,
                processStartElapsedMs = processStart,
                nowElapsedMs = processStart + 5_000L,
            )
        )
    }

    @Test
    fun anUndateableProcessKeepsTheWholeWindow() {
        // `Process.getStartElapsedRealtime()` answers 0 when it cannot date the process. That is not
        // evidence the service is gone, so the window starts now instead of reading the process as
        // ancient and failing closed.
        assertTrue(
            AccessibilityConnection.isConnected(
                reported = false,
                connected = false,
                processStartElapsedMs = 0L,
                nowElapsedMs = 5_000_000L,
            )
        )
    }

    @Test
    fun aStartAfterNowIsNotTreatedAsAncient() {
        // A clock that disagrees with itself must not produce a red Health row on a live service.
        assertTrue(
            AccessibilityConnection.isConnected(
                reported = false,
                connected = false,
                processStartElapsedMs = processStart + 5_000L,
                nowElapsedMs = processStart,
            )
        )
    }

    @Test
    fun theRemainingWindowCountsDownAndStopsAtZero() {
        assertEquals(
            6_000L,
            AccessibilityConnection.graceRemainingMs(processStart, processStart + 4_000L),
        )
        assertEquals(
            0L,
            AccessibilityConnection.graceRemainingMs(
                processStart,
                processStart + AccessibilityConnection.STARTUP_GRACE_MS,
            ),
        )
        assertEquals(0L, AccessibilityConnection.graceRemainingMs(processStart, processStart + 10 * 60_000L))
    }
}
