package com.ramish.sadiq.core.insight

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The reflection rule of §3.6: six opens in twenty minutes, at most once an hour, never when it is off.
 *
 * The clock is an argument, not a service, so every case pins an exact wall time and the whole file is
 * replayable.
 */
class ReflectionPolicyTest {

    private val policy: ReflectionPolicy = DefaultReflectionPolicy()

    @Test
    fun sixOpensInTwentyMinutesReflects() {
        val now = T0 + 20 * MIN
        val opens = listOf(T0, T0 + 4 * MIN, T0 + 8 * MIN, T0 + 12 * MIN, T0 + 16 * MIN, now)

        assertEquals(6, policy.opensInWindow(opens, now))
        assertTrue(policy.shouldReflect(opens, null, now, enabled = true))
    }

    @Test
    fun fiveOpensInTwentyMinutesDoesNotReflect() {
        val now = T0 + 20 * MIN
        val opens = listOf(T0, T0 + 4 * MIN, T0 + 8 * MIN, T0 + 12 * MIN, T0 + 16 * MIN)

        assertEquals(5, policy.opensInWindow(opens, now))
        assertFalse(policy.shouldReflect(opens, null, now, enabled = true))
    }

    @Test
    fun anOpenExactlyTwentyMinutesOldStillCounts() {
        val now = T0 + 20 * MIN

        assertEquals(1, policy.opensInWindow(listOf(now - 20 * MIN), now))
    }

    @Test
    fun anOpenOlderThanTheWindowIsIgnored() {
        val now = T0 + 20 * MIN

        assertEquals(0, policy.opensInWindow(listOf(now - 20 * MIN - 1), now))
    }

    @Test
    fun opensStampedAfterNowAreIgnored() {
        val now = T0 + 20 * MIN
        val opens = listOf(now - MIN, now + MIN, now + 5 * MIN)

        assertEquals(1, policy.opensInWindow(opens, now))
        assertFalse(policy.shouldReflect(opens, null, now, enabled = true))
    }

    @Test
    fun opensOutsideTheWindowAreNotCounted() {
        val now = T0 + 30 * MIN
        val old = listOf(now - 25 * MIN, now - 22 * MIN, now - 21 * MIN)
        val recent = listOf(now - 10 * MIN, now - 6 * MIN, now - 2 * MIN, now)

        assertEquals(0, policy.opensInWindow(old, now))
        assertEquals(4, policy.opensInWindow(old + recent, now))
    }

    @Test
    fun withinSixtyMinutesOfTheLastReflectionDoesNotReflect() {
        val now = T0 + 20 * MIN
        val opens = sixOpensEndingAt(now)

        assertFalse(policy.shouldReflect(opens, lastReflectWall = now - 59 * MIN, nowWall = now, enabled = true))
    }

    @Test
    fun theCooldownEndsExactlyAtSixtyMinutes() {
        val now = T0 + 20 * MIN
        val opens = sixOpensEndingAt(now)

        assertTrue(policy.shouldReflect(opens, lastReflectWall = now - 60 * MIN, nowWall = now, enabled = true))
    }

    @Test
    fun aReflectionStampInTheFutureBlocks() {
        val now = T0 + 20 * MIN
        val opens = sixOpensEndingAt(now)

        // A wall clock that moved backwards says nothing about how long he has been quiet.
        assertFalse(policy.shouldReflect(opens, lastReflectWall = now + MIN, nowWall = now, enabled = true))
    }

    @Test
    fun disabledNeverReflects() {
        val now = T0 + 20 * MIN
        val opens = List(10) { index -> now - index * MIN }

        assertFalse(policy.shouldReflect(opens, lastReflectWall = null, nowWall = now, enabled = false))
        assertFalse(policy.shouldReflect(opens, lastReflectWall = now - 2 * HOUR, nowWall = now, enabled = false))
    }

    @Test
    fun anEmptyHistoryDoesNotReflect() {
        assertEquals(0, policy.opensInWindow(emptyList(), T0))
        assertFalse(policy.shouldReflect(emptyList(), lastReflectWall = null, nowWall = T0, enabled = true))
    }

    /** Six opens spread over the twenty minutes ending exactly at [now]. */
    private fun sixOpensEndingAt(now: Long): List<Long> =
        listOf(now - 20 * MIN, now - 16 * MIN, now - 12 * MIN, now - 8 * MIN, now - 4 * MIN, now)

    private companion object {
        const val T0: Long = 1_790_000_000_000L
        const val MIN: Long = 60_000L
        const val HOUR: Long = 60 * MIN
    }
}
