package dev.phonebridge.android.ui.grant

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.phonebridge.android.ui.apps.AppIcon
import dev.phonebridge.android.ui.apps.AppListing
import dev.phonebridge.android.ui.apps.AppRowInset
import dev.phonebridge.android.ui.apps.ListLoading
import dev.phonebridge.android.ui.apps.SECTION_SPACING
import dev.phonebridge.android.ui.apps.SearchField
import dev.phonebridge.android.ui.apps.lazyListSection
import dev.phonebridge.android.ui.components.EmptyState
import dev.phonebridge.android.ui.components.ListRow

/**
 * The grant's app picker: every installed app with its icon, name and the Apps tab's badge ("Integration ·
 * 4 actions", "Basic · 2 actions"), searchable, with a checkbox, as inset grouped lists. Never-allow apps are
 * shown locked. Stateless. Shown in place of the grant (the same window, so the pairing screen keeps its
 * protections).
 *
 * [contentPadding] carries no horizontal gutter (pass `padded(horizontal = 0.dp)`): the search field and the
 * groups add the 16dp screen gutter themselves.
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
            SearchField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = "Search apps",
                modifier = Modifier.padding(horizontal = GUTTER, vertical = 8.dp),
            )
        }
        when {
            sections == null -> item(key = "loading") { ListLoading() }
            sections.all { it.rows.isEmpty() } -> item(key = "empty") {
                EmptyState(
                    title = if (query.isBlank()) "No apps found" else "No matching apps",
                    message = if (query.isBlank()) "PhoneBridge couldn't list the installed apps." else "Try another name.",
                )
            }
            else -> sections.filter { it.rows.isNotEmpty() }.forEachIndexed { index, section ->
                lazyListSection(
                    key = "section-${section.key}",
                    rows = section.rows,
                    rowKey = { "${section.key}-${it.packageName}" },
                    title = section.title,
                    dividerInset = AppRowInset,
                    gutter = GUTTER,
                    spaceAbove = if (index == 0) 8.dp else SECTION_SPACING,
                ) { row -> PickerRow(row, onToggle = { onToggle(row.packageName) }) }
            }
        }
    }
}

@Composable
private fun PickerRow(row: GrantAppRow, onToggle: () -> Unit) {
    val subtitle = when {
        row.locked -> GrantApps.LOCKED
        row.listing == null -> GrantApps.NOT_INSTALLED
        else -> row.badge?.let { listOfNotNull(it.text, it.status).joinToString(" · ") }
    }
    ListRow(
        title = row.label,
        subtitle = subtitle,
        subtitleMaxLines = 2,
        leading = { AppIcon(row.packageName, installed = row.listing != null, label = row.label) },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (row.locked) {
                    Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp))
                }
                Checkbox(checked = row.selected, onCheckedChange = null, enabled = !row.locked)
            }
        },
        modifier = Modifier
            .toggleable(value = row.selected, enabled = !row.locked, role = Role.Checkbox, onValueChange = { onToggle() })
            .semantics { if (row.locked) stateDescription = GrantApps.LOCKED },
    )
}

private val GUTTER = 16.dp
