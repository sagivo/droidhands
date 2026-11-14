package dev.phonebridge.android.ui.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
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
import dev.phonebridge.android.ui.apps.ActionNames
import dev.phonebridge.android.ui.components.StatusDot
import dev.phonebridge.android.ui.theme.statusColors
import dev.phonebridge.core.audit.TokenUsage
import java.time.LocalDate

/**
 * Usage card: calls per day (14 days), totals by outcome, and the most-used tools. App actions read as
 * "Spotify – Play music" ([names]) with their raw tool name underneath.
 */
@Composable
internal fun AgentUsageCard(usage: TokenUsage?, today: LocalDate, formatDay: (LocalDate) -> String, names: ActionNames.Index = ActionNames.Index.EMPTY) {
    val days = remember(usage, today) { UsageStats.lastDays(usage?.perDay.orEmpty(), today) }
    val top = remember(usage) { UsageStats.topTools(usage?.perTool.orEmpty()) }
    val colors = statusColors()
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (usage == null || usage.totalCalls == 0L) {
                Text(
                    "No calls yet. Usage appears here once the agent uses the phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            UsageChart(days, formatDay)
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Total("All time", usage.totalCalls, null)
                Total("OK", usage.okCalls, colors.ok)
                Total("Errors", usage.errorCalls, colors.error)
                Total("Denied", usage.deniedCalls, colors.warning)
            }
            if (top.isNotEmpty()) {
                HorizontalDivider()
                Text("Most used tools", style = MaterialTheme.typography.titleSmall)
                top.forEach { item ->
                    val name = ActionNames.display(item.tool, names)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (name.isAppAction) {
                            Column(Modifier.weight(1f)) {
                                Text(name.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    name.raw,
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        } else {
                            Text(
                                item.tool,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Text(
                            "${UsageStats.compact(item.count)} · ${UsageStats.percent(item.count, usage.totalCalls)}%",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** A stat: label, value, and a status dot (identity never by color alone: the label says it). */
@Composable
private fun Total(label: String, value: Long, color: Color?) {
    Column(Modifier.semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.Start) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (color != null) StatusDot(color, Modifier.padding(end = 6.dp), size = 8.dp)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(UsageStats.compact(value), style = MaterialTheme.typography.titleLarge)
    }
}
