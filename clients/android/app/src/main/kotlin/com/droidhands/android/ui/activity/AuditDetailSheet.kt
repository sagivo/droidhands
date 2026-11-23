package com.droidhands.android.ui.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.droidhands.android.ui.apps.ActionNames
import com.droidhands.android.ui.components.CodeBlock
import com.droidhands.android.ui.components.IconBadge
import com.droidhands.android.ui.components.ListDivider
import com.droidhands.android.ui.components.SecondaryButton
import com.droidhands.android.ui.components.SectionTitle
import com.droidhands.android.ui.components.StatusPill
import com.droidhands.android.ui.components.rememberCopier
import com.droidhands.android.ui.format.TimeFormat
import com.droidhands.android.ui.theme.StatusColors
import com.droidhands.android.ui.theme.TINT_ALPHA
import com.droidhands.core.audit.AuditEntry
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Everything about one logged request, in a bottom sheet: what was done and how it ended, why it
 * failed (as the agent was told), the arguments in readable form, and the technical details. [time]
 * is the full timestamp ("Today, 12:44:07 PM").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AuditDetailSheet(
    entry: AuditEntry,
    time: String,
    colors: StatusColors,
    names: ActionNames.Index,
    onDismiss: () -> Unit,
) {
    val copy = rememberCopier()
    val category = AuditPresentation.category(entry.outcome)
    val color = outcomeColor(category, colors)
    val title = AuditPresentation.displayTitle(entry, names)
    val locale: Locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val arguments = remember(entry.argsPreview, locale) {
        val dateTime = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale)
        val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
        AuditPresentation.argumentRows(entry.argsPreview, ZoneId.systemDefault(), dateTime::format, date::format)
    }
    val reason = entry.detail?.takeIf { it.isNotBlank() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(outcomeIcon(category), color, size = 48.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(title.title, style = MaterialTheme.typography.titleLarge)
                    StatusPill(AuditPresentation.label(entry.outcome), color)
                }
            }

            if (reason != null) {
                if (category == OutcomeCategory.SUCCESS) {
                    Text(reason, style = MaterialTheme.typography.bodyMedium)
                } else {
                    ReasonCallout(
                        heading = if (category == OutcomeCategory.ERROR) "What went wrong" else "Why it was refused",
                        text = reason,
                        color = color,
                    )
                }
            }

            if (arguments != null) {
                DetailSection("Request") {
                    arguments.forEachIndexed { i, row ->
                        if (i > 0) ListDivider(0.dp)
                        DetailRow(row.label, row.value)
                    }
                }
            } else {
                entry.argsPreview?.takeIf { it.isNotBlank() }?.let { raw ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionTitle("Request", Modifier.padding(start = 4.dp))
                        CodeBlock(raw)
                    }
                }
            }

            DetailSection("Details") {
                DetailRow("Agent", AuditPresentation.tokenLabel(entry))
                ListDivider(0.dp)
                DetailRow("Time", time)
                ListDivider(0.dp)
                DetailRow("Connection", AuditPresentation.transportLabel(entry.transport))
                ListDivider(0.dp)
                DetailRow("Response time", TimeFormat.duration(entry.durationMs))
                ListDivider(0.dp)
                DetailRow(if (entry.tool.isNullOrBlank()) "Method" else "Tool", AuditPresentation.title(entry), monospace = true)
            }

            SecondaryButton(
                "Copy details",
                onClick = { copy("Activity details", AuditPresentation.report(entry, names, time), false) },
                fullWidth = true,
            )
        }
    }
}

/** A failure's reason, tinted with the outcome [color]. The text was written for the agent, so it says so. */
@Composable
private fun ReasonCallout(heading: String, text: String, color: Color) {
    Surface(color = color.copy(alpha = TINT_ALPHA), shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(heading, style = MaterialTheme.typography.titleSmall, color = color)
            SelectionContainer { Text(text, style = MaterialTheme.typography.bodyMedium) }
            Text(
                "This is the message the agent received.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A titled card of [DetailRow]s. */
@Composable
private fun DetailSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(title, Modifier.padding(start = 4.dp))
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 16.dp), content = content)
        }
    }
}

/** Label on the start side, value on the end side (wrapping, selectable). */
@Composable
private fun DetailRow(label: String, value: String, monospace: Boolean = false) {
    Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        SelectionContainer(Modifier.weight(1f)) {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = if (monospace) FontFamily.Monospace else null,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

