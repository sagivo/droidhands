package dev.phonebridge.android.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import dev.phonebridge.android.ui.setup.SetupProgress
import dev.phonebridge.android.ui.theme.statusColors
import dev.phonebridge.core.confirm.ConfirmationRequest
import dev.phonebridge.core.tools.Risk

/** An agent action waiting for the user's decision (also shown as a notification). */
@Composable
internal fun PendingApprovalCard(
    request: ConfirmationRequest,
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
                    "Unanswered requests are denied after 60 s.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                OutlinedButton(onClick = onDeny) { Text("Deny") }
                Button(onClick = onAllow) { Text("Allow") }
            }
        }
    }
}

/** Shown while essential Setup items are missing; tapping opens Setup. */
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
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Open Setup")
        }
    }
}

/** Onboarding: three numbered steps with a shortcut to the screen that completes each. */
@Composable
internal fun GetStartedCard(
    steps: List<GettingStartedStep>,
    actions: List<Pair<String, () -> Unit>>,
    modifier: Modifier = Modifier,
) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Get started", style = MaterialTheme.typography.titleLarge)
            Text(
                "PhoneBridge lets AI agents you authorize use this phone, for example telling Claude " +
                    "\"send a WhatsApp to Tania\". The phone stays in charge: every agent needs a token you create " +
                    "here, sensitive actions ask you first, and everything is logged.",
                style = MaterialTheme.typography.bodyMedium,
            )
            steps.forEachIndexed { index, step ->
                StepRow(number = index + 1, step = step, action = actions.getOrNull(index))
            }
        }
    }
}

@Composable
private fun StepRow(number: Int, step: GettingStartedStep, action: Pair<String, () -> Unit>?) {
    Row(verticalAlignment = Alignment.Top) {
        if (step.done) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = "Done",
                tint = statusColors().ok,
                modifier = Modifier.size(28.dp),
            )
        } else {
            Box(
                Modifier
                    .size(28.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("$number", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(step.title, style = MaterialTheme.typography.titleSmall)
            Text(step.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!step.done && action != null) {
                TextButton(onClick = action.second, modifier = Modifier.padding(top = 2.dp)) { Text(action.first) }
            }
        }
    }
}
