package com.droidhands.android.ui.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.droidhands.android.ui.apps.ActionNames
import com.droidhands.android.ui.apps.ToolName
import com.droidhands.android.ui.components.Accents
import com.droidhands.android.ui.components.CardPadding
import com.droidhands.android.ui.components.IconTile
import com.droidhands.android.ui.components.ListDivider
import com.droidhands.android.ui.components.ListRow
import com.droidhands.android.ui.components.ListSection
import com.droidhands.android.ui.components.RowValue
import com.droidhands.android.ui.components.StatusDot
import com.droidhands.android.ui.theme.statusColors
import com.droidhands.core.audit.TokenUsage
import java.time.LocalDate

/**
 * "Usage": calls per day (14 days) and totals by outcome, then "Most used tools". App actions read as
 * "Spotify – Play music" ([names]) with their raw tool name underneath.
 */
@Composable
internal fun AgentUsageSection(usage: TokenUsage?, today: LocalDate, formatDay: (LocalDate) -> String, names: ActionNames.Index = ActionNames.Index.EMPTY) {
    val days = remember(usage, today) { UsageStats.lastDays(usage?.perDay.orEmpty(), today) }
    val top = remember(usage) { UsageStats.topTools(usage?.perTool.orEmpty()) }
    val colors = statusColors()
    if (usage == null || usage.totalCalls == 0L) {
        ListSection(title = "Usage") {
            ListRow(
                title = "No calls yet",
                subtitle = "Usage appears here once the agent uses the phone.",
                leading = { IconTile(Icons.AutoMirrored.Filled.List, Accents.Gray) },
            )
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        ListSection(title = "Usage") {
            Column(Modifier.padding(CardPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                UsageChart(days, formatDay)
                ListDivider(inset = 0.dp)
                Row(Modifier.fillMaxWidth()) {
                    Total("OK", usage.okCalls, colors.ok, Modifier.weight(1f))
                    Total("Errors", usage.errorCalls, colors.error, Modifier.weight(1f))
                    Total("Denied", usage.deniedCalls, colors.warning, Modifier.weight(1f))
                }
            }
        }
        if (top.isNotEmpty()) {
            ListSection(title = "Most used tools") {
                top.forEachIndexed { index, item ->
                    if (index > 0) ListDivider()
                    ToolUsageRow(
                        name = ActionNames.display(item.tool, names),
                        value = "${UsageStats.compact(item.count)} · ${UsageStats.percent(item.count, usage.totalCalls)}%",
                    )
                }
            }
        }
    }
}

/** A tool and its share of the calls. Raw tool names are code, so they are set in monospace (under the title when there is one). */
@Composable
private fun ToolUsageRow(name: ToolName, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .defaultMinSize(minHeight = 56.dp)
            .padding(horizontal = CardPadding, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (name.title != name.raw) {
                Text(name.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    name.raw,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(name.raw, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(12.dp))
        RowValue(value)
    }
}

/** A stat: label, value, and a status dot (identity never by color alone: the label says it). */
@Composable
private fun Total(label: String, value: Long, color: Color, modifier: Modifier = Modifier) {
    Column(modifier.semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.Start) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(color, Modifier.padding(end = 6.dp), size = 8.dp)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(UsageStats.compact(value), style = MaterialTheme.typography.titleLarge)
    }
}
