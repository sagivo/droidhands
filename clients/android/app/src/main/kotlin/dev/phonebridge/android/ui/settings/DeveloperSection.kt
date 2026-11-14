package dev.phonebridge.android.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.phonebridge.android.plugins.LocalPluginEntry
import dev.phonebridge.android.ui.components.Notice
import dev.phonebridge.android.ui.components.NoticeKind
import dev.phonebridge.android.ui.components.SwitchRow

/**
 * Settings > Developer: local plugins (docs/PLUGINS.md §10). Off by default; when on, plugin files can be
 * imported with the system file picker (or pushed with scripts/plugin-push.sh on a debug build) and removed.
 */
@Composable
internal fun DeveloperSection(
    enabled: Boolean,
    plugins: List<LocalPluginEntry>,
    importing: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onImport: () -> Unit,
    onRemove: (LocalPluginEntry) -> Unit,
) {
    SettingsCard {
        SwitchRow(
            title = "Local plugins",
            subtitle = "Try an app plugin you are writing without rebuilding PhoneBridge. Local plugins are not reviewed, " +
                "so every action they add asks for your approval.",
            checked = enabled,
            onCheckedChange = onEnabledChange,
        )
        if (enabled) {
            Notice(
                "Only import plugins you wrote or trust. A plugin can open screens and tap inside its app when you approve " +
                    "its actions.",
                kind = NoticeKind.WARNING,
            )
            OutlinedButton(onClick = onImport, enabled = !importing) { Text(if (importing) "Importing…" else "Import plugin") }
            if (plugins.isEmpty()) {
                Text(
                    "No local plugins. Import a plugin.yaml, or run scripts/plugin-push.sh plugins/<id> with a debug build " +
                        "connected over USB.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            plugins.forEach { entry ->
                HorizontalDivider()
                LocalPluginRow(entry, onRemove)
            }
        }
    }
}

@Composable
private fun LocalPluginRow(entry: LocalPluginEntry, onRemove: (LocalPluginEntry) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            val plugin = entry.plugin
            Text(plugin?.name ?: entry.id, style = MaterialTheme.typography.bodyLarge)
            if (plugin != null) {
                val actions = plugin.actions.size
                Text(
                    "${plugin.id} · ${plugin.androidPackages.firstOrNull().orEmpty()} · $actions ${if (actions == 1) "action" else "actions"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "Not used: " + entry.errors.firstOrNull().orEmpty() + if (entry.errors.size > 1) " (+${entry.errors.size - 1} more)" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 4,
                )
            }
        }
        TextButton(onClick = { onRemove(entry) }) { Text("Remove") }
    }
}

/** Why an import was refused: the validator's messages, one per paragraph. */
@Composable
internal fun ImportErrorsDialog(errors: List<String>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Plugin not imported") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "Fix these problems (./scripts/plugin-check.sh shows them on your computer too), then import it again:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                errors.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}