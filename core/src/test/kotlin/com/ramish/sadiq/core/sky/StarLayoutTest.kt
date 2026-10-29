package com.ramish.sadiq.core.sky

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sky is a promise of permanence: these tests pin the two properties that keep it — the same
 * inputs always give the same point, and the disc stays readable (inside the circle, evenly spread,
 * barely moving as it grows).
 */
class StarLayoutTest {

    private val layout: StarLayout = VogelStarLayout()
    private val seed = 1_234_567_890_123L

    @Test
    fun positionIsDeterministicForTheSameInputs() {
        for (total in intArrayOf(1, 10, 500)) {
            for (index in 0 until total) {
                val first = layout.position(index, total, seed)
                val second = layout.position(index, total, seed)
                assertEquals(first.x, second.x, 0f)
                assertEquals(first.y, second.y, 0f)
            }
        }
        // A fresh layout is a fresh process: no state may survive between instances.
        assertEquals(VogelStarLayout().position(7, 500, seed), layout.position(7, 500, seed))
    }

    @Test
    fun aDifferentSeedMovesTheStars() {
        val total = 500
        val original = List(total) { layout.position(it, total, seed) }
        val reseeded = List(total) { layout.position(it, total, seed + 1) }
        val moved = original.indices.count { original[it] != reseeded[it] }
        assertTrue(moved > total * 4 / 5, "the seed should reach almost every star, moved=$moved")
    }

    @Test
    fun everyPointLiesInsideTheUnitDisc() {
        for (total in intArrayOf(1, 2, 3, 10, 500, 5000)) {
            for (index in 0 until total) {
                val point = layout.position(index, total, seed)
                val radius = sqrt(point.x * point.x + point.y * point.y)
                assertTrue(radius <= 1.0001f, "total=$total index=$index radius=$radius")
            }
        }
    }

    @Test
    fun minimumPairwiseDistanceIsAtLeastSixtyPercentOfTheMeanNearestNeighbour() {
        for (total in intArrayOf(10, 500, 5000)) {
            val points = List(total) { layout.position(it, total, seed) }
            val nearestSquared = nearestNeighbourSquared(points)
            var sum = 0.0
            var minSquared = Double.MAX_VALUE
            for (distanceSquared in nearestSquared) {
                sum += sqrt(distanceSquared)
                if (distanceSquared < minSquared) minSquared = distanceSquared
            }
            val mean = sum / total
            val min = sqrt(minSquared)
            assertTrue(min >= 0.6 * mean, "total=$total min=$min mean=$mean")
        }
    }

    @Test
    fun existingStarsBarelyMoveWhenTheSkyGrowsByOne() {
        // The prescribed r = sqrt((i + 8) / (N + 8)) law inherently shifts the outermost star by
        // 2.6 % at N = 10, while the disc is still filling in; from ~20 stars it is under 2 % and
        // from 50 under 1 %. The sky is only ever that small during onboarding.
        for (total in intArrayOf(50, 200, 500, 2000)) {
            for (index in 0 until total) {
                val before = layout.position(index, total, seed)
                val after = layout.position(index, total + 1, seed)
                val dx = (before.x - after.x).toDouble()
                val dy = (before.y - after.y).toDouble()
                val moved = sqrt(dx * dx + dy * dy)
                assertTrue(moved <= 0.02, "total=$total index=$index moved=$moved")
            }
        }
    }

    @Test
    fun newerStarsSitFurtherOutThanOlderOnes() {
        val total = 500
        val radii = List(total) { index ->
            val point = layout.position(index, total, seed)
            sqrt(point.x * point.x + point.y * point.y)
        }
        val oldest = radii.take(10).average()
        val newest = radii.takeLast(10).average()
        assertTrue(newest > oldest + 0.4, "oldest=$oldest newest=$newest")
    }

    @Test
    fun outOfRangeIndicesAreCoercedInsteadOfEscapingTheDisc() {
        assertEquals(layout.position(0, 10, seed), layout.position(-5, 10, seed))
        assertEquals(layout.position(9, 10, seed), layout.position(10, 10, seed))
        val onlyStar = layout.position(0, 0, seed)
        assertTrue(sqrt(onlyStar.x * onlyStar.x + onlyStar.y * onlyStar.y) <= 1f)
    }

    @Test
    fun dustIsDeterministicAndStaysInsideTheDisc() {
        val dust = layout.dust(120, seed)
        assertEquals(120, dust.size)
        assertEquals(dust, layout.dust(120, seed))
        assertTrue(dust != layout.dust(120, seed + 1), "dust must follow the seed too")
        for (point in dust) {
            assertTrue(sqrt(point.x * point.x + point.y * point.y) <= 1.0001f, "dust escaped the disc")
        }
        assertTrue(layout.dust(0, seed).isEmpty())
    }

    @Test
    fun projectionFitsTheDiscInsideTheViewportWithPadding() {
        assertEquals(360f, SkyProjection.discRadiusPx(1000f, 800f, 40f), 0.001f)
        assertEquals(130f, SkyProjection.discRadiusPx(300f, 500f, 20f), 0.001f)
        assertEquals(0f, SkyProjection.discRadiusPx(100f, 100f, 60f), 0.001f)
    }

    @Test
    fun projectionFindsTheNearestStarUnderTheFinger() {
        val stars = listOf(SkyPoint(0f, 0f), SkyPoint(0.5f, 0f), SkyPoint(-0.5f, 0f))
        assertEquals(1, SkyProjection.nearestStar(700f, 505f, 500f, 500f, 400f, stars, 24f))
        assertEquals(0, SkyProjection.nearestStar(503f, 497f, 500f, 500f, 400f, stars, 24f))
        assertEquals(2, SkyProjection.nearestStar(290f, 500f, 500f, 500f, 400f, stars, 24f))
        // Equidistant taps go to the lower index, so the tooltip never flickers between two stars.
        assertEquals(0, SkyProjection.nearestStar(500f, 500f, 500f, 500f, 400f, listOf(SkyPoint(0f, -0.25f), SkyPoint(0f, 0.25f)), 200f))
    }

    @Test
    fun projectionIgnoresTapsThatMissEveryStar() {
        val stars = listOf(SkyPoint(0f, 0f))
        assertNull(SkyProjection.nearestStar(900f, 900f, 500f, 500f, 400f, stars, 24f))
        assertNull(SkyProjection.nearestStar(500f, 500f, 500f, 500f, 400f, emptyList(), 24f))
        assertNull(SkyProjection.nearestStar(500f, 500f, 500f, 500f, 0f, stars, 24f))
    }

    /** Squared distance to the nearest other point, per point. */
    private fun nearestNeighbourSquared(points: List<SkyPoint>): DoubleArray {
        val nearest = DoubleArray(points.size) { Double.MAX_VALUE }
        for (i in points.indices) {
            val xi = points[i].x.toDouble()
            val yi = points[i].y.toDouble()
            for (j in i + 1 until points.size) {
                val dx = xi - points[j].x
                val dy = yi - points[j].y
                val distanceSquared = dx * dx + dy * dy
                if (distanceSquared < nearest[i]) nearest[i] = distanceSquared
                if (distanceSquared < nearest[j]) nearest[j] = distanceSquared
            }
        }
        return nearest
    }
}
