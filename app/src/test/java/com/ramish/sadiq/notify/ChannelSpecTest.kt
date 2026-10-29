package com.ramish.sadiq.notify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The channel table from SPEC-UX §4.6, held still. These are the only notifications Sadiq can post, so
 * a change here is a product decision, not a refactor.
 */
class ChannelSpecTest {

    @Test
    fun channelsAreExactlyTheThreeTheSpecNames() {
        assertEquals(
            listOf("guard_status", "session_live", "attention"),
            Channels.all.map { it.id }
        )
        assertEquals(3, Channels.all.map { it.id }.distinct().size)
    }

    @Test
    fun importanceMatchesTheTable() {
        assertEquals(ChannelImportance.MIN, Channels.guardStatus.importance)
        assertEquals(ChannelImportance.LOW, Channels.sessionLive.importance)
        assertEquals(ChannelImportance.DEFAULT, Channels.attention.importance)
    }

    @Test
    fun noChannelMakesASoundAVibrationOrABadge() {
        Channels.all.forEach { spec ->
            assertFalse(spec.playSound, "${spec.id} must stay silent")
            assertFalse(spec.vibrate, "${spec.id} must not vibrate")
            assertFalse(spec.showBadge, "${spec.id} must never badge: a badge is one more thing to check")
        }
    }

    @Test
    fun everyChannelHasItsOwnNameAndADescription() {
        val specs = Channels.all
        val names = specs.map { it.nameRes }
        val descriptions = specs.map { it.descriptionRes }

        assertEquals(specs.size, names.distinct().size, "channel names must not be shared")
        assertTrue(names.none { it == 0 }, "every channel needs a display name")
        assertTrue(descriptions.none { it == 0 }, "every channel needs a description")
        assertEquals(specs.size, descriptions.distinct().size, "descriptions must not be shared")
    }
}
