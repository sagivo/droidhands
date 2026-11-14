package dev.phonebridge.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.phonebridge.android.ui.components.SwitchRow
import dev.phonebridge.android.ui.setup.SetupUiState

/** "Permissions & access": progress of the setup checklist; opens it. */
@Composable
internal fun PermissionsRow(setup: SetupUiState, onOpen: () -> Unit) {
    OutlinedCard(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Permissions & special access", style = MaterialTheme.typography.bodyLarge)
                val text = when {
                    !setup.loaded -> "Checking…"
                    setup.progress.essentialComplete -> "${setup.progress.done} of ${setup.progress.total} granted · essentials done"
                    else -> "${setup.progress.done} of ${setup.progress.total} granted · ${setup.progress.essentialMissing.size} essential missing"
                }
                Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (setup.loaded) {
                    LinearProgressIndicator(
                        progress = { setup.progress.fraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .semantics { contentDescription = text },
                    )
                }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Open permissions")
        }
    }
}

/** Device-wide protections that apply to every agent. */
@Composable
internal fun SecuritySection(
    neverAllowCount: Int,
    redactOneTimeCodes: Boolean,
    activeAgents: Int,
    onOpenNeverAllow: () -> Unit,
    onRedactChange: (Boolean) -> Unit,
    onRevokeAll: () -> Unit,
) {
    SettingsCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Never-allow apps", style = MaterialTheme.typography.bodyLarge)
                Text(
                    when (neverAllowCount) {
                        0 -> "None. Agents may use any app their access allows."
                        1 -> "1 app no agent can see or operate"
                        else -> "$neverAllowCount apps no agent can see or operate"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onOpenNeverAllow) { Text("Choose") }
        }
        HorizontalDivider()
        SwitchRow(
            title = "Hide one-time codes",
            subtitle = "Verification and 2FA codes in SMS and notifications are shown to agents as [code hidden], so a " +
                "tricked agent can't use them to take over your accounts. Recommended. Screen reading and screenshots " +
                "can still show a code that is on screen: add your SMS and authenticator apps to Never-allow apps.",
            checked = redactOneTimeCodes,
            onCheckedChange = onRedactChange,
        )
        HorizontalDivider()
        Text(
            "Lock every agent out at once, for example if you think one misbehaved.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = onRevokeAll,
            enabled = activeAgents > 0,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) { Text(if (activeAgents > 0) "Revoke all agents ($activeAgents)" else "No active agents") }
    }
}
