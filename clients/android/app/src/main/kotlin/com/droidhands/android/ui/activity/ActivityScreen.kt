package com.droidhands.android.ui.activity

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidhands.android.ui.MainViewModel
import com.droidhands.android.ui.apps.SearchField
import com.droidhands.android.ui.apps.lazyListSection
import com.droidhands.android.ui.components.ChoiceChip
import com.droidhands.android.ui.components.ConfirmDialog
import com.droidhands.android.ui.components.DropdownSelector
import com.droidhands.android.ui.components.EmptyState
import com.droidhands.android.ui.components.MenuAction
import com.droidhands.android.ui.components.OverflowMenu
import com.droidhands.android.ui.components.ScreenScaffold
import com.droidhands.android.ui.components.TertiaryButton
import com.droidhands.android.ui.components.padded
import com.droidhands.android.ui.components.rememberDayFormatter
import com.droidhands.android.ui.components.rememberNow
import com.droidhands.android.ui.components.rememberTimeOfDayFormatter
import com.droidhands.android.ui.theme.statusColors
import com.droidhands.core.audit.AuditEntry
import java.time.ZoneId

/**
 * Audit log: every agent request handled on this phone, newest first, grouped by day. Rows say what
 * was done and, for failures, why; tapping one opens its details. Search and filters show up once
 * they can narrow something down.
 */
@Composable
fun ActivityScreen(vm: MainViewModel) {
    LaunchedEffect(Unit) { vm.loadAppActions() }
    val entries by vm.auditEntries.collectAsStateWithLifecycle()
    val names by vm.actionNames.collectAsStateWithLifecycle()
    val tokens by vm.tokens.collectAsStateWithLifecycle()
    val now by rememberNow()
    val formatTime = rememberTimeOfDayFormatter(seconds = false)
    val formatFullTime = rememberTimeOfDayFormatter()
    val formatDay = rememberDayFormatter()
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf<AuditEntry?>(null) }
    val query = vm.activityQuery
    val zone = ZoneId.systemDefault()
    val today = remember(now, zone) { ActivityFilters.dayOf(now, zone) }

    val options = remember(entries, tokens) { ActivityFilters.agentOptions(entries, tokens) }
    // Outcome counts honor the other filters, so each chip says what tapping it would show.
    val forCounts = remember(entries, query.agent, query.search, names) { ActivityFilters.apply(entries, query.copy(outcome = AuditFilter.ALL), names) }
    val counts = remember(forCounts) { AuditPresentation.counts(forCounts) }
    val visible = remember(entries, query, names) { ActivityFilters.apply(entries, query, names) }
    val days = remember(visible, zone) { daySections(ActivityFilters.rows(visible, zone)) }
    val colors = statusColors()

    // Only offer what can narrow the list: a search once it is long, an agent choice when there are
    // several, outcome chips when there is more than one outcome. Active filters always stay visible.
    val showSearch = entries.size >= SEARCH_MIN_ENTRIES || query.search.isNotBlank()
    val showAgents = options.size > 2 || query.agent != null
    val outcomes = AuditFilter.entries.filter { it != AuditFilter.ALL && (counts[it] ?: 0) > 0 }
    val showOutcomes = outcomes.size > 1 || query.outcome != AuditFilter.ALL

    ScreenScaffold(
        title = "Activity",
        actions = {
            if (entries.isNotEmpty()) {
                OverflowMenu(listOf(MenuAction("Clear activity log", destructive = true) { confirmClear = true }))
            }
        },
    ) { padding ->
        LazyColumn(contentPadding = padding.padded(horizontal = 0.dp)) {
            if (showSearch) {
                item(key = "search") {
                    SearchField(
                        value = query.search,
                        onValueChange = { vm.activityQuery = query.copy(search = it) },
                        placeholder = "Search by tool or app",
                        modifier = Modifier.padding(horizontal = GUTTER, vertical = 4.dp),
                    )
                }
            }
            if (showAgents || showOutcomes) {
                item(key = "filters") {
                    // One swipeable row, edge to edge: the agent, then the outcomes with their counts.
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = GUTTER, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (showAgents) {
                            DropdownSelector(
                                label = "Agent",
                                options = options,
                                selected = options.firstOrNull { it.agent == query.agent } ?: options.first(),
                                optionLabel = { it.label },
                                onSelect = { vm.activityQuery = query.copy(agent = it.agent) },
                            )
                        }
                        if (showOutcomes) {
                            (listOf(AuditFilter.ALL) + outcomes + listOfNotNull(query.outcome.takeIf { it != AuditFilter.ALL && it !in outcomes }))
                                .forEach { f ->
                                    ChoiceChip(
                                        text = if (f == AuditFilter.ALL) f.label else "${f.label} ${counts[f] ?: 0}",
                                        selected = query.outcome == f,
                                        onClick = { vm.activityQuery = query.copy(outcome = f) },
                                    )
                                }
                        }
                    }
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
                    title = ActivityFilters.dayLabel(day.header.date, today, formatDay),
                    gutter = GUTTER,
                    dividerInset = ROW_TEXT_INSET,
                    spaceAbove = if (index == 0) 8.dp else 20.dp,
                ) { row ->
                    AuditRow(
                        entry = row.entry,
                        time = formatTime(row.entry.timestamp),
                        colors = colors,
                        onClick = { selected = row.entry },
                        names = names,
                    )
                }
            }
        }
    }

    selected?.let { entry ->
        val day = ActivityFilters.dayLabel(ActivityFilters.dayOf(entry.timestamp, zone), today, formatDay)
        AuditDetailSheet(
            entry = entry,
            time = "$day, ${formatFullTime(entry.timestamp)}",
            colors = colors,
            names = names,
            onDismiss = { selected = null },
        )
    }

    if (confirmClear) {
        ConfirmDialog(
            title = "Clear activity log?",
            text = "All ${entries.size} entries are deleted from this phone. Usage totals per agent are kept. This can't be undone.",
            confirmLabel = "Clear",
            destructive = true,
            onConfirm = {
                selected = null
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

/** Below this many entries the list is short enough to scan, so there is no search field. */
private const val SEARCH_MIN_ENTRIES = 8

/** Dividers start at the row text: [AuditRow]'s padding, badge and gap. */
private val ROW_TEXT_INSET = 16.dp + 36.dp + 12.dp
