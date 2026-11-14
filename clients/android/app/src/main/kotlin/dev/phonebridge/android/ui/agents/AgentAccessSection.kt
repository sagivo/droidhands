package dev.phonebridge.android.ui.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.phonebridge.android.ui.components.CardPadding
import dev.phonebridge.android.ui.components.ChoiceChip
import dev.phonebridge.android.ui.components.ListDivider
import dev.phonebridge.android.ui.components.ListRow
import dev.phonebridge.android.ui.components.ListSection
import dev.phonebridge.android.ui.components.Notice
import dev.phonebridge.android.ui.components.NoticeKind
import dev.phonebridge.android.ui.components.RowValue
import dev.phonebridge.android.ui.setup.SetupLogic
import dev.phonebridge.android.ui.theme.statusColors
import dev.phonebridge.android.ui.tokens.ExpiryOption
import dev.phonebridge.android.ui.tokens.POLICY_ORDER
import dev.phonebridge.android.ui.tokens.TokenPresentation
import dev.phonebridge.core.auth.ConfirmPolicy
import dev.phonebridge.core.tools.Scopes

/**
 * "What this agent may do" (access level presets, or custom capabilities), "Ask me before" (when to
 * ask for approval) and "Expires", as three list sections with their warnings below. Edits go to
 * the draft; the page's Save bar persists them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AgentAccessSection(
    draft: AgentDraft,
    currentExpiresAt: Long?,
    now: Long,
    onChange: ((AgentDraft) -> AgentDraft) -> Unit,
) {
    var showScopes by rememberSaveable { mutableStateOf(draft.accessLevel == AccessLevel.CUSTOM) }
    val level = draft.accessLevel
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ListSection(title = "What this agent may do", footer = level.description) {
                FlowRow(
                    modifier = Modifier.padding(CardPadding),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AccessLevel.entries.forEach { option ->
                        ChoiceChip(
                            text = option.label,
                            selected = level == option,
                            onClick = {
                                if (option == AccessLevel.CUSTOM) showScopes = true else onChange { it.withLevel(option) }
                            },
                            showCheck = true,
                        )
                    }
                }
                ListDivider()
                ListRow(
                    title = if (showScopes) "Hide capabilities" else "Show capabilities",
                    trailing = { ExpandValue("${draft.scopes.size} of ${Scopes.ALL.size}", expanded = showScopes) },
                    onClick = { showScopes = !showScopes },
                    modifier = Modifier.semantics { stateDescription = if (showScopes) "Expanded" else "Collapsed" },
                )
                if (showScopes) {
                    Scopes.DESCRIPTIONS.forEach { (scope, description) ->
                        ListDivider(ControlRowInset)
                        ScopeOptionRow(
                            scope = scope,
                            description = description,
                            checked = scope in draft.scopes,
                            highRisk = scope in TokenPresentation.HIGH_RISK_SCOPES,
                            onToggle = { onChange { it.toggleScope(scope) } },
                        )
                    }
                }
            }
            draft.scopesError?.let { ErrorText(it) }
            // Presets describe their cap above; a custom selection says whether it has one.
            if (level == AccessLevel.CUSTOM) {
                draft.capNotice?.let { Notice(it, kind = if (draft.maxRisk == null) NoticeKind.WARNING else NoticeKind.INFO) }
            }
            val highRisk = draft.scopes intersect TokenPresentation.HIGH_RISK_SCOPES
            if (highRisk.isNotEmpty()) {
                Notice(
                    "${highRisk.joinToString(" and ")} give the agent full control of any app on this phone. Keep approvals on.",
                    kind = NoticeKind.WARNING,
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ListSection(title = "Ask me before") {
                Column(Modifier.selectableGroup()) {
                    POLICY_ORDER.forEachIndexed { index, policy ->
                        if (index > 0) ListDivider(ControlRowInset)
                        PolicyOptionRow(policy, selected = draft.policy == policy, onSelect = { onChange { it.copy(policy = policy) } })
                    }
                }
            }
            if (draft.policy == ConfirmPolicy.NEVER) {
                Notice(TokenPresentation.NEVER_POLICY_WARNING, kind = NoticeKind.DANGER, title = "No approvals")
            }
        }

        ListSection(
            title = "Expires",
            footer = TokenPresentation.expiryText(currentExpiresAt, now) +
                (draft.expiry?.let { " · after saving: " + if (it.ttlMillis == null) "never expires" else "expires in ${it.label}" } ?: ""),
        ) {
            FlowRow(
                modifier = Modifier.padding(CardPadding),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ExpiryOption.entries.forEach { option ->
                    ChoiceChip(
                        text = option.label,
                        selected = draft.expiry == option,
                        // Tapping the chosen option again keeps the current expiry.
                        onClick = { onChange { it.copy(expiry = if (it.expiry == option) null else option) } },
                    )
                }
            }
        }
    }
}

/** A capability checkbox row: short label, scope id, description, and a warning icon for [highRisk]. */
@Composable
private fun ScopeOptionRow(scope: String, description: String, checked: Boolean, highRisk: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
            .defaultMinSize(minHeight = 56.dp)
            .padding(horizontal = CardPadding, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(SetupLogic.shortLabel(scope), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.width(8.dp))
                Text(
                    scope,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (highRisk) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = "High risk",
                tint = statusColors().warning,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/** A confirmation-policy radio row with its explanation; "Never" is red. */
@Composable
private fun PolicyOptionRow(policy: ConfirmPolicy, selected: Boolean, onSelect: () -> Unit) {
    ListRow(
        title = TokenPresentation.policyLabel(policy) + if (policy == ConfirmPolicy.SENSITIVE) " (recommended)" else "",
        subtitle = TokenPresentation.policyDescription(policy),
        titleColor = if (policy == ConfirmPolicy.NEVER) MaterialTheme.colorScheme.error else Color.Unspecified,
        leading = { RadioButton(selected = selected, onClick = null) },
        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
    )
}

/** Trailing "value ⌄" of a row that shows or hides the rows below it. */
@Composable
internal fun ExpandValue(value: String?, expanded: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (value != null) RowValue(value)
        Icon(
            if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

/** A validation error under a list section, aligned with the row text. */
@Composable
internal fun ErrorText(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = CardPadding),
    )
}

/** Divider inset that lines up with the text of rows led by a checkbox or radio button (24dp). */
internal val ControlRowInset = CardPadding + 24.dp + 14.dp
