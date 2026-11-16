package com.droidhands.android.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/** Two-button confirmation. [destructive] colors the confirm button with the error color. */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    dismissLabel: String = "Cancel",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TertiaryButton(
                confirmLabel,
                onClick = {
                    onDismiss()
                    onConfirm()
                },
                destructive = destructive,
            )
        },
        dismissButton = { TertiaryButton(dismissLabel, onClick = onDismiss) },
    )
}
