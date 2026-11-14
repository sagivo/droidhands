package dev.phonebridge.android.ui.agents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.phonebridge.android.ui.apps.ActionInfo
import dev.phonebridge.android.ui.apps.AppIcon
import dev.phonebridge.core.auth.ConfirmPolicy

/**
 * Header of the per-action rules: app actions are grouped by app and collapsed, so the list stays short;
 * the rows are separate list items ([ActionRulesSearch], [ActionGroupHeader], [ActionRuleRow]).
 */
@Composable
internal fun ActionRulesHeader(summary: String, available: Boolean, loading: Boolean, appCount: Int, expanded: Boolean, onToggle: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("App action rules", style = MaterialTheme.typography.titleMedium)
            Text(
                "Allow, Ask or Block single actions of an app, such as Spotify's \"Play music\". Agents run them with " +
                    "app_action_run.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                !available -> Text(
                    "This agent can't run app actions: it needs the Apps capability and an access level above Read-only " +
                        "(and no Block rule on app_action_run).",
                    style = MaterialTheme.typography.bodyMedium,
                )
                else -> {
                    Text(summary, style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = onToggle, enabled = appCount > 0) {
                        Text(
                            when {
                                appCount == 0 && loading -> "Loading apps…"
                                appCount == 0 -> "No app actions available"
                                expanded -> "Hide app actions"
                                else -> "Edit rules (${if (appCount == 1) "1 app" else "$appCount apps"})"
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Search over app names and action titles inside the expanded list. */
@Composable
internal fun ActionRulesSearch(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text("Search apps or actions") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Filled.Clear, contentDescription = "Clear search") }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** One app in the list: icon, name, "3 actions · 1 rule"; tap to show or hide its actions. */
@Composable
internal fun ActionGroupHeader(group: ActionRuleGroup, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = if (expanded) "Hide actions" else "Show actions", onClick = onToggle)
            .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(group.packageName)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(group.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(ActionRules.groupSubtitle(group), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null)
    }
}

/** One app action with the choices that apply to it ([ActionRules.choices]). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ActionRuleRow(action: ActionInfo, choice: RuleChoice, policy: ConfirmPolicy, onChoose: (RuleChoice) -> Unit) {
    val choices = ActionRules.choices(action)
    Column(Modifier.fillMaxWidth().padding(start = 52.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(action.title, style = MaterialTheme.typography.bodyLarge)
        Text(
            "${ToolRules.riskLabel(action.risk)} · ${ActionRules.meaning(choice, action, policy)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            action.toolName,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            choices.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = option == choice,
                    onClick = { onChoose(option) },
                    shape = SegmentedButtonDefaults.itemShape(index, choices.size),
                    icon = {},
                    modifier = Modifier.semantics { contentDescription = "${option.label} for ${action.title}" },
                ) { Text(option.label, maxLines = 1) }
            }
        }
    }
}
