package dev.phonebridge.android.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.phonebridge.android.ui.components.StatusPill
import dev.phonebridge.android.ui.components.rememberArming
import dev.phonebridge.android.ui.connect.PairingWatch
import dev.phonebridge.android.ui.setup.SetupProgress
import dev.phonebridge.android.ui.theme.statusColors
import dev.phonebridge.core.confirm.ConfirmationRequest
import dev.phonebridge.core.tools.Risk

/**
 * An agent action waiting for the user's decision (also shown as a notification). Like the full
 * approval screen, Allow arms after [dev.phonebridge.android.ui.components.ARM_DELAY_MS]; the screen
 * showing it applies the window hardening (`TouchShieldEffect`, `SecureWindowEffect`).
 */
@Composable
internal fun PendingApprovalCard(
    request: ConfirmationRequest,
    expiresAt: Long?,
    now: Long,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = statusColors()
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    request.toolTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.weight(1f),
                )
                val (riskText, riskColor) = when (request.risk) {
                    Risk.SENSITIVE -> "Sensitive" to colors.error
                    Risk.ACTION -> "Action" to colors.warning
                    Risk.READ -> "Read" to colors.ok
                }
                StatusPill(riskText, riskColor)
            }
            Text(request.summary, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onTertiaryContainer)
            Text(
                "Requested by \"${request.tokenName}\" via ${request.transport} · ${request.toolName}. " +
                    (expiresAt?.let { "Denied automatically if unanswered (${PairingWatch.timeLeft(it, now)})." }
                        ?: "Unanswered requests are denied automatically."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            // Allow arms only after the card has stayed in place (it pops in above whatever the user was tapping).
            val arming = rememberArming(request.id)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                OutlinedButton(onClick = onDeny) { Text("Deny") }
                Button(onClick = onAllow, enabled = arming.armed, modifier = arming.modifier) { Text("Allow") }
            }
        }
    }
}

/** Shown while essential permissions are missing; tapping opens Settings > Permissions & access. */
@Composable
internal fun SetupHintCard(progress: SetupProgress, onOpenSetup: () -> Unit, modifier: Modifier = Modifier) {
    val warning = statusColors().warning
    val missing = progress.essentialMissing
    OutlinedCard(onClick = onOpenSetup, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = warning)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Finish setup", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${missing.size} essential ${if (missing.size == 1) "item" else "items"} missing: " +
                        missing.joinToString(", ") { it.title },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val summary = "${progress.done} of ${progress.total} setup items done"
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .semantics { contentDescription = summary },
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Open permissions")
        }
    }
}

/**
 * "Update available: v1.3.0" (from the daily GitHub check). Download opens the release's APK in the
 * browser; Android then installs it over this version, keeping agents and settings. Not now hides
 * the card until the next release.
 */
@Composable
internal fun UpdateCard(version: String, onDownload: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Update available: v$version", style = MaterialTheme.typography.titleMedium)
            Text(
                "Download it, then open the file to install. Your agents and settings stay as they are.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = onDismiss) { Text("Not now") }
                Button(onClick = onDownload) { Text("Download") }
            }
        }
    }
}

/** The main call to action: connect an AI agent (one URL + Allow). */
@Composable
internal fun ConnectAgentCard(hasActiveAgents: Boolean, onConnect: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
            Text("Connect an AI agent")
        }
        if (!hasActiveAgents) {
            Text(
                "Paste one URL into Claude, ChatGPT or any MCP client, then tap Allow on this phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "Today: 23 actions by 2 agents · 1 denied", tapping opens the Agents tab. */
@Composable
internal fun TodayCard(summary: TodaySummary, onOpenAgents: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedCard(onClick = onOpenAgents, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Today", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(TodaySummary.text(summary), style = MaterialTheme.typography.titleMedium)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Open agents")
        }
    }
}
