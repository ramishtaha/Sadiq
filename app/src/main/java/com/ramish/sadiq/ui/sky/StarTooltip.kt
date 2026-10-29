package com.ramish.sadiq.ui.sky

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.ramish.sadiq.R
import com.ramish.sadiq.core.economy.StarKind
import com.ramish.sadiq.core.sky.SkyStar
import com.ramish.sadiq.core.time.LogicalDays
import com.ramish.sadiq.ui.components.ComponentPreview
import com.ramish.sadiq.ui.components.SadiqCard
import com.ramish.sadiq.ui.theme.ThemeFlavor
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun StarTooltip(
    star: SkyStar,
    modifier: Modifier = Modifier,
) {
    SadiqCard(modifier = modifier) {
        Column {
            val date = Instant.ofEpochMilli(star.litWall).atZone(ZoneId.systemDefault()).toLocalDate()
            val time = Instant.ofEpochMilli(star.litWall).atZone(ZoneId.systemDefault()).toLocalTime()
            val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(Locale.getDefault())
            val timeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault())

            val formattedDate = date.format(formatter)
            val formattedTime = time.format(timeFormatter)

            val text = when (star.kind) {
                StarKind.LEAVE -> stringResource(R.string.sky_tooltip_leave, star.sourcePackage ?: "")
                StarKind.KEPT_WORD -> stringResource(R.string.sky_tooltip_kept_word, 0) // TODO: retrieve actual minutes from star.sourceRef or similar
                StarKind.DONE_EARLY -> stringResource(R.string.sky_tooltip_done_early)
                StarKind.FOCUS -> stringResource(R.string.sky_tooltip_focus, 0) // TODO: retrieve actual minutes from star.sourceRef or similar
                StarKind.FOCUS_FINISH -> stringResource(R.string.sky_tooltip_focus_finish)
                StarKind.QUIET_NIGHT -> stringResource(R.string.sky_tooltip_quiet_night)
                StarKind.PROTECTED_MORNING -> stringResource(R.string.sky_tooltip_quiet_morning)
                StarKind.WELCOME -> stringResource(R.string.sky_tooltip_welcome)
            }
            Text(
                text = "$formattedDate · $formattedTime · $text",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Preview(name = "StarTooltip · Light", showBackground = true)
@Composable
private fun StarTooltipLightPreview() {
    ComponentPreview(ThemeFlavor.LIGHT) {
        StarTooltip(
            star = SkyStar(
                index = 0,
                kind = StarKind.LEAVE,
                litWall = System.currentTimeMillis(),
                logicalDay = LogicalDays.of(System.currentTimeMillis(), ZoneId.systemDefault().id),
                sourcePackage = "com.instagram.android"
            )
        )
    }
}
