package com.ramish.sadiq.ui.guardtab

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.ramish.sadiq.R
import com.ramish.sadiq.ui.components.SadiqCard
import com.ramish.sadiq.ui.components.SadiqTopBar
import com.ramish.sadiq.ui.components.SectionHeader
import com.ramish.sadiq.ui.components.SettingRow
import com.ramish.sadiq.ui.components.TimePickerDialog
import com.ramish.sadiq.ui.theme.SadiqTheme
import com.ramish.sadiq.ui.theme.Spacing
import com.ramish.sadiq.ui.theme.ThemeFlavor
import java.time.LocalTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProtectedTimesScreen(navController: NavController, viewModel: ProtectedTimesViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var showSleepGateTimePicker by remember { mutableStateOf(false) }
    var showMorningTimePicker by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SadiqTopBar(
            title = stringResource(R.string.protected_times_title),
            onBack = { navController.popBackStack() },
            backContentDescription = stringResource(R.string.confirm_cancel)
        )

        Column(modifier = Modifier.padding(horizontal = Spacing.xl)) {
            SectionHeader(text = stringResource(R.string.protected_times_sleep_gate_section_header))
            SadiqCard {
                SettingRow(
                    title = stringResource(R.string.protected_times_sleep_gate_title),
                    subtitle = if (uiState.sleepEnabled) stringResource(R.string.protected_times_on) else stringResource(R.string.protected_times_off),
                    checked = uiState.sleepEnabled,
                    onCheckedChange = viewModel::setSleepGateEnabled
                )
                if (uiState.sleepEnabled) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.md))
                    SettingRow(
                        title = stringResource(R.string.protected_times_start_time),
                        subtitle = formatMinuteOfDay(uiState.sleepStartMinuteOfDay),
                        showChevron = true,
                        onClick = { showSleepGateTimePicker = true }
                    )
                    Text(
                        text = stringResource(R.string.protected_times_sleep_gate_note, "20:46"), // TODO: Actual time
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spacing.sm)
                    )
                    if (uiState.sleepGatePreview.isNotEmpty()) {
                        Text(
                            text = uiState.sleepGatePreview,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = Spacing.sm)
                        )
                    }
                }
            }

            SectionHeader(text = stringResource(R.string.protected_times_morning_window_section_header))
            SadiqCard {
                SettingRow(
                    title = stringResource(R.string.protected_times_morning_window_title),
                    subtitle = if (uiState.morningEnabled) stringResource(R.string.protected_times_on) else stringResource(R.string.protected_times_off),
                    checked = uiState.morningEnabled,
                    onCheckedChange = viewModel::setMorningEnabled
                )
                if (uiState.morningEnabled) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.md))
                    SettingRow(
                        title = stringResource(R.string.protected_times_end_time),
                        subtitle = formatMinuteOfDay(uiState.morningEndMinuteOfDay),
                        showChevron = true,
                        onClick = { showMorningTimePicker = true }
                    )
                }
            }
        }
    }

    if (showSleepGateTimePicker) {
        val initialTime = LocalTime.of(uiState.sleepStartMinuteOfDay / 60, uiState.sleepStartMinuteOfDay % 60)
        val pickerState = rememberTimePickerState(
            initialHour = initialTime.hour,
            initialMinute = initialTime.minute,
            is24Hour = true
        )
        TimePickerDialog(
            title = stringResource(R.string.protected_times_select_sleep_gate_start_time),
            onCancel = { showSleepGateTimePicker = false },
            onConfirm = {
                viewModel.setSleepGateStartTime(pickerState.hour, pickerState.minute)
                showSleepGateTimePicker = false
            },
            state = pickerState
        )
    }

    if (showMorningTimePicker) {
        val initialTime = LocalTime.of(uiState.morningEndMinuteOfDay / 60, uiState.morningEndMinuteOfDay % 60)
        val pickerState = rememberTimePickerState(
            initialHour = initialTime.hour,
            initialMinute = initialTime.minute,
            is24Hour = true
        )
        TimePickerDialog(
            title = stringResource(R.string.protected_times_select_morning_window_end_time),
            onCancel = { showMorningTimePicker = false },
            onConfirm = {
                viewModel.setMorningEndTime(pickerState.hour, pickerState.minute)
                showMorningTimePicker = false
            },
            state = pickerState
        )
    }
}

private fun formatMinuteOfDay(minuteOfDay: Int): String {
    val hour = minuteOfDay / 60
    val minute = minuteOfDay % 60
    return String.format("%02d:%02d", hour, minute)
}

@Preview(name = "Protected Times Screen · Light", widthDp = 360, showBackground = true)
@Composable
private fun ProtectedTimesScreenLightPreview() {
    SadiqTheme(flavor = ThemeFlavor.LIGHT) {
        ProtectedTimesScreen(rememberNavController())
    }
}

@Preview(name = "Protected Times Screen · Dark", widthDp = 360, showBackground = true)
@Composable
private fun ProtectedTimesScreenDarkPreview() {
    SadiqTheme(flavor = ThemeFlavor.DARK) {
        ProtectedTimesScreen(rememberNavController())
    }
}

@Preview(name = "Protected Times Screen · Sepia", widthDp = 360, showBackground = true)
@Composable
private fun ProtectedTimesScreenSepiaPreview() {
    SadiqTheme(flavor = ThemeFlavor.SEPIA) {
        ProtectedTimesScreen(rememberNavController())
    }
}
