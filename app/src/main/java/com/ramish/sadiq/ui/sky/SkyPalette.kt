package com.ramish.sadiq.ui.sky

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ramish.sadiq.core.economy.StarKind

/**
 * How one kind of star looks: its colour, the radius of its core and, for the quiet nights, a
 * halo underneath it.
 *
 * A data class on purpose — the canvas buckets stars by style, so two kinds that look identical
 * (leave, kept word, done early) share one draw call instead of three.
 */
data class StarStyle(
    val color: Color,
    val radius: Dp,
    val glowDiameter: Dp = 0.dp,
    val glowAlpha: Float = 0f,
) {
    /** True when this style needs a halo pass before its core. */
    val hasGlow: Boolean get() = glowAlpha > 0f && glowDiameter > 0.dp
}

/**
 * The night sky: one palette for every theme, because the sky is always night (§4.5).
 *
 * The canvas, and the widget's bitmap renderer, read their colours and sizes from here so a star
 * looks the same whether it is drawn on the Sky screen or in the Glance widget.
 */
object SkyPalette {

    /** Sky base. Never themed, never lightened. */
    val skyBase = Color(0xFF0F1310)

    /** `#08503F` at 35 % alpha — the horizon glow over the bottom 30 %, the only gradient allowed. */
    val horizonGlow = Color(0x5908503F)

    /** Background dust: the ordinary star's colour, kept faint. Dust is scenery, not a star. */
    val dust = Color(0xFFE3E5DF)

    /** Constellation lines: `#8AD6BF` at 35 %. */
    val constellationLine = Color(0x598AD6BF)

    /** Leave, kept word, done early — the everyday star. */
    val ordinaryStar = StarStyle(color = Color(0xFFE3E5DF), radius = 1.6.dp)

    /** Focus and focus finish. */
    val focusStar = StarStyle(color = Color(0xFFFFFCF6), radius = 2.2.dp)

    /** Protected morning. */
    val morningStar = StarStyle(color = Color(0xFFCDEDE0), radius = 2.2.dp)

    /** Quiet night — the only gold in the app, with an 8 dp halo at 18 %. */
    val quietNightStar = StarStyle(color = Color(0xFFF1C04B), radius = 2.8.dp, glowDiameter = 8.dp, glowAlpha = 0.18f)

    /** The welcome star of onboarding. */
    val welcomeStar = StarStyle(color = Color(0xFF8AD6BF), radius = 2.6.dp)

    /** A kindling: a star that is being earned, drawn faint and never tappable until it is lit. */
    val kindlingStar = StarStyle(color = Color(0xFFE3E5DF), radius = 1.6.dp)

    /** Colour, radius and halo for a lit star of [kind]. */
    fun styleFor(kind: StarKind): StarStyle = when (kind) {
        StarKind.LEAVE, StarKind.KEPT_WORD, StarKind.DONE_EARLY -> ordinaryStar
        StarKind.FOCUS, StarKind.FOCUS_FINISH -> focusStar
        StarKind.PROTECTED_MORNING -> morningStar
        StarKind.QUIET_NIGHT -> quietNightStar
        StarKind.WELCOME -> welcomeStar
    }

    /**
     * Alpha of background dust mote [index], one of four steps between 12 % and 25 %.
     *
     * Quantised rather than continuous so the canvas can draw all 120 motes in four `drawPoints`
     * calls; the eye cannot tell four steps apart at this size.
     */
    fun dustAlpha(index: Int): Float {
        val spread = ((index * 2_654_435_761L) ushr 8) and 0x3
        return DUST_MIN_ALPHA + spread * ((DUST_MAX_ALPHA - DUST_MIN_ALPHA) / 3f)
    }

    const val DUST_COUNT = 120
    const val DUST_RADIUS_DP = 0.8f
    const val DUST_MIN_ALPHA = 0.12f
    const val DUST_MAX_ALPHA = 0.25f

    /** The glow starts at 70 % of the card's height and reaches full strength at the bottom. */
    const val HORIZON_START_FRACTION = 0.70f

    /** The disc always fits the card with 20 dp of air around it. */
    const val CAMERA_PADDING_DP = 20f

    /** A tap within 24 dp of a star's centre selects it — a finger, not a mouse. */
    const val TOUCH_RADIUS_DP = 24f

    const val CONSTELLATION_STROKE_DP = 0.75f

    const val KINDLING_ALPHA = 0.30f

    /** Twinkle runs between these alphas, never to zero: a star is never invisible. */
    const val TWINKLE_MIN_ALPHA = 0.75f
    const val MIN_TWINKLE_PERIOD_S = 3f
    const val MAX_TWINKLE_PERIOD_S = 5f

    /** A newly lit star fades in over this long, the next time the sky is shown. */
    const val FADE_IN_MS = 600
}
