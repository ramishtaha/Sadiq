package com.ramish.sadiq.notify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The exact strings §4.6 puts in the shade and in the status-bar chip. The formats are language
 * invariant, which is why they live in [LiveText] and are pinned here rather than in a screenshot.
 */
class LiveTextTest {

    @Test
    fun clockShowsMinutesAndSeconds() {
        assertEquals("3:12", LiveText.clock(192_000L))
    }

    @Test
    fun clockPadsSecondsAndKeepsWholeMinutes() {
        assertEquals("0:07", LiveText.clock(7_000L))
        assertEquals("5:00", LiveText.clock(300_000L))
    }

    @Test
    fun clockAddsHoursOnlyWhenThereAreHours() {
        assertEquals("59:00", LiveText.clock(3_540_000L))
        assertEquals("1:02:05", LiveText.clock(3_725_000L))
    }

    @Test
    fun clockNeverShowsANegativeNumber() {
        assertEquals("0:00", LiveText.clock(-1L))
    }

    @Test
    fun shortCriticalTextRoundsMinutesUp() {
        assertEquals("4m", LiveText.shortRemaining(192_000L))
        assertEquals("1m", LiveText.shortRemaining(1L))
        assertEquals("0m", LiveText.shortRemaining(0L))
        assertEquals("5m", LiveText.shortRemaining(300_000L))
    }

    @Test
    fun grantTitleIsAppThenMinutes() {
        assertEquals("Instagram · 5 min", LiveText.grantTitle("Instagram", 300_000L))
        assertEquals("YouTube · 1 min", LiveText.grantTitle("YouTube", 60_000L))
    }

    @Test
    fun focusTitleIsFocusThenMinutes() {
        assertEquals("Focus · 25 min", LiveText.focusTitle(1_500_000L))
    }

    @Test
    fun heldTitleMatchesTheSpec() {
        assertEquals("3:12 left · paused", LiveText.heldTitle(192_000L))
    }

    @Test
    fun pausedTitleSaysSavedForLater() {
        assertEquals("3:12 saved for later", LiveText.savedTitle(192_000L))
    }

    @Test
    fun starsLineIsPluralised() {
        assertEquals("+5 stars in your sky", LiveText.starsLine(5))
        assertEquals("+1 star in your sky", LiveText.starsLine(1))
    }

    /**
     * A capped session (the focus cap or the daily cap) lights no star, and §2.2 says Sadiq does not
     * put a count in front of him that he did not earn. The line has no zero reading at all: the
     * notifier drops the post instead of printing "+0".
     */
    @Test
    fun starsLineNeverSaysZeroStars() {
        assertNull(LiveText.starsLine(0))
        assertNull(LiveText.starsLine(-3))
        assertEquals("+1 star in your sky", LiveText.starsLine(1))
    }

    @Test
    fun progressStartsEmptyFillsToHalfAndEndsFull() {
        assertEquals(0, LiveText.progressPercent(0L, 300_000L))
        assertEquals(50, LiveText.progressPercent(150_000L, 300_000L))
        assertEquals(100, LiveText.progressPercent(300_000L, 300_000L))
    }

    @Test
    fun progressClampsAndNeverDividesByZero() {
        assertEquals(0, LiveText.progressPercent(-5_000L, 300_000L))
        assertEquals(100, LiveText.progressPercent(999_000L, 300_000L))
        assertEquals(0, LiveText.progressPercent(1_000L, 0L))
    }

    @Test
    fun guardedLineUsesTheCountWhenThereIsOne() {
        assertEquals("Guarding 5 apps", LiveText.guardedLine(5))
        assertEquals("Guarding 1 app", LiveText.guardedLine(1))
        assertEquals("Guarding your apps", LiveText.guardedLine(null))
    }
}
