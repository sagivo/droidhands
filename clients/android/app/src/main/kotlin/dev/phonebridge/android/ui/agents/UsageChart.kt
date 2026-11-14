package dev.phonebridge.android.ui.agents

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min

/**
 * Calls per day as a single-series column chart (Compose Canvas): thin columns with a rounded top
 * growing from one baseline, a hairline at the scale's top value, the first day and "Today" on the
 * axis. Tap a column to read its value. Time runs in the reading direction (mirrored in RTL).
 * TalkBack reads a summary ([UsageStats.chartDescription]) instead of the drawing.
 */
@Composable
internal fun UsageChart(days: List<DayCount>, formatDay: (LocalDate) -> String, modifier: Modifier = Modifier) {
    if (days.isEmpty()) return
    val ceiling = remember(days) { UsageStats.niceCeiling(days.maxOf { it.count }) }
    val total = remember(days) { days.sumOf { it.count } }
    var selected by remember(days) { mutableStateOf<Int?>(null) }
    val description = remember(days) { UsageStats.chartDescription(days, formatDay) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val bar = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val headline = selected?.let { days.getOrNull(it) }?.let { "${formatDay(it.date)}: ${UsageStats.calls(it.count)}" }
            ?: "Last ${days.size} days: ${UsageStats.calls(total)}"
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(headline, style = MaterialTheme.typography.titleSmall)
            Text(UsageStats.compact(ceiling), style = MaterialTheme.typography.labelSmall, color = muted)
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(CHART_HEIGHT)
                .pointerInput(days, rtl) {
                    detectTapGestures { offset ->
                        val slot = size.width.toFloat() / days.size
                        val position = (offset.x / slot).toInt().coerceIn(0, days.size - 1)
                        val index = if (rtl) days.size - 1 - position else position
                        selected = if (selected == index) null else index
                    }
                },
        ) {
            val hairline = 1.dp.toPx()
            val slot = size.width / days.size
            val gap = 2.dp.toPx()
            val barWidth = min(slot - gap, 24.dp.toPx()).coerceAtLeast(1f)
            val radius = min(4.dp.toPx(), barWidth / 2)
            val minVisible = 2.dp.toPx()

            drawLine(grid, Offset(0f, hairline / 2), Offset(size.width, hairline / 2), strokeWidth = hairline)
            drawLine(grid, Offset(0f, size.height - hairline / 2), Offset(size.width, size.height - hairline / 2), strokeWidth = hairline)

            days.forEachIndexed { i, day ->
                if (day.count <= 0) return@forEachIndexed
                val position = if (rtl) days.size - 1 - i else i
                val height = max(minVisible, size.height * (day.count.toFloat() / ceiling))
                val left = position * slot + (slot - barWidth) / 2
                val top = size.height - height
                val path = Path().apply {
                    addRoundRect(
                        RoundRect(
                            left = left, top = top, right = left + barWidth, bottom = size.height,
                            topLeftCornerRadius = CornerRadius(radius), topRightCornerRadius = CornerRadius(radius),
                            bottomRightCornerRadius = CornerRadius.Zero, bottomLeftCornerRadius = CornerRadius.Zero,
                        ),
                    )
                }
                val alpha = if (selected == null || selected == i) 1f else 0.35f
                drawPath(path, bar.copy(alpha = alpha))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatDay(days.first().date), style = MaterialTheme.typography.labelSmall, color = muted)
            Text("Today", style = MaterialTheme.typography.labelSmall, color = muted)
        }
    }
}

private val CHART_HEIGHT = 112.dp
