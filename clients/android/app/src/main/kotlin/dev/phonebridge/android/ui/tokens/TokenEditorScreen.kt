package dev.phonebridge.android.ui.tokens

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.phonebridge.android.ui.components.AppCard
import dev.phonebridge.android.ui.components.BottomActionBar
import dev.phonebridge.android.ui.components.ButtonSize
import dev.phonebridge.android.ui.components.CardPadding
import dev.phonebridge.android.ui.components.ChoiceChip
import dev.phonebridge.android.ui.components.ListDivider
import dev.phonebridge.android.ui.components.ListRow
import dev.phonebridge.android.ui.components.ListSection
import dev.phonebridge.android.ui.components.Notice
import dev.phonebridge.android.ui.components.NoticeKind
import dev.phonebridge.android.ui.components.PrimaryButton
import dev.phonebridge.android.ui.components.ScreenScaffold
import dev.phonebridge.android.ui.components.SectionHeader
import dev.phonebridge.android.ui.components.TertiaryButton
import dev.phonebridge.android.ui.components.padded
import dev.phonebridge.android.ui.setup.SetupLogic
import dev.phonebridge.android.ui.theme.statusColors
import dev.phonebridge.core.auth.ConfirmPolicy
import dev.phonebridge.core.tools.Scopes

/** Policies in the order offered: recommended first, the dangerous one last. */
internal val POLICY_ORDER = listOf(ConfirmPolicy.SENSITIVE, ConfirmPolicy.ALL_ACTIONS, ConfirmPolicy.NEVER)

/** Inset of a [ListDivider] after a [ScopeRow] or [PolicyRow], lining up with their text. */
internal val ControlRowInset = CardPadding + 24.dp + 14.dp

/** "New token" form: name, scopes (presets + per-scope), approval policy and expiry. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TokenEditorScreen(
    draft: TokenDraft,
    creating: Boolean,
    onChange: ((TokenDraft) -> TokenDraft) -> Unit,
    onCancel: () -> Unit,
    onCreate: () -> Unit,
) {
    BackHandler(onBack = onCancel)
    ScreenScaffold(
        title = "New token",
        navigationIcon = { IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, contentDescription = "Cancel") } },
        bottomBar = {
            BottomActionBar {
                TertiaryButton("Cancel", onClick = onCancel)
                PrimaryButton(
                    "Create token",
                    onClick = onCreate,
                    enabled = draft.canCreate,
                    loading = creating,
                    size = ButtonSize.LARGE,
                    modifier = Modifier.weight(1f),
                )
            }
        },
    ) { padding ->
        LazyColumn(contentPadding = padding.padded(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item(key = "name") {
                AppCard {
                    OutlinedTextField(
                        value = draft.name,
                        onValueChange = { value -> onChange { it.copy(name = value) } },
                        label = { Text("Name") },
                        supportingText = { Text(draft.nameError ?: "Shown in approval prompts and the activity log.") },
                        isError = draft.nameError != null,
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            item(key = "scopes") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionHeader("What the agent may do")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.selectableGroup(),
                    ) {
                        Scopes.PRESETS.keys.forEach { preset ->
                            ChoiceChip(
                                preset,
                                selected = draft.selectedPreset == preset,
                                onClick = { onChange { it.withPreset(preset) } },
                                showCheck = true,
                            )
                        }
                    }
                    ListSection(footer = TokenPresentation.capNotice(draft.scopes, draft.maxRisk)) {
                        Scopes.DESCRIPTIONS.entries.forEachIndexed { index, (scope, description) ->
                            if (index > 0) ListDivider(ControlRowInset)
                            ScopeRow(
                                scope = scope,
                                description = description,
                                checked = scope in draft.scopes,
                                highRisk = scope in TokenPresentation.HIGH_RISK_SCOPES,
                                onToggle = { onChange { it.toggle(scope) } },
                            )
                        }
                    }
                    draft.scopesError?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = CardPadding),
                        )
                    }
                    if (draft.highRiskScopes.isNotEmpty()) {
                        Notice(
                            "${draft.highRiskScopes.joinToString(" and ")} give the agent full control of any app on this " +
                                "phone. Keep approvals on.",
                            kind = NoticeKind.WARNING,
                        )
                    }
                }
            }

            item(key = "policy") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ListSection(title = "Ask me before") {
                        Column(Modifier.selectableGroup()) {
                            POLICY_ORDER.forEachIndexed { index, policy ->
                                if (index > 0) ListDivider(ControlRowInset)
                                PolicyRow(policy, selected = draft.policy == policy, onSelect = { onChange { it.copy(policy = policy) } })
                            }
                        }
                    }
                    if (draft.policy == ConfirmPolicy.NEVER) {
                        Notice(TokenPresentation.NEVER_POLICY_WARNING, kind = NoticeKind.DANGER, title = "No approvals")
                    }
                }
            }

            item(key = "expiry") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionHeader("Expires")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.selectableGroup(),
                    ) {
                        ExpiryOption.entries.forEach { option ->
                            ChoiceChip(option.label, selected = draft.expiry == option, onClick = { onChange { it.copy(expiry = option) } })
                        }
                    }
                }
            }
        }
    }
}

/**
 * A scope checkbox row, laid out like a [ListRow] for a [ListSection]: short label, scope id,
 * description, and a warning icon for [highRisk]. The whole row toggles.
 */
@Composable
internal fun ScopeRow(scope: String, description: String, checked: Boolean, highRisk: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
            .defaultMinSize(minHeight = 56.dp)
            .padding(horizontal = CardPadding, vertical = 10.dp),
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

/** A confirmation-policy radio row with its explanation, laid out like a [ListRow]. "Never" is red. */
@Composable
internal fun PolicyRow(policy: ConfirmPolicy, selected: Boolean, onSelect: () -> Unit) {
    val label = TokenPresentation.policyLabel(policy) + if (policy == ConfirmPolicy.SENSITIVE) " (recommended)" else ""
    ListRow(
        title = label,
        subtitle = TokenPresentation.policyDescription(policy),
        leading = { RadioButton(selected = selected, onClick = null) },
        titleColor = if (policy == ConfirmPolicy.NEVER) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
    )
}
