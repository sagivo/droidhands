package dev.phonebridge.android.ui.agents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.phonebridge.android.ui.apps.ActionInfo
import dev.phonebridge.android.ui.apps.AppIcon
import dev.phonebridge.android.ui.components.Accents
import dev.phonebridge.android.ui.components.AppsIcon
import dev.phonebridge.android.ui.components.CardPadding
import dev.phonebridge.android.ui.components.IconTile
import dev.phonebridge.android.ui.components.ListRow
import dev.phonebridge.core.auth.ConfirmPolicy

/** Explanation under the app action rules list. */
internal const val ACTION_RULES_FOOTER =
    "Allow, Ask or Block single actions of an app, such as Spotify's \"Play music\". Agents run them with app_action_run."

/**
 * First row of the per-action rules: the rules in force, and a toggle that shows the apps. App actions are
 * grouped by app and collapsed, so the list stays short; the rows are separate list items
 * ([ActionRulesSearch], [ActionGroupHeader], [ActionRuleRow]). Says why when the agent can't run actions.
 */
@Composable
internal fun ActionRulesToggleRow(summary: String, available: Boolean, loading: Boolean, appCount: Int, expanded: Boolean, onToggle: () -> Unit) {
    if (!available) {
        ListRow(
            title = "Not available for this agent",
            subtitle = "It needs the Apps capability and an access level above Read-only (and no Block rule on app_action_run).",
            leading = { IconTile(AppsIcon, Accents.Gray) },
        )
        return
    }
    ListRow(
        title = when {
            appCount == 0 && loading -> "Loading apps…"
            appCount == 0 -> "No app actions available"
            expanded -> "Hide app actions"
            else -> "Edit rules"
        },
        subtitle = summary,
        leading = { IconTile(AppsIcon, Accents.Purple) },
        trailing = if (appCount > 0) {
            { ExpandValue(if (appCount == 1) "1 app" else "$appCount apps", expanded) }
        } else {
            null
        },
        onClick = if (appCount > 0) onToggle else null,
        modifier = if (appCount > 0) Modifier.semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" } else Modifier,
    )
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
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = CardPadding, vertical = 12.dp),
    )
}

/** One app in the list: icon, name, "3 actions · 1 rule"; tap to show or hide its actions. */
@Composable
internal fun ActionGroupHeader(group: ActionRuleGroup, expanded: Boolean, onToggle: () -> Unit) {
    ListRow(
        title = group.label,
        subtitle = ActionRules.groupSubtitle(group),
        subtitleMaxLines = 1,
        leading = { AppIcon(group.packageName) },
        trailing = { ExpandValue(null, expanded) },
        modifier = Modifier
            .clickable(onClickLabel = if (expanded) "Hide actions" else "Show actions", onClick = onToggle)
            .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" },
    )
}

/** One app action with the choices that apply to it ([ActionRules.choices]), indented under its app. */
@Composable
internal fun ActionRuleRow(action: ActionInfo, choice: RuleChoice, policy: ConfirmPolicy, onChoose: (RuleChoice) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = ActionRowInset, end = CardPadding, top = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
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
        RuleChoiceChips(ActionRules.choices(action), choice, action.title, onChoose)
    }
}

/** Start inset of action rows and their dividers: the text column of the app rows above (40dp icon). */
internal val ActionRowInset = CardPadding + 40.dp + 14.dp
