package com.ramish.sadiq.ui.guardtab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.ramish.sadiq.R
import com.ramish.sadiq.core.model.Mode
import com.ramish.sadiq.nav.Routes
import com.ramish.sadiq.ui.components.HeroEmptyState
import com.ramish.sadiq.ui.components.PrimaryPillButton
import com.ramish.sadiq.ui.components.SadiqCard
import com.ramish.sadiq.ui.components.SadiqTopBar
import com.ramish.sadiq.ui.components.SectionHeader
import com.ramish.sadiq.ui.components.SelectableCard
import com.ramish.sadiq.ui.components.SettingRow
import com.ramish.sadiq.ui.theme.SadiqTheme
import com.ramish.sadiq.ui.theme.Spacing
import com.ramish.sadiq.ui.theme.ThemeFlavor

@Composable
fun GuardScreen(
    navController: NavController,
    viewModel: GuardViewModel = hiltViewModel(),
    slots: List<com.ramish.sadiq.contract.UiSlot> = emptyList(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SadiqTopBar(title = stringResource(R.string.guard_title))

        Column(modifier = Modifier.padding(horizontal = Spacing.xl)) {
            SectionHeader(text = stringResource(R.string.guard_mode_section_header))
            ModeSelection(currentMode = uiState.mode, onSelectMode = viewModel::setMode)

            SectionHeader(text = stringResource(R.string.guard_apps_section_header))
            if (uiState.apps.isEmpty()) {
                HeroEmptyState(
                    icon = Icons.Filled.Shield,
                    title = stringResource(R.string.guard_empty_headline),
                    body = stringResource(R.string.guard_empty_body),
                    actionLabel = stringResource(R.string.guard_empty_action),
                    onAction = { navController.navigate(Routes.APPS) }
                )
            } else {
                SadiqCard(onClick = { navController.navigate(Routes.APPS) }) {
                    Text(
                        text = stringResource(R.string.guard_apps_summary, uiState.apps.size),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }

            SectionHeader(text = stringResource(R.string.guard_protected_times_section_header))
            SettingRow(
                title = stringResource(R.string.guard_sleep_gate_summary, formatMinuteOfDay(uiState.sleepGate.startMinuteOfDay)),
                subtitle = stringResource(R.string.guard_mode_subtitle),
                checked = uiState.sleepGate.enabled,
                onCheckedChange = viewModel::toggleSleepGate,
                onClick = { navController.navigate(Routes.BEDTIME) }
            )
            SettingRow(
                title = stringResource(R.string.guard_morning_window_summary, formatMinuteOfDay(uiState.morning.endMinuteOfDay)),
                subtitle = stringResource(R.string.guard_mode_subtitle),
                checked = uiState.morning.enabled,
                onCheckedChange = viewModel::toggleMorning,
                onClick = { navController.navigate(Routes.MORNING) }
            )
            SettingRow(
                title = stringResource(R.string.guard_prayer_times_summary, uiState.prayerSummary),
                subtitle = uiState.todayTimesLine,
                showChevron = true,
                onClick = { navController.navigate(Routes.PRAYER) }
            )
        }
    }

        // Sections contributed by other features (strict mode, pending changes) render here.
        slots.forEach { slot -> (navController as? androidx.navigation.NavHostController)?.let { host -> slot.Content(host) } }
}

private fun formatMinuteOfDay(minuteOfDay: Int): String {
    val hour = minuteOfDay / 60
    val minute = minuteOfDay % 60
    return String.format("%02d:%02d", hour, minute)
}

@Composable
private fun ModeSelection(currentMode: Mode, onSelectMode: (Mode) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Mode.entries.forEach { mode ->
            val title = when (mode) {
                Mode.GENTLE -> stringResource(R.string.guard_mode_gentle)
                Mode.BALANCED -> stringResource(R.string.guard_mode_balanced)
                Mode.HARDCORE -> stringResource(R.string.guard_mode_hardcore)
            }
            SelectableCard(
                title = title,
                subtitle = stringResource(R.string.guard_mode_subtitle),
                selected = mode == currentMode,
                onClick = { onSelectMode(mode) }
            )
        }
    }
}

@Preview(name = "Guard Screen · Light", widthDp = 360, showBackground = true)
@Composable
private fun GuardScreenLightPreview() {
    SadiqTheme(flavor = ThemeFlavor.LIGHT) {
        GuardScreen(rememberNavController())
    }
}

@Preview(name = "Guard Screen · Dark", widthDp = 360, showBackground = true)
@Composable
private fun GuardScreenDarkPreview() {
    SadiqTheme(flavor = ThemeFlavor.DARK) {
        GuardScreen(rememberNavController())
    }
}

@Preview(name = "Guard Screen · Sepia", widthDp = 360, showBackground = true)
@Composable
private fun GuardScreenSepiaPreview() {
    SadiqTheme(flavor = ThemeFlavor.SEPIA) {
        GuardScreen(rememberNavController())
    }
}
