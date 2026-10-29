package com.ramish.sadiq.system

import com.ramish.sadiq.core.engine.UsageSpan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The three hard cases of §3.6's reconciliation, plus the noise around them.
 *
 * Every span here is a real shape the platform produces: a resumed/paused pair, a session that is
 * still open when the process dies (the one that matters most, because reconciliation exists for
 * exactly that moment), and a screen-off that arrives without a pause.
 */
class UsageSpansTest {

    private val instagram = "com.instagram.android"
    private val youtube = "com.google.android.youtube"

    @Test
    fun aResumeAndAPauseMakeOneSpan() {
        val spans = UsageSpans.fold(
            listOf(
                event(instagram, UsageEventKind.RESUMED, 1_000L),
                event(instagram, UsageEventKind.PAUSED, 4_000L),
            ),
            toWall = 10_000L,
        )
        assertEquals(listOf(UsageSpan(instagram, 1_000L, 4_000L)), spans)
    }

    @Test
    fun aResumeOfAnotherAppClosesTheOpenSpan() {
        val spans = UsageSpans.fold(
            listOf(
                event(instagram, UsageEventKind.RESUMED, 1_000L),
                event(youtube, UsageEventKind.RESUMED, 3_000L),
                event(youtube, UsageEventKind.PAUSED, 5_000L),
            ),
            toWall = 9_000L,
        )
        assertEquals(
            listOf(
                UsageSpan(instagram, 1_000L, 3_000L),
                UsageSpan(youtube, 3_000L, 5_000L),
            ),
            spans,
        )
    }

    @Test
    fun aMissingPauseClosesTheSpanAtTheEndOfTheQuery() {
        // The process died mid-session; this span is the reason reconciliation exists.
        val spans = UsageSpans.fold(
            listOf(event(instagram, UsageEventKind.RESUMED, 1_000L)),
            toWall = 90_000L,
        )
        assertEquals(listOf(UsageSpan(instagram, 1_000L, 90_000L)), spans)
    }

    @Test
    fun screenOffClosesTheOpenSpan() {
        val spans = UsageSpans.fold(
            listOf(
                event(instagram, UsageEventKind.RESUMED, 1_000L),
                event(null, UsageEventKind.SCREEN_OFF, 2_500L),
                // A pause can arrive after the screen-off, from the app being torn down.
                event(instagram, UsageEventKind.PAUSED, 3_000L),
            ),
            toWall = 60_000L,
        )
        assertEquals(listOf(UsageSpan(instagram, 1_000L, 2_500L)), spans)
    }

    @Test
    fun screenOffWithNothingOpenAddsNothing() {
        val spans = UsageSpans.fold(
            listOf(event(null, UsageEventKind.SCREEN_OFF, 2_500L)),
            toWall = 60_000L,
        )
        assertTrue(spans.isEmpty())
    }

    @Test
    fun screenOnIsNotABoundary() {
        val spans = UsageSpans.fold(
            listOf(
                event(instagram, UsageEventKind.RESUMED, 1_000L),
                event(null, UsageEventKind.SCREEN_OFF, 2_000L),
                event(null, UsageEventKind.SCREEN_ON, 3_000L),
                event(instagram, UsageEventKind.RESUMED, 4_000L),
                event(instagram, UsageEventKind.PAUSED, 5_000L),
            ),
            toWall = 10_000L,
        )
        assertEquals(
            listOf(
                UsageSpan(instagram, 1_000L, 2_000L),
                UsageSpan(instagram, 4_000L, 5_000L),
            ),
            spans,
        )
    }

    @Test
    fun aRepeatedResumeDoesNotSplitTheSession() {
        val spans = UsageSpans.fold(
            listOf(
                event(instagram, UsageEventKind.RESUMED, 1_000L),
                event(instagram, UsageEventKind.RESUMED, 2_000L),
                event(instagram, UsageEventKind.PAUSED, 3_000L),
            ),
            toWall = 10_000L,
        )
        assertEquals(listOf(UsageSpan(instagram, 1_000L, 3_000L)), spans)
    }

    @Test
    fun aPauseWithNoResumeIsIgnored() {
        // The session began before the query window; charging it would invent usage.
        val spans = UsageSpans.fold(
            listOf(
                event(instagram, UsageEventKind.PAUSED, 1_000L),
                event(youtube, UsageEventKind.RESUMED, 2_000L),
            ),
            toWall = 10_000L,
        )
        assertEquals(listOf(UsageSpan(youtube, 2_000L, 10_000L)), spans)
    }

    @Test
    fun aPauseForAnotherAppDoesNotCloseTheOpenSpan() {
        val spans = UsageSpans.fold(
            listOf(
                event(instagram, UsageEventKind.RESUMED, 1_000L),
                event(youtube, UsageEventKind.PAUSED, 2_000L),
                event(instagram, UsageEventKind.PAUSED, 3_000L),
            ),
            toWall = 10_000L,
        )
        assertEquals(listOf(UsageSpan(instagram, 1_000L, 3_000L)), spans)
    }

    @Test
    fun eventsAreSortedBeforeFolding() {
        val spans = UsageSpans.fold(
            listOf(
                event(instagram, UsageEventKind.PAUSED, 4_000L),
                event(youtube, UsageEventKind.RESUMED, 3_000L),
                event(instagram, UsageEventKind.RESUMED, 1_000L),
                event(youtube, UsageEventKind.PAUSED, 5_000L),
            ),
            toWall = 10_000L,
        )
        assertEquals(
            listOf(
                UsageSpan(instagram, 1_000L, 3_000L),
                UsageSpan(youtube, 3_000L, 5_000L),
            ),
            spans,
        )
    }

    @Test
    fun aZeroLengthSessionIsDropped() {
        val spans = UsageSpans.fold(
            listOf(
                event(instagram, UsageEventKind.RESUMED, 1_000L),
                event(instagram, UsageEventKind.PAUSED, 1_000L),
            ),
            toWall = 10_000L,
        )
        assertTrue(spans.isEmpty())
    }

    @Test
    fun aSessionStillOpenAtTheEndIsDroppedWhenTheQueryEndsFirst() {
        // A resumed event after toWall cannot describe time the query covered.
        val spans = UsageSpans.fold(
            listOf(event(instagram, UsageEventKind.RESUMED, 9_000L)),
            toWall = 5_000L,
        )
        assertTrue(spans.isEmpty())
    }

    @Test
    fun aSpanIsClippedAtTheQueryEnd() {
        val spans = UsageSpans.fold(
            listOf(
                event(instagram, UsageEventKind.RESUMED, 1_000L),
                event(instagram, UsageEventKind.PAUSED, 9_000L),
            ),
            toWall = 5_000L,
        )
        assertEquals(listOf(UsageSpan(instagram, 1_000L, 5_000L)), spans)
    }

    @Test
    fun aSessionThatStartsAndEndsBeyondTheQueryIsClippedAway() {
        val spans = UsageSpans.fold(
            listOf(
                event(instagram, UsageEventKind.RESUMED, 7_000L),
                event(instagram, UsageEventKind.PAUSED, 9_000L),
            ),
            toWall = 5_000L,
        )
        assertTrue(spans.isEmpty())
    }

    @Test
    fun aShutdownClosesTheOpenSpan() {
        val spans = UsageSpans.fold(
            listOf(
                event(instagram, UsageEventKind.RESUMED, 1_000L),
                event(null, UsageEventKind.SHUTDOWN, 2_000L),
            ),
            toWall = 60_000L,
        )
        assertEquals(listOf(UsageSpan(instagram, 1_000L, 2_000L)), spans)
    }

    @Test
    fun aStoppedEventClosesTheOpenSpan() {
        val spans = UsageSpans.fold(
            listOf(
                event(instagram, UsageEventKind.RESUMED, 1_000L),
                event(instagram, UsageEventKind.STOPPED, 2_000L),
            ),
            toWall = 60_000L,
        )
        assertEquals(listOf(UsageSpan(instagram, 1_000L, 2_000L)), spans)
    }

    @Test
    fun anUnknownEventChangesNothing() {
        val spans = UsageSpans.fold(
            listOf(
                event(instagram, UsageEventKind.RESUMED, 1_000L),
                event(instagram, UsageEventKind.OTHER, 2_000L),
                event(instagram, UsageEventKind.PAUSED, 3_000L),
            ),
            toWall = 10_000L,
        )
        assertEquals(listOf(UsageSpan(instagram, 1_000L, 3_000L)), spans)
    }

    @Test
    fun minSpanDropsABlip() {
        val events = listOf(
            event(instagram, UsageEventKind.RESUMED, 1_000L),
            event(instagram, UsageEventKind.PAUSED, 1_200L),
            event(instagram, UsageEventKind.RESUMED, 5_000L),
            event(instagram, UsageEventKind.PAUSED, 9_000L),
        )
        assertEquals(
            listOf(UsageSpan(instagram, 5_000L, 9_000L)),
            UsageSpans.fold(events, toWall = 10_000L, minSpanMs = 1_000L),
        )
        // Zero keeps both: a blip is still real usage, it is only the caller's choice to ignore it.
        assertEquals(2, UsageSpans.fold(events, toWall = 10_000L).size)
    }

    @Test
    fun anEmptyStreamFoldsToNothing() {
        assertTrue(UsageSpans.fold(emptyList(), toWall = 10_000L).isEmpty())
    }

    private fun event(packageName: String?, kind: UsageEventKind, atWall: Long): UsageEvent =
        UsageEvent(packageName = packageName, kind = kind, wallMs = atWall)
}
