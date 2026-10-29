package com.ramish.sadiq.ui.week

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ramish.sadiq.review.WeekFormat
import com.ramish.sadiq.ui.theme.SadiqTheme
import com.ramish.sadiq.ui.theme.Spacing
import com.ramish.sadiq.ui.theme.ThemeFlavor

/**
 * "WHERE IT WENT": the week's three largest apps as horizontal bars (§4.3 S4).
 *
 * The bar is 8 dp tall on a `surfaceVariant` track with `shapes.extraSmall` corners, filled with
 * `primary` to the fraction of the largest app — a bar is a comparison, not a percentage, so the
 * biggest one being full width is the honest scale.
 *
 * The label and the duration sit above the bar as text, which is what TalkBack reads; the bar itself
 * is decorative and has no content description, so nothing is announced twice.
 */
@Composable
fun WeekBars(
    bars: List<WeekBar>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        bars.forEach { bar -> WeekBarRow(bar = bar) }
    }
}

/** One app: its name on the left, its week on the right, its share underneath. */
@Composable
private fun WeekBarRow(bar: WeekBar) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = bar.label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = WeekFormat.duration(bar.foregroundMs),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(Spacing.xs))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(BAR_HEIGHT)
                .clip(MaterialTheme.shapes.extraSmall)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(bar.fraction.coerceIn(MIN_VISIBLE_FRACTION, 1f))
                    .fillMaxHeight()
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

/** §4.3 S4's bar height. */
private val BAR_HEIGHT = 8.dp

/** A real but tiny share still draws a sliver, so a row never looks like it has no bar at all. */
private const val MIN_VISIBLE_FRACTION = 0.02f

@Preview(name = "WeekBars · Light", widthDp = 360, showBackground = true)
@Composable
private fun WeekBarsLightPreview() {
    SadiqTheme(flavor = ThemeFlavor.LIGHT) {
        WeekBars(
            bars = listOf(
                WeekBar("com.instagram.android", "Instagram", 14_400_000L, 1f),
                WeekBar("com.google.android.youtube", "YouTube", 8_280_000L, 0.575f),
                WeekBar("com.reddit.frontpage", "Reddit", 3_600_000L, 0.25f),
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}
