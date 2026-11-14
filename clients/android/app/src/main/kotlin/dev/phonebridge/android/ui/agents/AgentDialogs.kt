package dev.phonebridge.android.ui.agents

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import dev.phonebridge.android.ui.components.ConfirmDialog
import dev.phonebridge.android.ui.tokens.TokenDraft
import dev.phonebridge.core.auth.TokenRecord

/** "Revoke all agents?" — shared by Home and Settings. */
@Composable
fun RevokeAllDialog(activeCount: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConfirmDialog(
        title = "Revoke all agents?",
        text = "${if (activeCount == 1) "The active agent is" else "All $activeCount active agents are"} locked out " +
            "immediately. Their entries stay in the list and the activity log. To use an agent again, connect it again.",
        confirmLabel = "Revoke all",
        destructive = true,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

@Composable
internal fun RevokeAgentDialog(record: TokenRecord, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConfirmDialog(
        title = "Revoke \"${record.name}\"?",
        text = "The agent is locked out immediately. This can't be undone; connect it again to use it.",
        confirmLabel = "Revoke",
        destructive = true,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

@Composable
internal fun DeleteAgentDialog(record: TokenRecord, active: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConfirmDialog(
        title = "Delete \"${record.name}\"?",
        text = if (active) {
            "The agent stops working immediately and is removed from this list. Its activity stays in the log."
        } else {
            "The agent is removed from this list. Its activity stays in the log."
        },
        confirmLabel = "Delete",
        destructive = true,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/** Edits an agent's display name (shown in approval prompts and the activity log). */
@Composable
internal fun RenameAgentDialog(name: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var value by rememberSaveable { mutableStateOf(name) }
    val error = AgentDraft.nameError(value)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename agent") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it.take(TokenDraft.MAX_NAME_LENGTH + 10) },
                label = { Text("Name") },
                isError = error != null,
                supportingText = { Text(error ?: "Shown in approval prompts and the activity log.") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            )
        },
        confirmButton = {
            TextButton(
                enabled = error == null,
                onClick = {
                    onDismiss()
                    onRename(value.trim())
                },
            ) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
