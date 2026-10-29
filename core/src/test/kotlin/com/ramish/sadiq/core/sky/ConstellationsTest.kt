package com.ramish.sadiq.core.sky

import com.ramish.sadiq.core.economy.StarKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Constellations are cumulative: seven quiet nights make one figure, it never breaks, and a star of
 * any other kind can never break it either.
 */
class ConstellationsTest {

    private val rule: ConstellationRule = QuietNightConstellations()

    @Test
    fun thereIsNoConstellationBeforeSevenQuietNights() {
        assertTrue(rule.groups(emptyList()).isEmpty())
        assertTrue(rule.groups(List(6) { star(it, StarKind.QUIET_NIGHT) }).isEmpty())
    }

    @Test
    fun sevenQuietNightsMakeOneConstellationInLitOrder() {
        val stars = List(7) { star(it, StarKind.QUIET_NIGHT) }
        assertEquals(listOf(listOf(0, 1, 2, 3, 4, 5, 6)), rule.groups(stars))
    }

    @Test
    fun onlyQuietNightsAreJoined() {
        val stars = List(14) { star(it, if (it % 2 == 0) StarKind.QUIET_NIGHT else StarKind.FOCUS) }
        assertEquals(listOf(listOf(0, 2, 4, 6, 8, 10, 12)), rule.groups(stars))
    }

    @Test
    fun otherKindsDoNotBreakAGroup() {
        val kinds = listOf(
            StarKind.QUIET_NIGHT, StarKind.LEAVE, StarKind.QUIET_NIGHT, StarKind.QUIET_NIGHT,
            StarKind.FOCUS, StarKind.QUIET_NIGHT, StarKind.QUIET_NIGHT, StarKind.QUIET_NIGHT,
            StarKind.QUIET_NIGHT, StarKind.WELCOME,
        )
        val stars = kinds.mapIndexed { index, kind -> star(index, kind) }
        assertEquals(listOf(listOf(0, 2, 3, 5, 6, 7, 8)), rule.groups(stars))
    }

    @Test
    fun fourteenQuietNightsMakeTwoConstellations() {
        val stars = List(14) { star(it, StarKind.QUIET_NIGHT) }
        assertEquals(
            listOf(listOf(0, 1, 2, 3, 4, 5, 6), listOf(7, 8, 9, 10, 11, 12, 13)),
            rule.groups(stars),
        )
    }

    @Test
    fun aTrailingPartialGroupWaitsForItsSeventhStar() {
        val stars = List(8) { star(it, StarKind.QUIET_NIGHT) }
        assertEquals(listOf(listOf(0, 1, 2, 3, 4, 5, 6)), rule.groups(stars))
        assertEquals(2, rule.groups(List(14) { star(it, StarKind.QUIET_NIGHT) }).size)
    }

    @Test
    fun groupsFollowLitOrderEvenWhenTheListIsNotSorted() {
        // Lit order is 6, 5, 4, 3, 2, 1, 0 while the list order is 0..6.
        val stars = List(7) { index ->
            star(index = index, kind = StarKind.QUIET_NIGHT, litWall = (6 - index) * 60_000L)
        }
        assertEquals(listOf(listOf(6, 5, 4, 3, 2, 1, 0)), rule.groups(stars))
    }

    @Test
    fun groupsAreStarIndicesEvenWhenTheListIsAFilteredOne() {
        // A week's slice of a bigger sky: positions 0..6, but the stars' own row ids are 7..13.
        val wholeSky = List(21) { star(it, StarKind.QUIET_NIGHT) }
        val thisWeek = wholeSky.subList(7, 14)
        assertEquals(listOf(listOf(7, 8, 9, 10, 11, 12, 13)), rule.groups(thisWeek))
        // Ids that are not list positions must still come back as ids.
        val oddIds = List(7) { star(index = 100 + it, kind = StarKind.QUIET_NIGHT) }
        assertEquals(listOf(listOf(100, 101, 102, 103, 104, 105, 106)), rule.groups(oddIds))
    }

    private fun star(index: Int, kind: StarKind, litWall: Long = index * 60_000L): SkyStar =
        SkyStar(index = index, kind = kind, litWall = litWall, logicalDay = 20_000, sourcePackage = null)
}
