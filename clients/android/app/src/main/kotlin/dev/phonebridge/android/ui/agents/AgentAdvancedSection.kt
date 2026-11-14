package dev.phonebridge.android.ui.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.phonebridge.android.ui.apps.AppInfo
import dev.phonebridge.android.ui.apps.AppSelection
import dev.phonebridge.android.ui.components.Notice
import dev.phonebridge.android.ui.components.NoticeKind
import dev.phonebridge.android.ui.components.SwitchRow
import dev.phonebridge.android.ui.components.TimePickerDialog
import dev.phonebridge.android.ui.setup.SetupLogic
import dev.phonebridge.core.auth.ActiveHours
import dev.phonebridge.core.auth.AppAccessMode
import dev.phonebridge.core.auth.ConfirmPolicy

/** App access mode choices, in the order offered. */
private val APP_MODES = listOf(
    AppAccessMode.ALL to "All apps",
    AppAccessMode.ALLOWLIST to "Only selected apps",
    AppAccessMode.BLOCKLIST to "All apps except selected",
)

/**
 * "App access": which apps this agent may see and operate (screen, notifications, opening apps).
 * Apps on the global never-allow list stay blocked whatever is chosen here.
 */
@Composable
internal fun AppAccessCard(
    draft: AgentDraft,
    apps: List<AppInfo>?,
    neverAllowCount: Int,
    onChange: ((AgentDraft) -> AgentDraft) -> Unit,
    onChooseApps: () -> Unit,
) {
    val policy = draft.appPolicy
    AdvancedCard(
        title = "App access",
        subtitle = "Which apps this agent may read and operate on screen, in notifications and when opening apps. " +
            "Contacts, SMS and calendar are controlled by the capabilities above.",
    ) {
        Column(Modifier.selectableGroup()) {
            APP_MODES.forEach { (mode, label) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(selected = policy.mode == mode, role = Role.RadioButton) { onChange { it.withAppMode(mode) } }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = policy.mode == mode, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        if (policy.mode != AppAccessMode.ALL) {
            val count = policy.packages.size
            Text(
                (if (policy.mode == AppAccessMode.ALLOWLIST) "Allowed: " else "Blocked: ") +
                    if (apps == null) "$count ${if (count == 1) "app" else "apps"}" else AppSelection.summary(policy.packages, apps),
                style = MaterialTheme.typography.bodyMedium,
            )
            FilledTonalButton(onClick = onChooseApps) { Text("Choose apps") }
            draft.appPolicyWarning?.let { Notice(it, kind = NoticeKind.WARNING) }
        }
        if (neverAllowCount > 0) {
            Text(
                "${if (neverAllowCount == 1) "1 app is" else "$neverAllowCount apps are"} blocked for every agent " +
                    "(Settings > Security > Never-allow apps).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Header of the per-tool rules list; the rows themselves are separate list items ([ToolRuleRow]). */
@Composable
internal fun ToolRulesHeader(summary: String, expanded: Boolean, toolCount: Int, onToggle: () -> Unit) {
    AdvancedCard(
        title = "Tool rules",
        subtitle = "Override the approval setting for single tools: Allow never asks, Ask always asks, Block hides the tool from this agent.",
    ) {
        Text(summary, style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = onToggle, enabled = toolCount > 0) {
            Text(
                when {
                    toolCount == 0 -> "No tools available"
                    expanded -> "Hide tools"
                    else -> "Edit rules ($toolCount tools)"
                },
            )
        }
    }
}

/** A scope title inside the expanded tool rules list. */
@Composable
internal fun ToolGroupHeader(scope: String) {
    Text(
        SetupLogic.shortLabel(scope),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/** One tool with its Default / Allow / Ask / Block choice. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolRuleRow(tool: ToolInfo, choice: RuleChoice, policy: ConfirmPolicy, onChoose: (RuleChoice) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(tool.title, style = MaterialTheme.typography.bodyLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tool.name, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                " · ${ToolRules.riskLabel(tool.risk)} · ${ToolRules.choiceMeaning(choice, tool.risk, policy)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            RuleChoice.entries.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = option == choice,
                    onClick = { onChoose(option) },
                    shape = SegmentedButtonDefaults.itemShape(index, RuleChoice.entries.size),
                    icon = {},
                    modifier = Modifier.semantics { contentDescription = "${option.label} for ${tool.title}" },
                ) { Text(option.label, maxLines = 1) }
            }
        }
    }
}

/** "Active hours": the agent may act only inside a daily window, e.g. 08:00–23:00. */
@Composable
internal fun ActiveHoursCard(
    hours: ActiveHours?,
    is24Hour: Boolean,
    error: String?,
    onChange: (ActiveHours?) -> Unit,
) {
    var picking by rememberSaveable { mutableStateOf<String?>(null) }
    AdvancedCard(
        title = "Active hours",
        subtitle = "Outside these hours every request from this agent is refused (phone's local time).",
    ) {
        SwitchRow(
            title = "Only at certain times",
            subtitle = Schedule.describe(hours, is24Hour),
            checked = hours != null,
            onCheckedChange = { on -> onChange(if (on) Schedule.DEFAULT else null) },
        )
        if (hours != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { picking = START }) { Text("From ${Schedule.time(hours.startMinute, is24Hour)}") }
                OutlinedButton(onClick = { picking = END }) { Text("Until ${Schedule.time(hours.endMinute, is24Hour)}") }
            }
            if (error != null) {
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            } else {
                Text(
                    "${Schedule.length(hours)} a day" + if (Schedule.isOvernight(hours)) ", across midnight" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    val current = hours
    when (picking) {
        START -> if (current != null) {
            TimePickerDialog("Allowed from", current.startMinute, is24Hour, onConfirm = { onChange(current.copy(startMinute = it)) }, onDismiss = { picking = null })
        }
        END -> if (current != null) {
            TimePickerDialog("Allowed until", current.endMinute, is24Hour, onConfirm = { onChange(current.copy(endMinute = it)) }, onDismiss = { picking = null })
        }
    }
}

@Composable
private fun AdvancedCard(title: String, subtitle: String, content: @Composable () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

private const val START = "start"
private const val END = "end"
