package dev.phonebridge.android.ui.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.phonebridge.android.ui.components.Accents
import dev.phonebridge.android.ui.components.CardPadding
import dev.phonebridge.android.ui.components.ChoiceChip
import dev.phonebridge.android.ui.components.IconTile
import dev.phonebridge.android.ui.components.ListDivider
import dev.phonebridge.android.ui.components.ListRow
import dev.phonebridge.android.ui.components.ListSection
import dev.phonebridge.android.ui.components.RowValue
import dev.phonebridge.android.ui.grant.GrantPresentation
import dev.phonebridge.android.ui.tokens.ExpiryOption
import dev.phonebridge.android.ui.tokens.TokenPresentation
import dev.phonebridge.core.auth.TokenRecord

/**
 * "Access" on the agent's page: its grant in the grant screen's terms (apps, what it can and can't do,
 * approvals), with a "Change access" row to the same editor used at pairing. Changes there are saved at once.
 */
@Composable
internal fun AgentAccessCard(record: TokenRecord, appLabels: Map<String, String>, editable: Boolean, onChange: () -> Unit) {
    ListSection(title = "Access", footer = GrantPresentation.AGENT_ACCESS_LINK_HINT) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = CardPadding, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            GrantPresentation.details(record, appLabels).forEach { line ->
                Text(line, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (editable) {
            ListDivider()
            ListRow(
                title = "Change access",
                leading = { IconTile(Icons.Filled.Lock, Accents.Indigo) },
                onClick = onChange,
            )
        }
    }
}

/** "Expires": the current expiry, and a new one counted from saving. Edits go to the page's draft. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ExpirySection(draft: AgentDraft, currentExpiresAt: Long?, now: Long, onChange: ((AgentDraft) -> AgentDraft) -> Unit) {
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
