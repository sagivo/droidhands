package dev.phonebridge.android.ui.activity

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonebridge.android.ui.MainViewModel
import dev.phonebridge.android.ui.apps.SearchField
import dev.phonebridge.android.ui.apps.lazyListSection
import dev.phonebridge.android.ui.components.CardPadding
import dev.phonebridge.android.ui.components.ChoiceChip
import dev.phonebridge.android.ui.components.ConfirmDialog
import dev.phonebridge.android.ui.components.DropdownSelector
import dev.phonebridge.android.ui.components.EmptyState
import dev.phonebridge.android.ui.components.ScreenScaffold
import dev.phonebridge.android.ui.components.TertiaryButton
import dev.phonebridge.android.ui.components.padded
import dev.phonebridge.android.ui.components.rememberDayFormatter
import dev.phonebridge.android.ui.components.rememberNow
import dev.phonebridge.android.ui.components.rememberTimeOfDayFormatter
import dev.phonebridge.android.ui.theme.statusColors
import java.time.ZoneId

/**
 * Audit log: every agent request handled on this phone, newest first, grouped by day, filterable
 * by agent, outcome and tool. Tapping a row shows its details.
 */
@Composable
fun ActivityScreen(vm: MainViewModel) {
    LaunchedEffect(Unit) { vm.loadAppActions() }
    val entries by vm.auditEntries.collectAsStateWithLifecycle()
    val names by vm.actionNames.collectAsStateWithLifecycle()
    val tokens by vm.tokens.collectAsStateWithLifecycle()
    val now by rememberNow()
    val formatTime = rememberTimeOfDayFormatter()
    val formatDay = rememberDayFormatter()
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val query = vm.activityQuery
    val zone = ZoneId.systemDefault()
    val today = remember(now, zone) { ActivityFilters.dayOf(now, zone) }

    val options = remember(entries, tokens) { ActivityFilters.agentOptions(entries, tokens) }
    // Outcome counts honor the other filters, so each chip says what tapping it would show.
    val forCounts = remember(entries, query.agent, query.search, names) { ActivityFilters.apply(entries, query.copy(outcome = AuditFilter.ALL), names) }
    val counts = remember(forCounts) { AuditPresentation.counts(forCounts) }
    val visible = remember(entries, query, names) { ActivityFilters.apply(entries, query, names) }
    val days = remember(visible, zone) { daySections(ActivityFilters.rows(visible, zone)) }
    val summary = remember(visible) { ActivityFilters.summaryText(ActivityFilters.summary(visible)) }
    val colors = statusColors()

    ScreenScaffold(
        title = "Activity",
        large = true,
        actions = {
            if (entries.isNotEmpty()) {
                IconButton(onClick = { confirmClear = true }) { Icon(Icons.Filled.Delete, contentDescription = "Clear activity log") }
            }
        },
    ) { padding ->
        LazyColumn(contentPadding = padding.padded(horizontal = 0.dp)) {
            item(key = "search") {
                SearchField(
                    value = query.search,
                    onValueChange = { vm.activityQuery = query.copy(search = it) },
                    placeholder = "Search by tool or app, e.g. whatsapp",
                    modifier = Modifier.padding(horizontal = GUTTER),
                )
            }
            item(key = "filters") {
                // One swipeable row, edge to edge: agent first, then the outcomes with their counts.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = GUTTER, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DropdownSelector(
                        label = "Agent",
                        options = options,
                        selected = options.firstOrNull { it.agent == query.agent } ?: options.first(),
                        optionLabel = { it.label },
                        onSelect = { vm.activityQuery = query.copy(agent = it.agent) },
                        modifier = Modifier.width(IntrinsicSize.Max),
                    )
                    AuditFilter.entries.forEach { f ->
                        ChoiceChip(
                            text = "${f.label} ${counts[f] ?: 0}",
                            selected = query.outcome == f,
                            onClick = { vm.activityQuery = query.copy(outcome = f) },
                            // A chip measures its label with a weight, which needs a bounded width inside a scrolling row.
                            modifier = Modifier.width(IntrinsicSize.Max),
                        )
                    }
                }
            }
            if (entries.isNotEmpty()) {
                item(key = "summary") {
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = GUTTER + CardPadding),
                    )
                }
            }
            if (visible.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        title = if (entries.isEmpty()) "No activity yet" else "Nothing matches",
                        message = if (entries.isEmpty()) {
                            "Every request from an agent (allowed, denied or failed) shows up here. The log stays on this phone."
                        } else {
                            "No requests match these filters."
                        },
                        icon = Icons.AutoMirrored.Filled.List,
                        action = if (query.isFiltered && entries.isNotEmpty()) {
                            { TertiaryButton("Clear filters", onClick = { vm.activityQuery = ActivityQuery() }) }
                        } else {
                            null
                        },
                    )
                }
            }
            days.forEachIndexed { index, day ->
                lazyListSection(
                    key = day.header.key,
                    rows = day.items,
                    rowKey = { it.key },
                    title = "${ActivityFilters.dayLabel(day.header.date, today, formatDay)} · ${day.header.count}",
                    gutter = GUTTER,
                    spaceAbove = if (index == 0) 12.dp else 20.dp,
                ) { row ->
                    AuditRow(
                        entry = row.entry,
                        time = formatTime(row.entry.timestamp),
                        colors = colors,
                        expanded = expanded[row.key] == true,
                        onToggle = { expanded[row.key] = expanded[row.key] != true },
                        names = names,
                    )
                }
            }
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = "Clear activity log?",
            text = "All ${entries.size} entries are deleted from this phone. Usage totals per agent are kept. This can't be undone.",
            confirmLabel = "Clear",
            destructive = true,
            onConfirm = {
                expanded.clear()
                vm.clearAudit()
            },
            onDismiss = { confirmClear = false },
        )
    }
}

/** One day of the log: its header and its entries, newest first. */
private class DaySection(val header: ActivityRow.DayHeader, val items: List<ActivityRow.Item>)

/** [rows] (headers each followed by that day's items) as one [DaySection] per day. */
private fun daySections(rows: List<ActivityRow>): List<DaySection> {
    val sections = ArrayList<DaySection>()
    var header: ActivityRow.DayHeader? = null
    var items = ArrayList<ActivityRow.Item>()
    rows.forEach { row ->
        when (row) {
            is ActivityRow.DayHeader -> {
                header?.let { sections += DaySection(it, items) }
                header = row
                items = ArrayList()
            }
            is ActivityRow.Item -> items += row
        }
    }
    header?.let { sections += DaySection(it, items) }
    return sections
}

/** Screen gutter: the list runs edge to edge so the filter row can scroll under the screen edges. */
private val GUTTER = 16.dp
