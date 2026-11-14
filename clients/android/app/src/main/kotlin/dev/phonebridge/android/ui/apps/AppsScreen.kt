package dev.phonebridge.android.ui.apps

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonebridge.android.ui.components.AppsIcon
import dev.phonebridge.android.ui.components.EmptyState
import dev.phonebridge.android.ui.components.ScreenScaffold
import dev.phonebridge.android.ui.components.StatusPill
import dev.phonebridge.android.ui.components.padded
import dev.phonebridge.android.ui.theme.statusColors

/**
 * Apps tab: every launchable app with what agents can do in it ("Integration · 4 actions", "Basic · 2 actions"),
 * searchable by app or action; tap an app for its actions and controls.
 */
@Composable
fun AppsScreen(vm: AppsViewModel, onOpenApp: (String) -> Unit) {
    LaunchedEffect(Unit) { vm.refresh() }
    val apps by vm.apps.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val blocked by vm.neverAllowApps.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val sections = remember(apps, blocked, query) { apps?.let { AppsPresentation.sections(it, blocked, query) } }

    ScreenScaffold(
        title = "Apps",
        actions = {
            if (loading) {
                CircularProgressIndicator(Modifier.padding(end = 16.dp).size(20.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = { vm.refresh(force = true) }) { Icon(Icons.Filled.Refresh, contentDescription = "Scan apps again") }
            }
        },
    ) { padding ->
        LazyColumn(contentPadding = padding.padded(horizontal = 0.dp)) {
            item(key = "intro") {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        apps?.let { AppsPresentation.summary(it, blocked) + " Agents pick an app and one of its actions; " +
                            "each action is checked and approved like any other request." }
                            ?: "Looking at the apps on this phone…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Search apps or actions") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, contentDescription = "Clear search") }
                            }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    )
                }
            }
            when {
                sections == null -> item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
                sections.all { it.rows.isEmpty() } -> item(key = "empty") {
                    EmptyState(
                        title = if (query.isBlank()) "No apps found" else "No matching apps",
                        message = if (query.isBlank()) "PhoneBridge couldn't list the installed apps." else "Try another app name or action.",
                        icon = AppsIcon,
                    )
                }
                else -> sections.forEach { section ->
                    if (section.rows.isEmpty()) return@forEach
                    section.title?.let { title ->
                        item(key = "section-${section.key}") { SectionTitle(title) }
                    }
                    items(section.rows, key = { "${section.key}-${it.app.packageName}" }) { row ->
                        AppListRow(row, onClick = { onOpenApp(row.app.packageName) })
                        HorizontalDivider(Modifier.padding(start = 72.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
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

@Composable
private fun AppListRow(row: AppsRow, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Show actions", onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(row.app.packageName)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(row.app.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (row.blocked) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(end = 4.dp).size(14.dp))
                    Text("Blocked for all agents", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                BadgeRow(row.badge)
            }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.padding(start = 4.dp))
    }
}

/** The app's badge (and plugin status) as tinted pills. */
@Composable
internal fun BadgeRow(badge: AppBadge) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        StatusPill(badge.text, badgeColor(badge.kind))
        badge.status?.let { StatusPill(it, statusColors().warning) }
    }
}

@Composable
internal fun badgeColor(kind: BadgeKind): Color = when (kind) {
    BadgeKind.INTEGRATION -> MaterialTheme.colorScheme.primary
    BadgeKind.LOCAL -> statusColors().warning
    BadgeKind.BASIC -> MaterialTheme.colorScheme.onSurfaceVariant
}
