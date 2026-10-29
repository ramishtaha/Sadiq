package com.ramish.sadiq.ui.sky

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ramish.sadiq.core.economy.StarKind
import com.ramish.sadiq.core.sky.QuietNightConstellations
import com.ramish.sadiq.core.sky.SkyPoint
import com.ramish.sadiq.core.sky.SkyProjection
import com.ramish.sadiq.core.sky.SkyStar
import com.ramish.sadiq.core.sky.VogelStarLayout
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin

/**
 * The night sky of §4.5: a dark disc of stars that only ever grows.
 *
 * The composable owns no state of its own beyond animation — [stars] arrives in lit order and the
 * layout is a pure function of `(index, total, seed)`, so the sky looks the same after a restart,
 * a rotation or a theme change.
 *
 * **Drawing cost** is this screen's only real risk, so the draw is grouped:
 * - positions are computed once per `(stars, kindling, seed)`, never per frame;
 * - every star that is not moving goes into one `drawPoints` per [StarStyle] — five calls cover
 *   five thousand stars — with a single extra halo pass for the one glowing style;
 * - only today's stars, and any star that has not been shown yet, are drawn one at a time,
 *   because their alpha moves, and the daily caps keep that set in single digits;
 * - constellation segments are collected into one [Path] and stroked in a single call;
 * - dust is quantised to four alphas, so it costs four calls in total.
 * The twinkle clock and the fade are read in the draw phase, so neither recomposes the screen.
 *
 * Taps select the nearest star within 24 dp and report [SkyStar.index]; [kindling] stars are never
 * tappable, because there is nothing to say about a star that does not exist yet.
 *
 * @param stars the lit sky, in lit order (oldest first).
 * @param kindling how many stars are being earned right now, drawn faint at the rim.
 * @param seed the install's permanent `sky_seed`; the same seed always draws the same sky.
 * @param todayDay the logical day whose stars twinkle.
 * @param reduceMotion the app-wide preference: no twinkle, no fade, no clock.
 * @param onStarTapped called with [SkyStar.index] of the star under the finger.
 */
@Composable
fun StarfieldCanvas(
    stars: List<SkyStar>,
    kindling: Int,
    seed: Long,
    todayDay: Int,
    reduceMotion: Boolean,
    onStarTapped: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val layout = remember { VogelStarLayout() }
    val constellationRule = remember { QuietNightConstellations() }
    val pending = kindling.coerceAtLeast(0)
    val total = stars.size + pending

    val unitPoints = remember(stars, pending, seed, layout) {
        List(total) { index -> layout.position(index, total, seed) }
    }
    val litPoints = remember(unitPoints, stars) { unitPoints.take(stars.size) }
    val constellationGroups = remember(stars, constellationRule) { constellationRule.groups(stars) }
    val dust = remember(layout, seed) { layout.dust(SkyPalette.DUST_COUNT, seed) }
    val hasTodaysStars = remember(stars, todayDay) { stars.any { it.logicalDay == todayDay } }

    // "A newly lit star fades in over 600 ms the next time the sky is shown": the wall time the
    // canvas was last on screen is saved with the nav entry, so leaving and coming back is what
    // plays the fade — not a recomposition.
    var lastShownWall by rememberSaveable { mutableStateOf(0L) }
    DisposableEffect(Unit) {
        onDispose { lastShownWall = System.currentTimeMillis() }
    }
    val freshStars = remember(stars, lastShownWall) {
        if (lastShownWall <= 0L) {
            BooleanArray(stars.size)
        } else {
            BooleanArray(stars.size) { stars[it].litWall > lastShownWall }
        }
    }
    val freshCount = remember(freshStars) { freshStars.count { it } }

    val fade = remember { Animatable(1f) }
    LaunchedEffect(freshCount, reduceMotion) {
        if (reduceMotion || freshCount == 0) {
            fade.snapTo(1f)
        } else {
            fade.snapTo(0f)
            fade.animateTo(1f, tween(durationMillis = SkyPalette.FADE_IN_MS, easing = LinearEasing))
        }
    }

    // One clock for every twinkling star. Nothing recomposes while it runs: the value is only read
    // inside onDrawBehind, which invalidates the draw phase alone.
    val clockNanos = remember { mutableLongStateOf(0L) }
    LaunchedEffect(reduceMotion, hasTodaysStars) {
        if (reduceMotion || !hasTodaysStars) return@LaunchedEffect
        var startNanos = -1L
        while (true) {
            withFrameNanos { frameNanos ->
                if (startNanos < 0L) startNanos = frameNanos
                clockNanos.longValue = frameNanos - startNanos
            }
        }
    }

    val currentOnStarTapped by rememberUpdatedState(onStarTapped)

    // A Spacer with drawing modifiers rather than a Canvas: the sky's geometry is cached in
    // drawWithCache, which Canvas's plain onDraw lambda cannot do.
    Spacer(
        modifier = modifier
            .pointerInput(stars, litPoints) {
                detectTapGestures { tap ->
                    val discRadiusPx = SkyProjection.discRadiusPx(
                        widthPx = size.width.toFloat(),
                        heightPx = size.height.toFloat(),
                        paddingPx = SkyPalette.CAMERA_PADDING_DP.dp.toPx(),
                    )
                    val hit = SkyProjection.nearestStar(
                        tapX = tap.x,
                        tapY = tap.y,
                        centerX = size.width / 2f,
                        centerY = size.height / 2f,
                        discRadiusPx = discRadiusPx,
                        stars = litPoints,
                        touchRadiusPx = SkyPalette.TOUCH_RADIUS_DP.dp.toPx(),
                    )
                    if (hit != null) currentOnStarTapped(stars[hit].index)
                }
            }
            .drawWithCache {
                val sky = buildSkyGeometry(
                    stars = stars,
                    unitPoints = unitPoints,
                    kindling = pending,
                    todayDay = todayDay,
                    constellationGroups = constellationGroups,
                    freshStars = freshStars,
                    dust = dust,
                    size = size,
                    paddingPx = SkyPalette.CAMERA_PADDING_DP.dp.toPx(),
                )
                val dustStrokePx = SkyPalette.DUST_RADIUS_DP.dp.toPx() * 2f
                val constellationStrokePx = SkyPalette.CONSTELLATION_STROKE_DP.dp.toPx()
                onDrawBehind {
                    drawRect(SkyPalette.skyBase)

                    val horizonTop = size.height * SkyPalette.HORIZON_START_FRACTION
                    drawRect(
                        brush = Brush.verticalGradient(listOf(Color.Transparent, SkyPalette.horizonGlow)),
                        topLeft = Offset(0f, horizonTop),
                        size = Size(size.width, size.height - horizonTop),
                    )

                    for (batch in sky.dust) {
                        drawPoints(
                            points = batch.points,
                            pointMode = PointMode.Points,
                            color = SkyPalette.dust,
                            strokeWidth = dustStrokePx,
                            cap = StrokeCap.Round,
                            alpha = batch.alpha,
                        )
                    }

                    sky.constellationPath?.let { path ->
                        drawPath(
                            path = path,
                            color = SkyPalette.constellationLine,
                            style = Stroke(width = constellationStrokePx),
                        )
                    }

                    // Haloes first, so a gold star's glow sits under every core, not just its own.
                    for (batch in sky.staticStars) {
                        if (batch.style.hasGlow) {
                            drawPoints(
                                points = batch.points,
                                pointMode = PointMode.Points,
                                color = batch.style.color,
                                strokeWidth = batch.style.glowDiameter.toPx(),
                                cap = StrokeCap.Round,
                                alpha = batch.style.glowAlpha,
                            )
                        }
                    }
                    for (batch in sky.staticStars) {
                        drawPoints(
                            points = batch.points,
                            pointMode = PointMode.Points,
                            color = batch.style.color,
                            strokeWidth = batch.style.radius.toPx() * 2f,
                            cap = StrokeCap.Round,
                        )
                    }

                    if (sky.kindling.points.isNotEmpty()) {
                        drawPoints(
                            points = sky.kindling.points,
                            pointMode = PointMode.Points,
                            color = SkyPalette.kindlingStar.color,
                            strokeWidth = SkyPalette.kindlingStar.radius.toPx() * 2f,
                            cap = StrokeCap.Round,
                            alpha = SkyPalette.KINDLING_ALPHA,
                        )
                    }

                    val fadeAlpha = if (reduceMotion) 1f else fade.value
                    val seconds = clockNanos.longValue / NANOS_PER_SECOND
                    for (star in sky.animatedStars) {
                        val twinkle = if (!star.twinklesToday || reduceMotion) {
                            1f
                        } else {
                            twinkleAlpha(seconds, star.periodSeconds, star.phase)
                        }
                        val alpha = twinkle * if (star.fadesIn) fadeAlpha else 1f
                        if (star.style.hasGlow) {
                            drawCircle(
                                color = star.style.color,
                                radius = star.style.glowDiameter.toPx() / 2f,
                                center = star.center,
                                alpha = alpha * star.style.glowAlpha,
                            )
                        }
                        drawCircle(
                            color = star.style.color,
                            radius = star.style.radius.toPx(),
                            center = star.center,
                            alpha = alpha,
                        )
                    }
                }
            },
    )
}

/** Everything the draw phase needs, all of it in pixels and all of it built once per size change. */
private class SkyGeometry(
    val staticStars: List<StarBatch>,
    val animatedStars: List<AnimatedStar>,
    val kindling: StarBatch,
    val constellationPath: Path?,
    val dust: List<DustBatch>,
)

/** One `drawPoints` batch: all the plain stars that share a [StarStyle]. */
private class StarBatch(val style: StarStyle, val points: List<Offset>)

/** A star whose alpha moves — today's, or one that has not been shown yet — so it is drawn alone. */
private class AnimatedStar(
    val style: StarStyle,
    val center: Offset,
    val twinklesToday: Boolean,
    val periodSeconds: Float,
    val phase: Float,
    val fadesIn: Boolean,
)

/** Dust of one alpha step; four of these cover all 120 motes. */
private class DustBatch(val alpha: Float, val points: List<Offset>)

private fun buildSkyGeometry(
    stars: List<SkyStar>,
    unitPoints: List<SkyPoint>,
    kindling: Int,
    todayDay: Int,
    constellationGroups: List<List<Int>>,
    freshStars: BooleanArray,
    dust: List<SkyPoint>,
    size: Size,
    paddingPx: Float,
): SkyGeometry {
    val discRadius = SkyProjection.discRadiusPx(size.width, size.height, paddingPx)
    val centerX = size.width / 2f
    val centerY = size.height / 2f

    fun screen(point: SkyPoint): Offset =
        Offset(centerX + point.x * discRadius, centerY + point.y * discRadius)

    val batches = LinkedHashMap<StarStyle, MutableList<Offset>>()
    val animated = ArrayList<AnimatedStar>()
    for (i in stars.indices) {
        val star = stars[i]
        val style = SkyPalette.styleFor(star.kind)
        val center = screen(unitPoints[i])
        val isToday = star.logicalDay == todayDay
        val isFresh = freshStars.getOrElse(i) { false }
        if (isToday || isFresh) {
            animated.add(
                AnimatedStar(
                    style = style,
                    center = center,
                    twinklesToday = isToday,
                    periodSeconds = twinklePeriodSeconds(star.index),
                    phase = twinklePhase(star.index),
                    fadesIn = isFresh,
                ),
            )
        } else {
            batches.getOrPut(style) { ArrayList() }.add(center)
        }
    }

    val kindlingPoints = ArrayList<Offset>(kindling.coerceAtMost(64))
    for (i in stars.size until (stars.size + kindling).coerceAtMost(unitPoints.size)) {
        kindlingPoints.add(screen(unitPoints[i]))
    }

    val constellationPath = if (constellationGroups.isEmpty()) {
        null
    } else {
        // Groups carry SkyStar.index — the star's row id — so resolve ids to the points we actually
        // drew; a filtered or renumbered list must never draw a figure through the wrong stars.
        val offsetByIndex = HashMap<Int, Offset>(stars.size * 2)
        for (i in stars.indices) offsetByIndex[stars[i].index] = screen(unitPoints[i])
        val path = Path()
        for (group in constellationGroups) {
            for (k in 1 until group.size) {
                val from = offsetByIndex[group[k - 1]] ?: continue
                val to = offsetByIndex[group[k]] ?: continue
                path.moveTo(from.x, from.y)
                path.lineTo(to.x, to.y)
            }
        }
        path
    }

    val dustBatches = LinkedHashMap<Float, MutableList<Offset>>()
    dust.forEachIndexed { index, point ->
        dustBatches.getOrPut(SkyPalette.dustAlpha(index)) { ArrayList() }.add(screen(point))
    }

    return SkyGeometry(
        staticStars = batches.map { (style, points) -> StarBatch(style, points) },
        animatedStars = animated,
        kindling = StarBatch(SkyPalette.kindlingStar, kindlingPoints),
        constellationPath = constellationPath,
        dust = dustBatches.map { (alpha, points) -> DustBatch(alpha, points) },
    )
}

private const val NANOS_PER_SECOND = 1_000_000_000f

/**
 * Golden-ratio phases rather than a hash: consecutive stars never blink together, and a star's
 * rhythm is a function of its own index, so it does not change between launches either.
 */
private fun twinklePeriodSeconds(index: Int): Float {
    val fraction = index * 0.6180339887f
    val unit = fraction - floor(fraction)
    return SkyPalette.MIN_TWINKLE_PERIOD_S + (SkyPalette.MAX_TWINKLE_PERIOD_S - SkyPalette.MIN_TWINKLE_PERIOD_S) * unit
}

private fun twinklePhase(index: Int): Float {
    val fraction = index * 0.7548776662f
    return fraction - floor(fraction)
}

private fun twinkleAlpha(seconds: Float, periodSeconds: Float, phase: Float): Float {
    val wave = 0.5f + 0.5f * sin((2.0 * PI * (seconds / periodSeconds + phase)).toFloat())
    return SkyPalette.TWINKLE_MIN_ALPHA + (1f - SkyPalette.TWINKLE_MIN_ALPHA) * wave
}

@Preview(name = "Sky — empty", widthDp = 320, heightDp = 352, showBackground = true)
@Composable
private fun StarfieldCanvasEmptyPreview() {
    StarfieldPreview(count = 0)
}

@Preview(name = "Sky — 40 stars", widthDp = 320, heightDp = 352, showBackground = true)
@Composable
private fun StarfieldCanvasSmallPreview() {
    StarfieldPreview(count = 40)
}

@Preview(name = "Sky — 2 000 stars", widthDp = 320, heightDp = 352, showBackground = true)
@Composable
private fun StarfieldCanvasFullPreview() {
    StarfieldPreview(count = 2_000)
}

@Composable
private fun StarfieldPreview(count: Int) {
    val stars = remember(count) { previewStars(count) }
    StarfieldCanvas(
        stars = stars,
        kindling = if (count == 0) 1 else 0,
        seed = 0x5EEDL,
        todayDay = stars.lastOrNull()?.logicalDay ?: 0,
        reduceMotion = true,
        onStarTapped = {},
        modifier = Modifier.fillMaxSize(),
    )
}

private fun previewStars(count: Int): List<SkyStar> = List(count) { index ->
    SkyStar(
        index = index,
        kind = when {
            index % 41 == 0 -> StarKind.QUIET_NIGHT
            index % 13 == 0 -> StarKind.WELCOME
            index % 7 == 0 -> StarKind.FOCUS
            index % 5 == 0 -> StarKind.PROTECTED_MORNING
            else -> StarKind.LEAVE
        },
        litWall = 1_760_000_000_000L + index * 3_600_000L,
        logicalDay = 21_600 + index / 6,
        sourcePackage = null,
    )
}
