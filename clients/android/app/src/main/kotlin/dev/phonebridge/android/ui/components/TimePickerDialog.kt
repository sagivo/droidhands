package dev.phonebridge.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Material 3 clock picker in a dialog styled like [ConfirmDialog] (24dp corners, text buttons, Cancel
 * then OK); [onConfirm] receives minutes after midnight.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerDialog(
    title: String,
    initialMinuteOfDay: Int,
    is24Hour: Boolean,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberTimePickerState(
        initialHour = (initialMinuteOfDay / 60).coerceIn(0, 23),
        initialMinute = (initialMinuteOfDay % 60).coerceIn(0, 59),
        is24Hour = is24Hour,
    )
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.padding(24.dp)) {
            // Sized by the picker itself (vertical dial in portrait, wider horizontal layout in landscape).
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .align(Alignment.Start)
                        .semantics { heading() },
                )
                TimePicker(state = state)
                Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TertiaryButton("Cancel", onClick = onDismiss)
                    TertiaryButton("OK", onClick = {
                        onDismiss()
                        onConfirm(state.hour * 60 + state.minute)
                    })
                }
            }
        }
    }
}
