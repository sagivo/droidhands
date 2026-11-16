package com.droidhands.android.ui.apps

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidhands.android.ui.components.AppsIcon
import com.droidhands.android.ui.components.EmptyState
import com.droidhands.android.ui.components.ListRow
import com.droidhands.android.ui.components.ScreenScaffold
import com.droidhands.android.ui.components.StatusPill
import com.droidhands.android.ui.components.padded
import com.droidhands.android.ui.theme.statusColors
import com.droidhands.core.plugins.Integration

/**
 * Apps tab: every launchable app with what agents can do in it, grouped ("Integrations", "All apps")
 * and searchable by app or action; tap an app for its actions and controls.
 */
@Composable
fun AppsScreen(vm: AppsViewModel, onOpenApp: (String) -> Unit) {
    LaunchedEffect(Unit) { vm.refresh() }
    val apps by vm.apps.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val blocked by vm.neverAllowApps.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val sections = remember(apps, blocked, query) { apps?.let { AppsPresentation.sections(it, blocked, query) } }
    val summary = remember(apps, blocked) {
        apps?.let {
            AppsPresentation.summary(it, blocked) + " Agents pick an app and one of its actions; each action follows the " +
                "access you gave the agent."
        }
    }

    ScreenScaffold(
        title = "Apps",
        large = true,
        actions = {
            if (loading) {
                CircularProgressIndicator(Modifier.padding(end = 16.dp).size(20.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = { vm.refresh(force = true) }) { Icon(Icons.Filled.Refresh, contentDescription = "Scan apps again") }
            }
        },
    ) { padding ->
        LazyColumn(contentPadding = padding.padded()) {
            item(key = "search") { SearchField(value = query, onValueChange = { query = it }, placeholder = "Search apps or actions") }
            when {
                sections == null -> item(key = "loading") { ListLoading(text = "Looking at the apps on this phone…") }
                sections.all { it.rows.isEmpty() } -> item(key = "empty") {
                    EmptyState(
                        title = if (query.isBlank()) "No apps found" else "No matching apps",
                        message = if (query.isBlank()) "DroidHands couldn't list the installed apps." else "Try another app name or action.",
                        icon = AppsIcon,
                    )
                }
                else -> {
                    val shown = sections.filter { it.rows.isNotEmpty() }
                    shown.forEach { section ->
                        lazyListSection(
                            key = "section-${section.key}",
                            rows = section.rows,
                            rowKey = { "${section.key}-${it.app.packageName}" },
                            title = section.title,
                            // The summary explains the whole tab: it closes the list, like a settings footnote.
                            footer = summary.takeIf { section == shown.last() },
                            dividerInset = AppRowInset,
                        ) { row -> AppListRow(row, onClick = { onOpenApp(row.app.packageName) }) }
                    }
                }
            }
        }
    }
}

/** An app: its icon, name and action count, with one pill when something matters (blocked, unreviewed, not stable). */
@Composable
private fun AppListRow(row: AppsRow, onClick: () -> Unit) {
    val pill = rowPill(row)
    ListRow(
        title = row.app.label,
        subtitle = AppsPresentation.actionCount(row.app.actions.size),
        leading = { AppIcon(row.app.packageName, label = row.app.label) },
        trailing = if (pill != null) {
            { StatusPill(pill.first, pill.second) }
        } else {
            null
        },
        showChevron = true,
        modifier = Modifier.clickable(onClickLabel = "Show actions", onClick = onClick),
    )
}

/**
 * The one pill an app row shows, most important first: blocked for all agents, an unreviewed local
 * plugin, then a plugin that isn't stable ("Experimental", "Beta"). Null for the ordinary case.
 */
@Composable
private fun rowPill(row: AppsRow): Pair<String, Color>? {
    val colors = statusColors()
    return when {
        row.blocked -> "Blocked" to colors.off
        row.app.integration == Integration.LOCAL -> "Local plugin" to colors.warning
        row.badge.status != null -> row.badge.status to colors.warning
        else -> null
    }
}

/** Color of an app's kind of support ([AppBadge.kind]): brand for integrations, amber for local plugins. */
@Composable
internal fun badgeColor(kind: BadgeKind): Color = when (kind) {
    BadgeKind.INTEGRATION -> MaterialTheme.colorScheme.primary
    BadgeKind.LOCAL -> statusColors().warning
    BadgeKind.BASIC -> MaterialTheme.colorScheme.onSurfaceVariant
}
