package com.droidhands.android.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.droidhands.android.ui.components.AppCard
import com.droidhands.android.ui.components.ButtonSize
import com.droidhands.android.ui.components.Chevron
import com.droidhands.android.ui.components.IconBadge
import com.droidhands.android.ui.components.PrimaryButton
import com.droidhands.android.ui.components.SecondaryButton
import com.droidhands.android.ui.components.StatusPill
import com.droidhands.android.ui.components.TertiaryButton
import com.droidhands.android.ui.components.rememberArming
import com.droidhands.android.ui.connect.PairingWatch
import com.droidhands.android.ui.setup.SetupProgress
import com.droidhands.android.ui.theme.statusColors
import com.droidhands.core.confirm.ConfirmationRequest
import com.droidhands.core.tools.Risk

/**
 * An agent action waiting for the user's decision (also shown as a notification). Like the full
 * approval screen, Allow arms after [com.droidhands.android.ui.components.ARM_DELAY_MS]; the screen
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
    val (riskText, riskColor) = when (request.risk) {
        Risk.SENSITIVE -> "Sensitive" to colors.error
        Risk.ACTION -> "Action" to colors.warning
        Risk.READ -> "Read" to colors.ok
    }
    AppCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(request.toolTitle, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            StatusPill(riskText, riskColor)
        }
        Text(request.summary, style = MaterialTheme.typography.bodyLarge)
        Text(
            "Requested by \"${request.tokenName}\" via ${request.transport} · ${request.toolName}. " +
                (expiresAt?.let { "Denied automatically if unanswered (${PairingWatch.timeLeft(it, now)})." }
                    ?: "Unanswered requests are denied automatically."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Allow arms only after the card has stayed in place (it pops in above whatever the user was tapping).
        val arming = rememberArming(request.id)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("Deny", onClick = onDeny, modifier = Modifier.weight(1f))
            PrimaryButton("Allow", onClick = onAllow, enabled = arming.armed, modifier = arming.modifier.weight(1f))
        }
    }
}

/** Shown while essential permissions are missing; tapping opens Settings > Permissions & access. */
@Composable
internal fun SetupHintCard(progress: SetupProgress, onOpenSetup: () -> Unit, modifier: Modifier = Modifier) {
    val warning = statusColors().warning
    val missing = progress.essentialMissing
    AppCard(onClick = onOpenSetup, modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Filled.Warning, warning)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Finish setup", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${missing.size} essential ${if (missing.size == 1) "item" else "items"} missing: " +
                        missing.joinToString(", ") { it.title },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Chevron()
        }
        val summary = "${progress.done} of ${progress.total} setup items done"
        LinearProgressIndicator(
            progress = { progress.fraction },
            color = warning,
            trackColor = warning.copy(alpha = 0.15f),
            gapSize = 0.dp,
            drawStopIndicator = {},
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape)
                .semantics { contentDescription = summary },
        )
    }
}

/**
 * "Update available: v1.3.0" (from the daily GitHub check). Download opens the release's APK in the
 * browser; Android then installs it over this version, keeping agents and settings. Not now hides
 * the card until the next release.
 */
@Composable
internal fun UpdateCard(version: String, onDownload: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    AppCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Filled.Refresh, MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Update available: v$version", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Download it, then open the file to install. Your agents and settings stay as they are.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TertiaryButton("Not now", onClick = onDismiss)
            PrimaryButton("Download", onClick = onDownload)
        }
    }
}

/** The main call to action: connect an AI agent (one URL + Allow). */
@Composable
internal fun ConnectAgentCard(hasActiveAgents: Boolean, onConnect: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PrimaryButton("Connect an AI agent", onClick = onConnect, icon = Icons.Filled.Add, size = ButtonSize.LARGE, fullWidth = true)
        if (!hasActiveAgents) {
            Text(
                "Copy one message to Claude, ChatGPT or any other AI agent. " +
                    "Then tap Allow on this phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )
        }
    }
}

/** Today's numbers as stat tiles (actions, agents, denied); tapping opens the Agents tab. */
@Composable
internal fun TodayCard(summary: TodaySummary, onOpenAgents: () -> Unit, modifier: Modifier = Modifier) {
    val colors = statusColors()
    val text = TodaySummary.text(summary)
    AppCard(onClick = onOpenAgents, modifier = modifier.semantics { contentDescription = "Today: $text. Open agents" }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Today", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Chevron()
        }
        Row(Modifier.fillMaxWidth().clearAndSetSemantics {}) {
            Stat(summary.actions.toString(), if (summary.actions == 1L) "action" else "actions", Modifier.weight(1f))
            Stat(summary.agents.toString(), if (summary.agents == 1) "agent" else "agents", Modifier.weight(1f))
            val blocked = summary.denied + summary.errors
            Stat(
                blocked.toString(),
                "denied or failed",
                Modifier.weight(1f),
                color = if (blocked > 0) colors.error else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.headlineMedium, color = color)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
