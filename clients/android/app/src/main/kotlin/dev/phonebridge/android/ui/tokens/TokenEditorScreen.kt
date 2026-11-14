package dev.phonebridge.android.ui.tokens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import dev.phonebridge.android.ui.components.BottomActionBar
import dev.phonebridge.android.ui.components.Notice
import dev.phonebridge.android.ui.components.NoticeKind
import dev.phonebridge.android.ui.components.ScreenScaffold
import dev.phonebridge.android.ui.components.SectionHeader
import dev.phonebridge.android.ui.components.padded
import dev.phonebridge.android.ui.setup.SetupLogic
import dev.phonebridge.android.ui.theme.statusColors
import dev.phonebridge.core.auth.ConfirmPolicy
import dev.phonebridge.core.tools.Scopes

/** Policies in the order offered: recommended first, the dangerous one last. */
internal val POLICY_ORDER = listOf(ConfirmPolicy.SENSITIVE, ConfirmPolicy.ALL_ACTIONS, ConfirmPolicy.NEVER)

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
                Button(onClick = onCreate, enabled = draft.canCreate && !creating) {
                    if (creating) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Create token")
                }
            }
        },
    ) { padding ->
        LazyColumn(contentPadding = padding.padded(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "name") {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { value -> onChange { it.copy(name = value) } },
                    label = { Text("Name") },
                    supportingText = { Text(draft.nameError ?: "Shown in approval prompts and the activity log.") },
                    isError = draft.nameError != null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item(key = "scopes-header") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeader("What the agent may do")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Scopes.PRESETS.keys.forEach { preset ->
                            val selected = draft.selectedPreset == preset
                            FilterChip(
                                selected = selected,
                                onClick = { onChange { it.withPreset(preset) } },
                                label = { Text(preset) },
                                leadingIcon = if (selected) {
                                    { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                    TokenPresentation.capNotice(draft.scopes, draft.maxRisk)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    draft.scopesError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
            items(Scopes.DESCRIPTIONS.entries.toList(), key = { "scope-${it.key}" }) { (scope, description) ->
                ScopeRow(
                    scope = scope,
                    description = description,
                    checked = scope in draft.scopes,
                    highRisk = scope in TokenPresentation.HIGH_RISK_SCOPES,
                    onToggle = { onChange { it.toggle(scope) } },
                )
            }
            if (draft.highRiskScopes.isNotEmpty()) {
                item(key = "high-risk") {
                    Notice(
                        "${draft.highRiskScopes.joinToString(" and ")} give the agent full control of any app on this " +
                            "phone. Keep approvals on.",
                        kind = NoticeKind.WARNING,
                    )
                }
            }

            item(key = "policy") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SectionHeader("Ask me before")
                    Column(Modifier.selectableGroup()) {
                        POLICY_ORDER.forEach { policy ->
                            PolicyRow(policy, selected = draft.policy == policy, onSelect = { onChange { it.copy(policy = policy) } })
                        }
                    }
                    if (draft.policy == ConfirmPolicy.NEVER) {
                        Notice(TokenPresentation.NEVER_POLICY_WARNING, kind = NoticeKind.DANGER, title = "No approvals")
                    }
                }
            }

            item(key = "expiry") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeader("Expires")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ExpiryOption.entries.forEach { option ->
                            FilterChip(
                                selected = draft.expiry == option,
                                onClick = { onChange { it.copy(expiry = option) } },
                                label = { Text(option.label) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A scope checkbox row: short label, scope id, description, and a warning icon for [highRisk]. */
@Composable
internal fun ScopeRow(scope: String, description: String, checked: Boolean, highRisk: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
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
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

/** A confirmation-policy radio row with its explanation. */
@Composable
internal fun PolicyRow(policy: ConfirmPolicy, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            val label = TokenPresentation.policyLabel(policy) + if (policy == ConfirmPolicy.SENSITIVE) " (recommended)" else ""
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (policy == ConfirmPolicy.NEVER) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                TokenPresentation.policyDescription(policy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
