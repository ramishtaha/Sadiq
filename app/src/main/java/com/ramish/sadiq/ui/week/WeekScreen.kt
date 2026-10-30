package com.ramish.sadiq.ui.week

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ramish.sadiq.R
import com.ramish.sadiq.core.insight.ReflectionReason
import com.ramish.sadiq.core.insight.WeeklySuggestion
import com.ramish.sadiq.core.model.Mode
import com.ramish.sadiq.review.WeekCoverage
import com.ramish.sadiq.review.WeekFormat
import com.ramish.sadiq.ui.components.HeroEmptyState
import com.ramish.sadiq.ui.components.PrimaryPillButton
import com.ramish.sadiq.ui.components.SadiqCard
import com.ramish.sadiq.ui.components.SadiqTopBar
import com.ramish.sadiq.ui.components.SectionHeader
import com.ramish.sadiq.ui.components.TextPillButton
import com.ramish.sadiq.ui.theme.SadiqTheme
import com.ramish.sadiq.ui.theme.Spacing
import com.ramish.sadiq.ui.theme.ThemeFlavor
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/**
 * S4 Week: where the time went (§4.3).
 *
 * Four cards at most, in the spec's order: the week's total, the apps and the peak band, what he
 * chose, and what he said with one suggestion. The page scrolls, because four cards do not fit on a
 * phone at font scale 1.3 and cutting the copy to fit would be the wrong trade.
 *
 * There is one action on the screen — the suggestion's — and it is applied by
 * [WeekViewModel.onApplySuggestion], never here.
 */
@Composable
fun WeekScreen(viewModel: WeekViewModel = hiltViewModel()) {
    val state: WeekUiState by viewModel.uiState.collectAsStateWithLifecycle()

    // The week is a window on data that ages: the numbers change while the tab is away, and a review
    // read once at first composition would still be showing last Sunday's week by Friday.
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    WeekContent(
        state = state,
        onApplySuggestion = viewModel::onApplySuggestion,
        onTurnOnUsageAccess = viewModel::onTurnOnUsageAccess,
    )
}

/**
 * The screen without its ViewModel, so it can be previewed and reasoned about from a state alone.
 *
 * @param state what to draw.
 * @param onApplySuggestion the one tap that applies the suggestion.
 * @param onTurnOnUsageAccess opens the OS page for usage access, for the "Approximate" chip.
 */
@Composable
fun WeekContent(
    state: WeekUiState,
    onApplySuggestion: () -> Unit,
    onTurnOnUsageAccess: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        SadiqTopBar(title = stringResource(R.string.week_title))

        when {
            // The page stays blank while the first read runs: no spinner, no zeroes, nothing that
            // would have to be taken back a frame later.
            state.loading -> Spacer(Modifier.fillMaxSize())

            !state.available -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                HeroEmptyState(
                    icon = Icons.Filled.CalendarMonth,
                    title = stringResource(R.string.week_empty_headline),
                    body = stringResource(R.string.week_empty_body),
                )
            }

            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.xl, vertical = Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                if (state.approximate) {
                    ApproximateNote(onTurnOnUsageAccess = onTurnOnUsageAccess)
                } else {
                    // The headline is a measured total. When the numbers are engine records it would
                    // be a guess dressed as a measurement, so it is withheld (§4.6, A4-16).
                    HeadlineCard(state = state)
                }
                WhereItWentCard(state = state)
                ChoicesCard(state = state)
                WhatYouSaidCard(state = state, onApplySuggestion = onApplySuggestion)

                Spacer(Modifier.height(Spacing.xl))
            }
        }
    }
}

/** Card 1: the week in one line, and which way it moved. */
@Composable
private fun HeadlineCard(state: WeekUiState) {
    SadiqCard {
        Text(
            text = stringResource(R.string.week_headline, WeekFormat.duration(state.totalMs)),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = comparisonLine(state.deltaMs),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The comparison line, neutral in both directions: "2 h less than last week" and "1 h more than last
 * week" are the same sentence shape, and an unchanged week is stated as unchanged rather than praised.
 */
@Composable
private fun comparisonLine(deltaMs: Long?): String {
    if (deltaMs == null) return stringResource(R.string.week_no_comparison)
    val amount: String = WeekFormat.duration(abs(deltaMs))
    return when (WeekFormat.comparison(deltaMs)) {
        WeekFormat.Comparison.SAME -> stringResource(R.string.week_delta_same)
        WeekFormat.Comparison.LESS -> stringResource(R.string.week_delta_less, amount)
        WeekFormat.Comparison.MORE -> stringResource(R.string.week_delta_more, amount)
    }
}

/** Card 2: three bars and the two hours most of it fell in. */
@Composable
private fun WhereItWentCard(state: WeekUiState) {
    val context = LocalContext.current
    val is24Hour = remember(context) { DateFormat.is24HourFormat(context) }

    SadiqCard {
        SectionHeader(text = stringResource(R.string.week_section_where))

        if (state.topApps.isEmpty()) {
            Text(
                text = stringResource(R.string.week_no_apps),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            WeekBars(bars = state.topApps)
        }

        // Only when the hour profile had something in it: the builder returns null rather than a
        // guess, and a made-up band would be the one line on this screen that is not true.
        state.peakStartHour?.let { startHour ->
            Spacer(Modifier.height(Spacing.md))
            Text(
                text = stringResource(
                    R.string.week_peak_band,
                    WeekFormat.hourLabel(startHour, is24Hour),
                    WeekFormat.hourLabel(WeekFormat.bandEndHour(startHour), is24Hour),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Card 3: what he chose, as four plain numbers. */
@Composable
private fun ChoicesCard(state: WeekUiState) {
    SadiqCard {
        SectionHeader(text = stringResource(R.string.week_section_choices))

        ChoiceRow(
            label = stringResource(R.string.week_choice_stars),
            value = state.stars.toString(),
        )
        ChoiceRow(
            label = stringResource(R.string.week_choice_leaves),
            value = state.leaves.toString(),
        )
        ChoiceRow(
            label = stringResource(R.string.week_choice_quiet_nights),
            value = stringResource(
                R.string.week_quiet_nights_value,
                state.quietNights,
                WeekCoverage.DAYS_IN_WEEK,
            ),
        )
        ChoiceRow(
            label = stringResource(R.string.week_choice_focus),
            value = stringResource(R.string.week_value_minutes, state.focusMinutes),
        )
    }
}

/** One line of card 3: the thing counted on the left, the count on the right. */
@Composable
private fun ChoiceRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Card 4: the most common check-in answer and the one change worth trying. */
@Composable
private fun WhatYouSaidCard(state: WeekUiState, onApplySuggestion: () -> Unit) {
    SadiqCard {
        SectionHeader(text = stringResource(R.string.week_section_said))

        val reason: ReflectionReason? = state.topReason
        if (reason == null) {
            Text(
                text = stringResource(R.string.week_no_reason),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = stringResource(R.string.week_top_reason, reasonLabel(reason)),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Spacer(Modifier.height(Spacing.lg))

        when {
            state.suggestionApplied -> AppliedLine(appliedAtWall = state.suggestionAppliedAtWall)
            state.suggestion != null -> SuggestionBlock(
                state = state,
                onApplySuggestion = onApplySuggestion,
            )

            else -> Text(
                text = stringResource(R.string.week_no_suggestion),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The suggestion: what it would change, why, and the one button that does it. */
@Composable
private fun SuggestionBlock(state: WeekUiState, onApplySuggestion: () -> Unit) {
    val context = LocalContext.current
    val is24Hour = remember(context) { DateFormat.is24HourFormat(context) }

    val title: String
    val body: String
    val action: String

    when (val suggestion = state.suggestion) {
        is WeeklySuggestion.MoveSleepGate -> {
            title = stringResource(
                R.string.week_suggestion_bedtime_title,
                WeekFormat.minuteLabel(suggestion.toMinuteOfDay, is24Hour),
            )
            body = stringResource(R.string.week_suggestion_bedtime_body)
            action = stringResource(R.string.week_suggestion_bedtime_action)
        }

        is WeeklySuggestion.SwitchMode -> {
            val modeName: String = modeLabel(suggestion.to)
            title = stringResource(R.string.week_suggestion_mode_title, modeName)
            body = stringResource(R.string.week_suggestion_mode_body)
            action = stringResource(R.string.week_suggestion_mode_action, modeName)
        }

        is WeeklySuggestion.GuardApp -> {
            val label: String = state.suggestionAppLabel ?: suggestion.packageName
            title = stringResource(R.string.week_suggestion_guard_title, label)
            body = stringResource(R.string.week_suggestion_guard_body, label)
            action = stringResource(R.string.week_suggestion_guard_action)
        }

        null -> return
    }

    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(Spacing.sm))
    Text(
        text = body,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(Spacing.lg))
    PrimaryPillButton(text = action, onClick = onApplySuggestion)
}

/** What replaced the suggestion once it was applied: the fact, not a thank-you. */
@Composable
private fun AppliedLine(appliedAtWall: Long?) {
    val context = LocalContext.current
    val is24Hour = remember(context) { DateFormat.is24HourFormat(context) }

    val text: String = if (appliedAtWall == null) {
        stringResource(R.string.week_suggestion_applied)
    } else {
        stringResource(
            R.string.week_suggestion_scheduled,
            WeekFormat.minuteLabel(minuteOfDayOf(appliedAtWall), is24Hour),
        )
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** "Approximate", and the one tap that would make it exact. */
@Composable
private fun ApproximateNote(onTurnOnUsageAccess: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Text(
                text = stringResource(R.string.week_approximate),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
            )
        }
        TextPillButton(
            text = stringResource(R.string.week_turn_on_usage_access),
            onClick = onTurnOnUsageAccess,
        )
    }
}

/** The check-in answers, in the same words the question used (§4.4). */
@Composable
private fun reasonLabel(reason: ReflectionReason): String = stringResource(
    when (reason) {
        ReflectionReason.BORED -> R.string.intercept_reflect_chip_bored
        ReflectionReason.TIRED -> R.string.intercept_reflect_chip_tired
        ReflectionReason.AVOIDING -> R.string.intercept_reflect_chip_avoiding
        ReflectionReason.LONELY -> R.string.intercept_reflect_chip_lonely
        ReflectionReason.ANXIOUS -> R.string.intercept_reflect_chip_anxious
        ReflectionReason.HABIT -> R.string.intercept_reflect_chip_habit
    }
)

/** Gentle / Steady / Firm, as the name addendum renames them. */
@Composable
private fun modeLabel(mode: Mode): String = stringResource(
    when (mode) {
        Mode.GENTLE -> R.string.week_mode_gentle
        Mode.BALANCED -> R.string.week_mode_steady
        Mode.HARDCORE -> R.string.week_mode_firm
    }
)

/** Local minute of day for a wall time, used by the applied-at line. */
private fun minuteOfDayOf(wallMs: Long): Int {
    val local = Instant.ofEpochMilli(wallMs).atZone(ZoneId.systemDefault()).toLocalTime()
    return local.hour * 60 + local.minute
}

@Preview(name = "Week · Light", widthDp = 360, heightDp = 900, showBackground = true)
@Composable
private fun WeekContentLightPreview() {
    SadiqTheme(flavor = ThemeFlavor.LIGHT) {
        WeekContent(
            state = SampleWeek,
            onApplySuggestion = {},
            onTurnOnUsageAccess = {},
        )
    }
}

@Preview(name = "Week · Dark", widthDp = 360, heightDp = 900, showBackground = true)
@Composable
private fun WeekContentDarkPreview() {
    SadiqTheme(flavor = ThemeFlavor.DARK) {
        WeekContent(
            state = SampleWeek,
            onApplySuggestion = {},
            onTurnOnUsageAccess = {},
        )
    }
}

/** A week that exercises every part of the screen, for the previews only. */
private val SampleWeek: WeekUiState = WeekUiState(
    loading = false,
    available = true,
    approximate = false,
    totalMs = 36_000_000L,
    deltaMs = -7_200_000L,
    topApps = listOf(
        WeekBar("com.instagram.android", "Instagram", 14_400_000L, 1f),
        WeekBar("com.google.android.youtube", "YouTube", 8_280_000L, 0.575f),
        WeekBar("com.reddit.frontpage", "Reddit", 3_600_000L, 0.25f),
    ),
    peakStartHour = 23,
    stars = 12,
    leaves = 9,
    quietNights = 4,
    focusMinutes = 75,
    topReason = ReflectionReason.TIRED,
    suggestion = WeeklySuggestion.MoveSleepGate(toMinuteOfDay = 22 * 60),
    suggestionAppLabel = null,
    suggestionApplied = false,
)
