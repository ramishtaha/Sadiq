package com.ramish.sadiq.core.sky

import com.ramish.sadiq.core.economy.StarKind

/**
 * Constellations: every seven quiet-night stars are joined into one faint figure.
 *
 * Quiet nights are cumulative — a bad night never removes a line, and other kinds of star never
 * break a group. Stars are taken in lit order, so a group's shape is a record of how those seven
 * nights followed one another, and it never changes once the seventh star is lit. A trailing group
 * with fewer than seven stars has no figure yet, so it is not returned: the shape appears exactly
 * when the sky has earned it.
 *
 * Each `Int` is a [SkyStar.index] — the star's row id, which the contract defines as its place in
 * the sky — not a position in the list argument. The two agree for a whole sky, but a filtered list
 * would otherwise draw the figure through the wrong stars.
 */
class QuietNightConstellations : ConstellationRule {

    override fun groups(stars: List<SkyStar>): List<List<Int>> {
        if (stars.isEmpty()) return emptyList()
        val quiet = ArrayList<Int>(stars.size / 4)
        for (position in stars.indices) {
            if (stars[position].kind == StarKind.QUIET_NIGHT) quiet.add(position)
        }
        if (quiet.size < GROUP_SIZE) return emptyList()

        // Ordered by the star's own lit time first and its stable index second, so the figure is
        // the same whether the caller happened to pass the sky sorted or not.
        val litOrder = quiet.sortedWith(
            compareBy<Int> { stars[it].litWall }
                .thenBy { stars[it].index }
                .thenBy { it },
        )
        return litOrder
            .chunked(GROUP_SIZE)
            .filter { it.size == GROUP_SIZE }
            .map { group -> group.map { stars[it].index } }
    }

    companion object {
        /** Stars per constellation, from §3.8. */
        const val GROUP_SIZE = 7
    }
}
