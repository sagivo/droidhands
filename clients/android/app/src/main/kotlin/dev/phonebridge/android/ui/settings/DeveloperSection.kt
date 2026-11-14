package dev.phonebridge.android.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.phonebridge.android.plugins.LocalPluginEntry
import dev.phonebridge.android.ui.components.Accents
import dev.phonebridge.android.ui.components.CardPadding
import dev.phonebridge.android.ui.components.IconRowInset
import dev.phonebridge.android.ui.components.IconTile
import dev.phonebridge.android.ui.components.ListDivider
import dev.phonebridge.android.ui.components.ListRow
import dev.phonebridge.android.ui.components.ListSection
import dev.phonebridge.android.ui.components.Monogram
import dev.phonebridge.android.ui.components.Notice
import dev.phonebridge.android.ui.components.NoticeKind
import dev.phonebridge.android.ui.components.SwitchRow
import dev.phonebridge.android.ui.components.TertiaryButton

/**
 * Settings > Developer: local plugins (docs/PLUGINS.md §10). Off by default; when on, plugin files can be
 * imported with the system file picker (or pushed with scripts/plugin-push.sh on a debug build) and removed.
 * Imported plugins are rows; "Import plugin" is the last row, as an "Add" row.
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
    ListSection(
        title = "Developer",
        footer = "Try an app plugin you are writing without rebuilding PhoneBridge. Local plugins are not reviewed, so " +
            "every action they add asks for your approval.",
    ) {
        SwitchRow(
            title = "Local plugins",
            subtitle = "Unreviewed; every action asks you.",
            checked = enabled,
            onCheckedChange = onEnabledChange,
            leading = { IconTile(Icons.Filled.Build, Accents.Gray) },
        )
        if (enabled) {
            ListDivider(IconRowInset)
            SectionBlock {
                Notice(
                    "Only import plugins you wrote or trust. A plugin can open screens and tap inside its app when you approve " +
                        "its actions.",
                    kind = NoticeKind.WARNING,
                )
            }
            plugins.forEach { entry ->
                ListDivider()
                LocalPluginRow(entry, onRemove)
            }
            ListDivider(if (plugins.isEmpty()) CardPadding else IconRowInset)
            ListRow(
                title = if (importing) "Importing…" else "Import plugin",
                subtitle = if (plugins.isEmpty()) {
                    "No local plugins. Import a plugin.yaml, or run scripts/plugin-push.sh plugins/<id> with a debug build " +
                        "connected over USB."
                } else {
                    null
                },
                leading = { IconTile(Icons.Filled.Add, Accents.Indigo) },
                titleColor = MaterialTheme.colorScheme.primary,
                onClick = onImport,
                enabled = !importing,
                showChevron = false,
            )
        }
    }
}

/** One local plugin: its id, app and action count, or why it isn't used (orange tile); Remove asks first. */
@Composable
private fun LocalPluginRow(entry: LocalPluginEntry, onRemove: (LocalPluginEntry) -> Unit) {
    val plugin = entry.plugin
    val name = plugin?.name ?: entry.id
    val subtitle = if (plugin != null) {
        val actions = plugin.actions.size
        "${plugin.id} · ${plugin.androidPackages.firstOrNull().orEmpty()} · $actions ${if (actions == 1) "action" else "actions"}"
    } else {
        "Not used: " + entry.errors.firstOrNull().orEmpty() + if (entry.errors.size > 1) " (+${entry.errors.size - 1} more)" else ""
    }
    ListRow(
        title = name,
        subtitle = subtitle,
        subtitleMaxLines = 4,
        leading = {
            if (plugin != null) Monogram(name, size = 32.dp) else IconTile(Icons.Filled.Warning, Accents.Orange)
        },
        trailing = { TertiaryButton("Remove", onClick = { onRemove(entry) }, destructive = true) },
    )
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
        confirmButton = { TertiaryButton("OK", onClick = onDismiss) },
    )
}
