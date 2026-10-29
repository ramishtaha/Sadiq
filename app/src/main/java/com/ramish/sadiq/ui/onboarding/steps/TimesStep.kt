package com.ramish.sadiq.ui.onboarding.steps

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ramish.sadiq.R
import com.ramish.sadiq.ui.components.ComponentPreview
import com.ramish.sadiq.ui.components.SelectableCard
import com.ramish.sadiq.ui.onboarding.OnboardingViewModel
import com.ramish.sadiq.ui.onboarding.OemStepScaffold
import com.ramish.sadiq.ui.theme.Spacing
import com.ramish.sadiq.ui.theme.ThemeFlavor
import com.ramish.sadiq.ui.components.TimePickerDialog
import java.util.Calendar

/** O4: Protected times. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimesStep(vm: OnboardingViewModel) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showBedtimePicker by remember { mutableStateOf(false) }
    var showMorningPicker by remember { mutableStateOf(false) }

    val is24HourFormat = DateFormat.is24HourFormat(context)
    val calendar = Calendar.getInstance()

    val initialBedtimeHour = state.sleepStartMin / 60
    val initialBedtimeMinute = state.sleepStartMin % 60
    val bedtimePickerState = rememberTimePickerState(initialHour = initialBedtimeHour, initialMinute = initialBedtimeMinute, is24Hour = is24HourFormat)

    val initialMorningHour = state.morningEndMin / 60
    val initialMorningMinute = state.morningEndMin % 60
    val morningPickerState = rememberTimePickerState(initialHour = initialMorningHour, initialMinute = initialMorningMinute, is24Hour = is24HourFormat)

    OemStepScaffold(
        icon = Icons.Filled.Bedtime,
        headline = stringResource(R.string.onboarding_times_headline),
        body = stringResource(R.string.onboarding_times_body, state.place),
        actionLabel = stringResource(R.string.onboarding_times_action),
        onAction = { vm.next() }
    ) {
        SelectableCard(
            title = stringResource(R.string.onboarding_times_bedtime_title),
            subtitle = formatTime(state.sleepStartMin, is24HourFormat, calendar),
            selected = false, // Not selectable, just a tappable card
            onClick = { showBedtimePicker = true }
        )
        Spacer(Modifier.height(Spacing.sm))
        SelectableCard(
            title = stringResource(R.string.onboarding_times_morning_title),
            subtitle = formatTime(state.morningEndMin, is24HourFormat, calendar),
            selected = false, // Not selectable, just a tappable card
            onClick = { showMorningPicker = true }
        )

        if (showBedtimePicker) {
            TimePickerDialog(
                title = stringResource(R.string.onboarding_times_bedtime_picker_title),
                onCancel = { showBedtimePicker = false },
                onConfirm = {
                    val newMinuteOfDay = bedtimePickerState.hour * 60 + bedtimePickerState.minute
                    vm.setSleepStart(newMinuteOfDay)
                    showBedtimePicker = false
                },
                state = bedtimePickerState
            )
        }

        if (showMorningPicker) {
            TimePickerDialog(
                title = stringResource(R.string.onboarding_times_morning_picker_title),
                onCancel = { showMorningPicker = false },
                onConfirm = {
                    val newMinuteOfDay = morningPickerState.hour * 60 + morningPickerState.minute
                    vm.setMorningEnd(newMinuteOfDay)
                    showMorningPicker = false
                },
                state = morningPickerState
            )
        }
    }
}

@Composable
private fun formatTime(minuteOfDay: Int, is24Hour: Boolean, calendar: Calendar): String {
    calendar.set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
    calendar.set(Calendar.MINUTE, minuteOfDay % 60)
    val format = if (is24Hour) "HH:mm" else "hh:mm a"
    return DateFormat.format(format, calendar.time).toString()
}
