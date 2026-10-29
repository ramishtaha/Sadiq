package com.ramish.sadiq.ui.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.ui.window.DialogProperties
import com.ramish.sadiq.R
import androidx.compose.ui.res.stringResource

/**
 * A Material 3 Time Picker Dialog.
 *
 * @param title the title for the dialog.
 * @param onCancel what to do when the cancel button is pressed.
 * @param onConfirm what to do when the confirm button is pressed.
 * @param state the [TimePickerState] to use.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerDialog(
    title: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    state: TimePickerState,
    modifier: Modifier = Modifier
) {
    AlertDialog(
        modifier = modifier,
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = { TimeInput(state = state) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.confirm_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.confirm_cancel))
            }
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    )
}
