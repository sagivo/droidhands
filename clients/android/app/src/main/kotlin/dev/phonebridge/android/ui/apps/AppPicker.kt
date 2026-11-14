package dev.phonebridge.android.ui.apps

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.phonebridge.android.ui.components.EmptyState

/**
 * Searchable app list with checkboxes, used by the per-agent app picker and the never-allow list.
 * Stateless: the caller owns [selected] and [query]. [lockedReason] explains locked rows.
 */
@Composable
internal fun AppPickerList(
    apps: List<AppInfo>?,
    selected: Set<String>,
    query: String,
    onQueryChange: (String) -> Unit,
    onToggle: (String) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    locked: Set<String> = emptySet(),
    lockedSelected: Boolean = false,
    lockedReason: String = "",
    suggested: Set<String> = emptySet(),
    header: LazyListScope.() -> Unit = {},
) {
    val sections = remember(apps, selected, locked, suggested, query, lockedSelected) {
        apps?.let { AppSelection.sections(it, selected, locked, suggested, query, lockedSelected) }
    }
    LazyColumn(contentPadding = contentPadding, modifier = modifier) {
        header()
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
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        when {
            sections == null -> item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            sections.all { it.rows.isEmpty() } -> item(key = "empty") {
                EmptyState(
                    title = if (query.isBlank()) "No apps found" else "No matching apps",
                    message = if (query.isBlank()) "PhoneBridge couldn't list the installed apps." else "Try another name or package.",
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
                                modifier = Modifier
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                                    .semantics { heading() },
                            )
                        }
                    }
                }
                items(section.rows, key = { "${section.key}-${it.app.packageName}" }) { row ->
                    AppRowItem(row, lockedReason, onToggle = { onToggle(row.app.packageName) })
                }
            }
        }
    }
}

@Composable
private fun AppRowItem(row: AppRow, lockedReason: String, onToggle: () -> Unit) {
    val subtitle = when {
        row.locked -> lockedReason
        !row.installed -> "Not installed · tap to remove"
        else -> row.app.packageName
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = row.selected, enabled = !row.locked, role = Role.Checkbox, onValueChange = { onToggle() })
            .semantics { if (row.locked) stateDescription = lockedReason }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(row.app.packageName, installed = row.installed)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(row.app.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        if (row.locked) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(end = 12.dp))
        }
        Checkbox(checked = row.selected, onCheckedChange = null, enabled = !row.locked)
    }
}

/** The app's launcher icon, loaded off the main thread; a neutral placeholder until then. */
@Composable
internal fun AppIcon(packageName: String, installed: Boolean = true, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { ICON_SIZE.roundToPx() }
    var icon by remember(packageName) { mutableStateOf<ImageBitmap?>(LaunchableApps.cachedIcon(packageName)) }
    if (installed && icon == null) {
        LaunchedEffect(packageName) { icon = LaunchableApps.icon(context, packageName, sizePx) }
    }
    val bitmap = icon
    if (bitmap != null) {
        Image(bitmap = bitmap, contentDescription = null, modifier = modifier.size(ICON_SIZE))
    } else {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.medium, modifier = modifier.size(ICON_SIZE)) {}
    }
}

private val ICON_SIZE = 40.dp
