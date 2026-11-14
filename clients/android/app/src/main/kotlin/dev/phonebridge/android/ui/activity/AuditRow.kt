package dev.phonebridge.android.ui.activity

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.phonebridge.android.ui.components.CodeBlock
import dev.phonebridge.android.ui.components.StatusPill
import dev.phonebridge.android.ui.format.TimeFormat
import dev.phonebridge.android.ui.theme.StatusColors
import dev.phonebridge.core.audit.AuditEntry

/**
 * One audit entry: outcome, tool, agent, time. When [onToggle] is set the row expands to show the
 * detail, arguments preview, duration and transport; otherwise [onClick] (if any) handles taps.
 */
@Composable
internal fun AuditRow(
    entry: AuditEntry,
    time: String,
    colors: StatusColors,
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
    onToggle: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    showAgent: Boolean = true,
    horizontalPadding: Dp = 16.dp,
) {
    val outcomeColor = outcomeColor(AuditPresentation.category(entry.outcome), colors)
    val clickModifier = when {
        onToggle != null -> Modifier
            .clickable(onClickLabel = if (expanded) "Hide details" else "Show details", onClick = onToggle)
            .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
        onClick != null -> Modifier.clickable(onClickLabel = "Open activity", onClick = onClick)
        else -> Modifier
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(clickModifier)
            .animateContentSize()
            .padding(horizontal = horizontalPadding, vertical = 12.dp),
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
            if (onToggle != null) {
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
        Text(
            listOfNotNull(
                if (showAgent) AuditPresentation.tokenLabel(entry) else null,
                AuditPresentation.transportLabel(entry.transport),
                TimeFormat.duration(entry.durationMs),
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (expanded) AuditDetails(entry)
    }
}

@Composable
private fun AuditDetails(entry: AuditEntry) {
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        entry.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        entry.argsPreview?.let {
            Text("Arguments", style = MaterialTheme.typography.labelMedium)
            CodeBlock(it)
        }
        Text(
            "Method ${entry.method} · ${AuditPresentation.transportLabel(entry.transport)} · took ${TimeFormat.duration(entry.durationMs)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

internal fun outcomeColor(category: OutcomeCategory, colors: StatusColors): Color = when (category) {
    OutcomeCategory.SUCCESS -> colors.ok
    OutcomeCategory.ERROR -> colors.error
    OutcomeCategory.DENIED -> colors.warning
}
