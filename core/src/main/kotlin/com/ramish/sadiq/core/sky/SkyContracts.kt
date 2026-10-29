package com.ramish.sadiq.core.sky

import com.ramish.sadiq.core.economy.StarKind

/**
 * A lit star, as the sky draws it. `index` is the row id, and therefore the drawing order: stars are
 * never renumbered, so a star never moves once it has lit (§3.8).
 *
 * @property index the star's position in the sky, oldest first.
 * @property kind what it records.
 * @property litWall wall time it lit.
 * @property logicalDay the logical day it belongs to.
 * @property sourcePackage the app it came from, for the tooltip.
 */
data class SkyStar(
    val index: Int,
    val kind: StarKind,
    val litWall: Long,
    val logicalDay: Int,
    val sourcePackage: String?
)

/**
 * A point in the sky's unit disc, centre (0,0), radius 1.
 *
 * @property x horizontal position, −1…1.
 * @property y vertical position, −1…1.
 */
data class SkyPoint(
    val x: Float,
    val y: Float
)

/**
 * Deterministic star placement. The same `(index, total, seed)` always yields the same point, so the
 * sky looks identical after a restart and grows without the old stars jumping.
 */
interface StarLayout {
    /**
     * Where star [index] of [total] sits.
     *
     * @param index the star's position, oldest first.
     * @param total how many stars the sky holds.
     * @param seed the install's sky seed.
     */
    fun position(index: Int, total: Int, seed: Long): SkyPoint

    /**
     * Background dust, drawn faintly behind the lit stars.
     *
     * @param count how many dust points.
     * @param seed the install's sky seed.
     */
    fun dust(count: Int, seed: Long): List<SkyPoint>
}

/** Groups quiet-night stars into constellations. Nothing ever breaks a group once it is formed. */
interface ConstellationRule {
    /** @param stars every lit star, oldest first. @return groups of star indices. */
    fun groups(stars: List<SkyStar>): List<List<Int>>
}
