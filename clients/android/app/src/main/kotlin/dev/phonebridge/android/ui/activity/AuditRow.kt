package dev.phonebridge.android.ui.activity

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.phonebridge.android.ui.apps.ActionNames
import dev.phonebridge.android.ui.apps.ToolName
import dev.phonebridge.android.ui.components.CodeBlock
import dev.phonebridge.android.ui.components.IconBadge
import dev.phonebridge.android.ui.format.TimeFormat
import dev.phonebridge.android.ui.theme.StatusColors
import dev.phonebridge.core.audit.AuditEntry

/**
 * One audit entry: outcome, tool, agent, time. When [onToggle] is set the row expands to show the
 * detail, arguments preview, duration and transport; otherwise [onClick] (if any) handles taps.
 * App actions read as "Spotify – Play music" ([names]); the details show the logged tool name.
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
    names: ActionNames.Index = ActionNames.Index.EMPTY,
) {
    val title = AuditPresentation.displayTitle(entry, names)
    val outcomeColor = outcomeColor(AuditPresentation.category(entry.outcome), colors)
    val clickModifier = when {
        onToggle != null -> Modifier
            .clickable(onClickLabel = if (expanded) "Hide details" else "Show details", onClick = onToggle)
            .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
        onClick != null -> Modifier.clickable(onClickLabel = "Open activity", onClick = onClick)
        else -> Modifier
    }
    val category = AuditPresentation.category(entry.outcome)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(clickModifier)
            .animateContentSize()
            .padding(horizontal = horizontalPadding, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        IconBadge(outcomeIcon(category), outcomeColor, size = 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (onToggle != null) {
                    Icon(
                        if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier
                            .padding(start = 2.dp)
                            .size(20.dp),
                    )
                }
            }
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = outcomeColor, fontWeight = FontWeight.SemiBold)) {
                        append(AuditPresentation.label(entry.outcome))
                    }
                    listOfNotNull(
                        if (showAgent) AuditPresentation.tokenLabel(entry) else null,
                        AuditPresentation.transportLabel(entry.transport),
                        TimeFormat.duration(entry.durationMs),
                    ).forEach {
                        append(" · ")
                        append(it)
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (expanded) AuditDetails(entry, title)
        }
    }
}

/** Check for success, a cross for denied, a warning sign for errors. */
internal fun outcomeIcon(category: OutcomeCategory): ImageVector = when (category) {
    OutcomeCategory.SUCCESS -> Icons.Filled.Check
    OutcomeCategory.DENIED -> Icons.Filled.Close
    OutcomeCategory.ERROR -> Icons.Filled.Warning
}

@Composable
private fun AuditDetails(entry: AuditEntry, title: ToolName) {
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (title.isAppAction) {
            Text(
                "App action ${title.raw}",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
