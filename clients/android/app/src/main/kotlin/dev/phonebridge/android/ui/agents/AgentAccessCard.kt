package dev.phonebridge.android.ui.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.phonebridge.android.ui.components.SectionHeader
import dev.phonebridge.android.ui.grant.GrantPresentation
import dev.phonebridge.android.ui.tokens.ExpiryOption
import dev.phonebridge.android.ui.tokens.TokenPresentation
import dev.phonebridge.core.auth.TokenRecord

/**
 * "Access" on the agent's page: its grant in the grant screen's terms (apps, what it can and can't do,
 * approvals), with a button to the same editor used at pairing. Changes there are saved at once.
 */
@Composable
internal fun AgentAccessCard(record: TokenRecord, appLabels: Map<String, String>, editable: Boolean, onChange: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Access")
        AdvancedCard(title = GrantPresentation.title(record.name), subtitle = GrantPresentation.AGENT_ACCESS_LINK_HINT) {
            GrantPresentation.details(record, appLabels).forEach { line ->
                Text(line, style = MaterialTheme.typography.bodyMedium)
            }
            if (editable) FilledTonalButton(onClick = onChange) { Text("Change access") }
        }
    }
}

/** "Expires": the current expiry, and a new one counted from saving. Edits go to the page's draft. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ExpiryCard(draft: AgentDraft, currentExpiresAt: Long?, now: Long, onChange: ((AgentDraft) -> AgentDraft) -> Unit) {
    AdvancedCard(
        title = "Expires",
        subtitle = TokenPresentation.expiryText(currentExpiresAt, now) +
            (draft.expiry?.let { " · after saving: " + if (it.ttlMillis == null) "never expires" else "expires in ${it.label}" } ?: ""),
    ) {
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
