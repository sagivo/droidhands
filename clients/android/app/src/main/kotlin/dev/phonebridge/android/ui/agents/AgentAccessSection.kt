package dev.phonebridge.android.ui.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.phonebridge.android.ui.components.Notice
import dev.phonebridge.android.ui.components.NoticeKind
import dev.phonebridge.android.ui.components.SectionHeader
import dev.phonebridge.android.ui.tokens.ExpiryOption
import dev.phonebridge.android.ui.tokens.POLICY_ORDER
import dev.phonebridge.android.ui.tokens.PolicyRow
import dev.phonebridge.android.ui.tokens.ScopeRow
import dev.phonebridge.android.ui.tokens.TokenPresentation
import dev.phonebridge.core.auth.ConfirmPolicy
import dev.phonebridge.core.tools.Scopes

/**
 * "Access": access level (presets, or custom scopes), when to ask for approval, and expiry.
 * Edits go to the draft; the page's Save bar persists them.
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Access")
        Text("What this agent may do", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AccessLevel.entries.forEach { option ->
                val selected = level == option
                FilterChip(
                    selected = selected,
                    onClick = {
                        if (option == AccessLevel.CUSTOM) showScopes = true else onChange { it.withLevel(option) }
                    },
                    label = { Text(option.label) },
                    leadingIcon = if (selected) {
                        { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                    } else {
                        null
                    },
                )
            }
        }
        Text(level.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Presets describe their cap above; a custom selection says whether it has one.
        if (level == AccessLevel.CUSTOM) {
            draft.capNotice?.let { Notice(it, kind = if (draft.maxRisk == null) NoticeKind.WARNING else NoticeKind.INFO) }
        }
        TextButton(onClick = { showScopes = !showScopes }) {
            Text(if (showScopes) "Hide capabilities" else "Show capabilities (${draft.scopes.size} of ${Scopes.ALL.size})")
        }
        if (showScopes) {
            Scopes.DESCRIPTIONS.forEach { (scope, description) ->
                ScopeRow(
                    scope = scope,
                    description = description,
                    checked = scope in draft.scopes,
                    highRisk = scope in TokenPresentation.HIGH_RISK_SCOPES,
                    onToggle = { onChange { it.toggleScope(scope) } },
                )
            }
        }
        draft.scopesError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        val highRisk = draft.scopes intersect TokenPresentation.HIGH_RISK_SCOPES
        if (highRisk.isNotEmpty()) {
            Notice(
                "${highRisk.joinToString(" and ")} give the agent full control of any app on this phone. Keep approvals on.",
                kind = NoticeKind.WARNING,
            )
        }

        Text("Ask me before", style = MaterialTheme.typography.titleSmall, modifier = Modifier)
        Column(Modifier.selectableGroup()) {
            POLICY_ORDER.forEach { policy ->
                PolicyRow(policy, selected = draft.policy == policy, onSelect = { onChange { it.copy(policy = policy) } })
            }
        }
        if (draft.policy == ConfirmPolicy.NEVER) {
            Notice(TokenPresentation.NEVER_POLICY_WARNING, kind = NoticeKind.DANGER, title = "No approvals")
        }

        Text("Expires", style = MaterialTheme.typography.titleSmall)
        Text(
            TokenPresentation.expiryText(currentExpiresAt, now) +
                (draft.expiry?.let { " · after saving: " + if (it.ttlMillis == null) "never expires" else "expires in ${it.label}" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ExpiryOption.entries.forEach { option ->
                FilterChip(
                    selected = draft.expiry == option,
                    // Tapping the chosen option again keeps the current expiry.
                    onClick = { onChange { it.copy(expiry = if (it.expiry == option) null else option) } },
                    label = { Text(option.label) },
                )
            }
        }
    }
}
