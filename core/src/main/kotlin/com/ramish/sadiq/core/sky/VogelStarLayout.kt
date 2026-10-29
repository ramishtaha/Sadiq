package com.ramish.sadiq.core.sky

// CONTRACT-GAP: §5.1 has no unit-disc → pixel camera, which the canvas needs to draw and to hit
// test. `SkyProjection` (bottom of this file) is the smallest local helper: two pure functions,
// no state, also usable by the widget's bitmap renderer.

import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Deterministic Vogel (golden-angle) placement of the sky.
 *
 * Star [index] of [total] sits at `r = sqrt((index + 8) / (total + 8))` and
 * `theta = index * 137.508°`, so the disc fills from the centre outwards and the newest star is
 * always the outermost one. Both the radius and the angle carry a small jitter derived only from
 * `(index, seed)`: the same star keeps its place across launches, recompositions and process
 * deaths, which is the one thing the sky must never get wrong.
 *
 * Radii are scaled by `max(0.6, 1 - 0.08 * log10(total + 1))`. Without the falloff an accumulating
 * sky would crowd the rim of a card that cannot grow; with it the disc stays legible at 2 000
 * stars and still fits at 5 000.
 *
 * The maths is integer/`Double` only — no clock, no `Random`, no iteration order — because a star
 * that moved between two launches would read as a bug in a screen whose whole point is permanence.
 */
class VogelStarLayout : StarLayout {

    /**
     * The position of star [index] in a sky of [total] stars, inside the unit disc.
     *
     * [index] outside `0 until total` is coerced into range and `total <= 0` is treated as one
     * star, so a malformed call can never push a point outside the disc.
     */
    override fun position(index: Int, total: Int, seed: Long): SkyPoint {
        val n = max(total, 1)
        val i = index.coerceIn(0, n - 1)

        val radiusScale = max(MIN_RADIUS_SCALE, 1.0 - LOG_RADIUS_FALLOFF * log10((n + 1).toDouble()))
        val baseRadius = sqrt((i + CENTRE_BIAS) / (n + CENTRE_BIAS))
        val localSpacing = 1.0 / sqrt(n + CENTRE_BIAS)

        // Two bounds on the angular jitter. The plan's ±8° is the hard ceiling, but a fixed 8° at
        // r ≈ 0.7 sweeps farther than the gap between neighbours once the sky holds a few hundred
        // stars: the closest pair then sits at ~0.30 of the mean nearest-neighbour distance
        // (measured across seeds), failing this package's own distance acceptance. Capping the
        // jitter to a small fraction of the local spacing keeps the irregularity visible only
        // where there is room for it — at small skies — and leaves large skies quasi-uniform.
        val angleJitter = min(MAX_ANGLE_JITTER, ANGLE_JITTER_FRACTION * localSpacing / baseRadius)
        val angle = i * GOLDEN_ANGLE_RAD + signedUnit(i, seed, SALT_ANGLE) * angleJitter

        // ±0.35 of the local spacing radially: enough to break the spiral's regularity, small
        // enough that a star cannot jump past the ring around it.
        val radialJitter = signedUnit(i, seed, SALT_RADIUS) * RADIAL_JITTER_FRACTION * localSpacing
        val radius = (baseRadius + radialJitter) * radiusScale

        val clamped = radius.coerceIn(0.0, 1.0)
        return SkyPoint(
            x = (clamped * cos(angle)).toFloat(),
            y = (clamped * sin(angle)).toFloat(),
        )
    }

    /**
     * [count] background dust motes, deterministic for a given [seed] and spread evenly over the
     * whole disc (uniform in area, not in radius). Dust is scenery, never a star: it is not
     * counted, not tappable and not stored.
     */
    override fun dust(count: Int, seed: Long): List<SkyPoint> {
        if (count <= 0) return emptyList()
        return List(count) { i ->
            val radius = sqrt(unit(i, seed, SALT_DUST_RADIUS))
            val angle = unit(i, seed, SALT_DUST_ANGLE) * TWO_PI
            SkyPoint(
                x = (radius * cos(angle)).toFloat(),
                y = (radius * sin(angle)).toFloat(),
            )
        }
    }
}

/**
 * Maps the unit disc onto a viewport and back.
 *
 * This is the camera the canvas (and later the widget's bitmap) draws through: the disc is centred
 * in the viewport and fits inside it with [discRadiusPx] of padding, so a star at radius 1 lands
 * exactly on the padded edge. Pure float maths so it can be unit-tested without Compose.
 */
object SkyProjection {

    /** Radius in pixels of the disc that fits inside a [widthPx] x [heightPx] viewport with [paddingPx]. */
    fun discRadiusPx(widthPx: Float, heightPx: Float, paddingPx: Float): Float {
        val half = min(widthPx, heightPx) / 2f
        return max(0f, half - paddingPx)
    }

    /**
     * Index of the star nearest to a tap, or null when the nearest one is farther than
     * [touchRadiusPx] (or there is nothing to hit).
     *
     * [stars] are unit-disc points; [centerX]/[centerY] and [discRadiusPx] are the camera. Ties go
     * to the lower index so the result never depends on iteration quirks.
     */
    fun nearestStar(
        tapX: Float,
        tapY: Float,
        centerX: Float,
        centerY: Float,
        discRadiusPx: Float,
        stars: List<SkyPoint>,
        touchRadiusPx: Float,
    ): Int? {
        if (stars.isEmpty() || discRadiusPx <= 0f || touchRadiusPx <= 0f) return null
        val maxDistanceSq = touchRadiusPx * touchRadiusPx
        var bestDistanceSq = Float.POSITIVE_INFINITY
        var best = -1
        for (i in stars.indices) {
            val dx = centerX + stars[i].x * discRadiusPx - tapX
            val dy = centerY + stars[i].y * discRadiusPx - tapY
            val distanceSq = dx * dx + dy * dy
            if (distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq
                best = i
            }
        }
        return if (best >= 0 && bestDistanceSq <= maxDistanceSq) best else null
    }
}

private const val CENTRE_BIAS = 8.0

/** `max(0.6, 1 - 0.08 * log10(N + 1))` — the rim falloff of §4.5. */
private const val LOG_RADIUS_FALLOFF = 0.08
private const val MIN_RADIUS_SCALE = 0.6

/** 137.508° in radians. */
private const val GOLDEN_ANGLE_RAD = 2.39996735

/** The plan's ±8° ceiling on the angular jitter, in radians. */
private const val MAX_ANGLE_JITTER = 0.13962634

/**
 * The angular jitter actually used, as a fraction of the local spacing at the star's radius.
 * See [VogelStarLayout.position] for why the ±8° ceiling alone is not safe.
 */
private const val ANGLE_JITTER_FRACTION = 0.12

/** Radial jitter as a fraction of the local spacing, from §4.5. */
private const val RADIAL_JITTER_FRACTION = 0.35

private const val TWO_PI = 6.2831855

/** SplitMix64's Weyl increment, as a signed Long (`0x9E3779B97F4A7C15`). */
private const val GOLDEN_GAMMA = -7046029254386353131L

/** SplitMix64 mixing constants (`0xBF58476D1CE4E5B9`, `0x94D049BB133111EB`). */
private const val MIX_MULTIPLIER_1 = -4658895280553007687L
private const val MIX_MULTIPLIER_2 = -7723592293110705685L

/** Salts keep the radius, angle and dust streams independent of each other. */
private const val SALT_ANGLE = 0x1111111111111111L
private const val SALT_RADIUS = 0x2222222222222222L
private const val SALT_DUST_RADIUS = 0x3333333333333333L
private const val SALT_DUST_ANGLE = 0x4444444444444444L

/** Integer-only hash: stable across devices, JVMs and process restarts. */
private fun mix64(value: Long): Long {
    var z = value + GOLDEN_GAMMA
    z = (z xor (z ushr 30)) * MIX_MULTIPLIER_1
    z = (z xor (z ushr 27)) * MIX_MULTIPLIER_2
    return z xor (z ushr 31)
}

/** A stable value in `[0, 1)` for `(index, seed, salt)`. */
private fun unit(index: Int, seed: Long, salt: Long): Double {
    val bits = mix64(seed xor (index.toLong() * GOLDEN_GAMMA) xor salt) ushr 40
    return bits.toDouble() / 16_777_216.0
}

/** A stable value in `[-1, 1]` for `(index, seed, salt)`. */
private fun signedUnit(index: Int, seed: Long, salt: Long): Double = unit(index, seed, salt) * 2.0 - 1.0
