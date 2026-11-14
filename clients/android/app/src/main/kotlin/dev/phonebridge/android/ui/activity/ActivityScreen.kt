package dev.phonebridge.android.ui.activity

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonebridge.android.ui.MainViewModel
import dev.phonebridge.android.ui.components.ConfirmDialog
import dev.phonebridge.android.ui.components.DropdownSelector
import dev.phonebridge.android.ui.components.EmptyState
import dev.phonebridge.android.ui.components.ScreenScaffold
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
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun ActivityScreen(vm: MainViewModel) {
    val entries by vm.auditEntries.collectAsStateWithLifecycle()
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
    val forCounts = remember(entries, query.agent, query.search) { ActivityFilters.apply(entries, query.copy(outcome = AuditFilter.ALL)) }
    val counts = remember(forCounts) { AuditPresentation.counts(forCounts) }
    val visible = remember(entries, query) { ActivityFilters.apply(entries, query) }
    val rows = remember(visible, zone) { ActivityFilters.rows(visible, zone) }
    val summary = remember(visible) { ActivityFilters.summaryText(ActivityFilters.summary(visible)) }
    val colors = statusColors()

    ScreenScaffold(
        title = "Activity",
        actions = {
            if (entries.isNotEmpty()) {
                IconButton(onClick = { confirmClear = true }) { Icon(Icons.Filled.Delete, contentDescription = "Clear activity log") }
            }
        },
    ) { padding ->
        LazyColumn(contentPadding = padding.padded(horizontal = 0.dp)) {
            item(key = "filters") {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = query.search,
                        onValueChange = { vm.activityQuery = query.copy(search = it) },
                        placeholder = { Text("Search by tool, e.g. whatsapp") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = {
                            if (query.search.isNotEmpty()) {
                                IconButton(onClick = { vm.activityQuery = query.copy(search = "") }) {
                                    Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                                }
                            }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    DropdownSelector(
                        label = "Agent",
                        options = options,
                        selected = options.firstOrNull { it.agent == query.agent } ?: options.first(),
                        optionLabel = { it.label },
                        onSelect = { vm.activityQuery = query.copy(agent = it.agent) },
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AuditFilter.entries.forEach { f ->
                            FilterChip(
                                selected = query.outcome == f,
                                onClick = { vm.activityQuery = query.copy(outcome = f) },
                                label = { Text("${f.label} (${counts[f] ?: 0})") },
                            )
                        }
                    }
                    if (entries.isNotEmpty()) {
                        Text(
                            summary,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                }
            }
            if (visible.isEmpty()) {
                item(key = "empty") {
                    Column {
                        EmptyState(
                            title = if (entries.isEmpty()) "No activity yet" else "Nothing matches",
                            message = if (entries.isEmpty()) {
                                "Every request from an agent (allowed, denied or failed) shows up here. The log stays on this phone."
                            } else {
                                "No requests match these filters."
                            },
                            icon = Icons.AutoMirrored.Filled.List,
                        )
                        if (query.isFiltered && entries.isNotEmpty()) {
                            TextButton(
                                onClick = { vm.activityQuery = ActivityQuery() },
                                modifier = Modifier.align(Alignment.CenterHorizontally),
                            ) { Text("Clear filters") }
                        }
                    }
                }
            }
            rows.forEach { row ->
                when (row) {
                    is ActivityRow.DayHeader -> stickyHeader(key = row.key) {
                        DayHeader(ActivityFilters.dayLabel(row.date, today, formatDay), row.count)
                    }
                    is ActivityRow.Item -> item(key = row.key) {
                        AuditRow(
                            entry = row.entry,
                            time = formatTime(row.entry.timestamp),
                            colors = colors,
                            expanded = expanded[row.key] == true,
                            onToggle = { expanded[row.key] = expanded[row.key] != true },
                        )
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    }
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

@Composable
private fun DayHeader(label: String, count: Int) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Text(
            "$label · $count",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .semantics { heading() },
        )
    }
}
