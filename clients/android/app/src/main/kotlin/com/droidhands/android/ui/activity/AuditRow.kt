package com.droidhands.android.ui.activity

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.droidhands.android.ui.apps.ActionNames
import com.droidhands.android.ui.components.IconBadge
import com.droidhands.android.ui.theme.StatusColors
import com.droidhands.core.audit.AuditEntry

/**
 * One audit entry as a list row: what was done ("Create calendar event", "Spotify – Play music" from
 * [names]), when, by which agent and, for anything that didn't succeed, the outcome and why. [onClick]
 * (if any) opens the details.
 */
@Composable
internal fun AuditRow(
    entry: AuditEntry,
    time: String,
    colors: StatusColors,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    showAgent: Boolean = true,
    horizontalPadding: Dp = 16.dp,
    names: ActionNames.Index = ActionNames.Index.EMPTY,
) {
    val category = AuditPresentation.category(entry.outcome)
    val color = outcomeColor(category, colors)
    val failed = category != OutcomeCategory.SUCCESS
    // Successes stay quiet; a failure says what happened and why without opening it.
    val reason = entry.detail?.takeIf { failed && it.isNotBlank() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClickLabel = "Show details", onClick = onClick) else Modifier)
            .padding(horizontal = horizontalPadding, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        IconBadge(outcomeIcon(category), color, size = 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    AuditPresentation.displayTitle(entry, names).title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val meta = buildAnnotatedString {
                if (failed) {
                    withStyle(SpanStyle(color = color, fontWeight = FontWeight.SemiBold)) { append(AuditPresentation.label(entry.outcome)) }
                }
                if (showAgent) {
                    if (failed) append(" · ")
                    append(AuditPresentation.tokenLabel(entry))
                }
            }
            if (meta.isNotEmpty()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (reason != null) {
                Text(
                    reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/** Check for success, a cross for denied, a warning sign for errors. */
internal fun outcomeIcon(category: OutcomeCategory): ImageVector = when (category) {
    OutcomeCategory.SUCCESS -> Icons.Filled.Check
    OutcomeCategory.DENIED -> Icons.Filled.Close
    OutcomeCategory.ERROR -> Icons.Filled.Warning
}

internal fun outcomeColor(category: OutcomeCategory, colors: StatusColors): Color = when (category) {
    OutcomeCategory.SUCCESS -> colors.ok
    OutcomeCategory.ERROR -> colors.error
    OutcomeCategory.DENIED -> colors.warning
}
