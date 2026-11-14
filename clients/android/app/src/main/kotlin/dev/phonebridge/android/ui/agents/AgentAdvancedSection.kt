package dev.phonebridge.android.ui.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.phonebridge.android.ui.apps.AppInfo
import dev.phonebridge.android.ui.apps.AppSelection
import dev.phonebridge.android.ui.components.Accents
import dev.phonebridge.android.ui.components.AppsIcon
import dev.phonebridge.android.ui.components.CardPadding
import dev.phonebridge.android.ui.components.ChoiceChip
import dev.phonebridge.android.ui.components.IconRowInset
import dev.phonebridge.android.ui.components.IconTile
import dev.phonebridge.android.ui.components.ListDivider
import dev.phonebridge.android.ui.components.ListRow
import dev.phonebridge.android.ui.components.ListSection
import dev.phonebridge.android.ui.components.Notice
import dev.phonebridge.android.ui.components.NoticeKind
import dev.phonebridge.android.ui.components.RowValue
import dev.phonebridge.android.ui.components.SectionTitle
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
internal fun AppAccessSection(
    draft: AgentDraft,
    apps: List<AppInfo>?,
    neverAllowCount: Int,
    onChange: ((AgentDraft) -> AgentDraft) -> Unit,
    onChooseApps: () -> Unit,
) {
    val policy = draft.appPolicy
    val neverAllowNote = if (neverAllowCount > 0) {
        " ${if (neverAllowCount == 1) "1 app is" else "$neverAllowCount apps are"} blocked for every agent " +
            "(Settings > Security > Never-allow apps)."
    } else {
        ""
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ListSection(
            title = "App access",
            footer = "Which apps this agent may read and operate on screen, in notifications and when opening apps. " +
                "Contacts, SMS and calendar are controlled by the capabilities above." + neverAllowNote,
        ) {
            Column(Modifier.selectableGroup()) {
                APP_MODES.forEachIndexed { index, (mode, label) ->
                    if (index > 0) ListDivider(ControlRowInset)
                    ListRow(
                        title = label,
                        leading = { RadioButton(selected = policy.mode == mode, onClick = null) },
                        modifier = Modifier.selectable(selected = policy.mode == mode, role = Role.RadioButton) {
                            onChange { it.withAppMode(mode) }
                        },
                    )
                }
            }
            if (policy.mode != AppAccessMode.ALL) {
                val count = policy.packages.size
                ListDivider()
                ListRow(
                    title = "Choose apps",
                    subtitle = (if (policy.mode == AppAccessMode.ALLOWLIST) "Allowed: " else "Blocked: ") +
                        if (apps == null) "$count ${if (count == 1) "app" else "apps"}" else AppSelection.summary(policy.packages, apps),
                    leading = { IconTile(AppsIcon, Accents.Blue) },
                    onClick = onChooseApps,
                )
            }
        }
        if (policy.mode != AppAccessMode.ALL) {
            draft.appPolicyWarning?.let { Notice(it, kind = NoticeKind.WARNING) }
        }
    }
}

/** Explanation under the tool rules list. */
internal const val TOOL_RULES_FOOTER =
    "Override the approval setting for single tools: Allow never asks, Ask always asks, Block hides the tool from this agent."

/**
 * First row of the per-tool rules list: the rules in force, and a toggle that shows or hides one
 * row per tool ([ToolGroupHeader], [ToolRuleRow], separate list items).
 */
@Composable
internal fun ToolRulesToggleRow(summary: String, expanded: Boolean, toolCount: Int, onToggle: () -> Unit) {
    ListRow(
        title = when {
            toolCount == 0 -> "No tools available"
            expanded -> "Hide tools"
            else -> "Edit rules"
        },
        subtitle = summary,
        leading = { IconTile(Icons.Filled.Build, Accents.Teal) },
        trailing = if (toolCount > 0) {
            { ExpandValue(if (toolCount == 1) "1 tool" else "$toolCount tools", expanded) }
        } else {
            null
        },
        onClick = if (toolCount > 0) onToggle else null,
        modifier = if (toolCount > 0) Modifier.semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" } else Modifier,
    )
}

/** A capability title inside the expanded tool rules list. */
@Composable
internal fun ToolGroupHeader(scope: String) {
    SectionTitle(SetupLogic.shortLabel(scope), Modifier.padding(start = CardPadding, end = CardPadding, top = 16.dp, bottom = 4.dp))
}

/** One tool with its Default / Allow / Ask / Block choice ([ToolRules.choices]). */
@Composable
internal fun ToolRuleRow(tool: ToolInfo, choice: RuleChoice, policy: ConfirmPolicy, onChoose: (RuleChoice) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = CardPadding, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(tool.title, style = MaterialTheme.typography.bodyLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tool.name, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                " · ${ToolRules.riskLabel(tool.risk)} · ${ToolRules.meaning(choice, tool, policy)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        RuleChoiceChips(ToolRules.choices(tool, choice), choice, tool.title, onChoose)
    }
}

/** The rule choices of one tool or app action as a row of chips; TalkBack reads "Allow for Send SMS". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RuleChoiceChips(choices: List<RuleChoice>, choice: RuleChoice, subject: String, onChoose: (RuleChoice) -> Unit) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        choices.forEach { option ->
            ChoiceChip(
                text = option.label,
                selected = option == choice,
                onClick = { onChoose(option) },
                modifier = Modifier.semantics { contentDescription = "${option.label} for $subject" },
            )
        }
    }
}

/** "Active hours": the agent may act only inside a daily window, e.g. 08:00–23:00. */
@Composable
internal fun ActiveHoursSection(
    hours: ActiveHours?,
    is24Hour: Boolean,
    error: String?,
    onChange: (ActiveHours?) -> Unit,
) {
    var picking by rememberSaveable { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ListSection(
            title = "Active hours",
            footer = "Outside these hours every request from this agent is refused (phone's local time).",
        ) {
            SwitchRow(
                title = "Only at certain times",
                subtitle = Schedule.describe(hours, is24Hour),
                checked = hours != null,
                onCheckedChange = { on -> onChange(if (on) Schedule.DEFAULT else null) },
                leading = { IconTile(Icons.Filled.DateRange, Accents.Orange) },
            )
            if (hours != null) {
                ListDivider(IconRowInset)
                ListRow(
                    title = "From",
                    trailing = { RowValue(Schedule.time(hours.startMinute, is24Hour)) },
                    onClick = { picking = START },
                    leading = { Spacer(Modifier.width(TILE_SIZE)) },
                )
                ListDivider(IconRowInset)
                ListRow(
                    title = "Until",
                    subtitle = if (error == null) {
                        "${Schedule.length(hours)} a day" + if (Schedule.isOvernight(hours)) ", across midnight" else ""
                    } else {
                        null
                    },
                    trailing = { RowValue(Schedule.time(hours.endMinute, is24Hour)) },
                    onClick = { picking = END },
                    leading = { Spacer(Modifier.width(TILE_SIZE)) },
                )
            }
        }
        if (hours != null && error != null) ErrorText(error)
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

/** Width of an [IconTile], so rows under a tiled row line up with its text. */
private val TILE_SIZE = 32.dp

private const val START = "start"
private const val END = "end"
