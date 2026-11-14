package dev.phonebridge.android.ui.grant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.phonebridge.android.ui.apps.AppIcon
import dev.phonebridge.android.ui.apps.AppListing
import dev.phonebridge.android.ui.apps.BadgeRow
import dev.phonebridge.android.ui.components.EmptyState

/**
 * The grant's app picker: every installed app with its icon, name and the Apps tab's badge ("Integration ·
 * 4 actions", "Basic · 2 actions"), searchable, with a checkbox. Never-allow apps are shown locked. Stateless.
 * Shown in place of the grant (the same window, so the pairing screen keeps its protections).
 */
@Composable
internal fun GrantAppPicker(
    apps: List<AppListing>?,
    selected: Set<String>,
    neverAllow: Set<String>,
    query: String,
    onQueryChange: (String) -> Unit,
    onToggle: (String) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val sections = remember(apps, selected, neverAllow, query) { apps?.let { GrantApps.sections(it, selected, neverAllow, query) } }
    LazyColumn(contentPadding = contentPadding, modifier = modifier) {
        item(key = "search") {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text("Search apps") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Filled.Clear, contentDescription = "Clear search") }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        when {
            sections == null -> item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            sections.all { it.rows.isEmpty() } -> item(key = "empty") {
                EmptyState(
                    title = if (query.isBlank()) "No apps found" else "No matching apps",
                    message = if (query.isBlank()) "PhoneBridge couldn't list the installed apps." else "Try another name.",
                )
            }
            else -> sections.forEach { section ->
                if (section.rows.isEmpty()) return@forEach
                section.title?.let { title ->
                    item(key = "section-${section.key}") {
                        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                title,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() },
                            )
                        }
                    }
                }
                items(section.rows, key = { "${section.key}-${it.packageName}" }) { row ->
                    PickerRow(row, onToggle = { onToggle(row.packageName) })
                }
            }
        }
    }
}

@Composable
private fun PickerRow(row: GrantAppRow, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = row.selected, enabled = !row.locked, role = Role.Checkbox, onValueChange = { onToggle() })
            .semantics { if (row.locked) stateDescription = GrantApps.LOCKED }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(row.packageName, installed = row.listing != null)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(row.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            when {
                row.locked -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(end = 4.dp).size(14.dp))
                    Text(GrantApps.LOCKED, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                row.listing == null -> Text(GrantApps.NOT_INSTALLED, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> BadgeRow(row.badge!!)
            }
        }
        Spacer(Modifier.width(8.dp))
        Checkbox(checked = row.selected, onCheckedChange = null, enabled = !row.locked)
    }
}
