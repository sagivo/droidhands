package dev.phonebridge.android.ui.activity

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonebridge.android.ui.MainViewModel
import dev.phonebridge.android.ui.components.CodeBlock
import dev.phonebridge.android.ui.components.ConfirmDialog
import dev.phonebridge.android.ui.components.EmptyState
import dev.phonebridge.android.ui.components.ScreenScaffold
import dev.phonebridge.android.ui.components.StatusPill
import dev.phonebridge.android.ui.components.padded
import dev.phonebridge.android.ui.components.rememberNow
import dev.phonebridge.android.ui.components.rememberTimestampFormatter
import dev.phonebridge.android.ui.format.TimeFormat
import dev.phonebridge.android.ui.theme.StatusColors
import dev.phonebridge.android.ui.theme.statusColors
import dev.phonebridge.core.audit.AuditEntry

/** Audit log: every agent request handled on this phone, newest first, with filters. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActivityScreen(vm: MainViewModel) {
    val entries by vm.auditEntries.collectAsStateWithLifecycle()
    val now by rememberNow()
    val formatTime = rememberTimestampFormatter()
    var filter by rememberSaveable { mutableStateOf(AuditFilter.ALL) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }

    val counts = remember(entries) { AuditPresentation.counts(entries) }
    val visible = remember(entries, filter) { AuditPresentation.filter(entries, filter) }
    val keys = remember(visible) { AuditPresentation.stableKeys(visible) }
    val colors = statusColors()

    ScreenScaffold(
        title = "Activity",
        actions = {
            if (entries.isNotEmpty()) {
                IconButton(onClick = { confirmClear = true }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Clear activity log")
                }
            }
        },
    ) { padding ->
        LazyColumn(contentPadding = padding.padded(horizontal = 0.dp)) {
            item(key = "filters") {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    AuditFilter.entries.forEach { f ->
                        FilterChip(
                            selected = filter == f,
                            onClick = { filter = f },
                            label = { Text("${f.label} (${counts[f] ?: 0})") },
                        )
                    }
                }
            }
            if (visible.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        title = if (entries.isEmpty()) "No activity yet" else "Nothing here",
                        message = if (entries.isEmpty()) {
                            "Every request from an agent (allowed, denied or failed) shows up here. The log stays on this phone."
                        } else {
                            if (filter == AuditFilter.ERRORS) "No errors in the log." else "No denied requests in the log."
                        },
                        icon = Icons.AutoMirrored.Filled.List,
                    )
                }
            }
            itemsIndexed(visible, key = { index, _ -> keys[index] }) { index, entry ->
                val key = keys[index]
                AuditRow(
                    entry = entry,
                    time = formatTime(entry.timestamp, now),
                    expanded = expanded[key] == true,
                    onToggle = { expanded[key] = expanded[key] != true },
                    colors = colors,
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            }
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = "Clear activity log?",
            text = "All ${entries.size} entries are deleted from this phone. This can't be undone.",
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
private fun AuditRow(entry: AuditEntry, time: String, expanded: Boolean, onToggle: () -> Unit, colors: StatusColors) {
    val outcomeColor = outcomeColor(AuditPresentation.category(entry.outcome), colors)
    val hasDetails = entry.argsPreview != null || entry.detail != null
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = hasDetails, onClickLabel = if (expanded) "Hide details" else "Show details", onClick = onToggle)
            .semantics { if (hasDetails) stateDescription = if (expanded) "Expanded" else "Collapsed" }
            .animateContentSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusPill(AuditPresentation.label(entry.outcome), outcomeColor)
            Spacer(Modifier.width(8.dp))
            Text(
                AuditPresentation.title(entry),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (hasDetails) {
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
        Text(
            listOf(
                AuditPresentation.tokenLabel(entry),
                AuditPresentation.transportLabel(entry.transport),
                TimeFormat.duration(entry.durationMs),
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (expanded) {
            entry.detail?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            }
            entry.argsPreview?.let {
                Text("Arguments", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
                CodeBlock(it)
            }
        }
    }
}

private fun outcomeColor(category: OutcomeCategory, colors: StatusColors): Color = when (category) {
    OutcomeCategory.SUCCESS -> colors.ok
    OutcomeCategory.ERROR -> colors.error
    OutcomeCategory.DENIED -> colors.warning
}
