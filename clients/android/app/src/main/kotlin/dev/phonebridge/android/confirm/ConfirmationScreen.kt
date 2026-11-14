package dev.phonebridge.android.confirm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.phonebridge.android.ui.components.AppCard
import dev.phonebridge.android.ui.components.ButtonSize
import dev.phonebridge.android.ui.components.IconBadge
import dev.phonebridge.android.ui.components.ListDivider
import dev.phonebridge.android.ui.components.ListRow
import dev.phonebridge.android.ui.components.ListSection
import dev.phonebridge.android.ui.components.PrimaryButton
import dev.phonebridge.android.ui.components.RowValue
import dev.phonebridge.android.ui.components.SecondaryButton
import dev.phonebridge.android.ui.components.StatusPill
import dev.phonebridge.android.ui.theme.statusColors
import dev.phonebridge.core.confirm.ConfirmationRequest
import dev.phonebridge.core.tools.Risk
import kotlinx.coroutines.delay

/** Allow stays disabled this long after a request appears, so a tap meant for something else can't approve it. */
private const val ARM_DELAY_MS = 700L

/**
 * Full details of a pending agent action with Allow/Deny: who wants to do what (hero), the exact
 * action, then risk, connection and tool as rows. Deny · Allow split the width at the bottom; Allow
 * arms after [ARM_DELAY_MS].
 */
@Composable
internal fun ConfirmationScreen(
    request: ConfirmationRequest,
    expiresAt: Long,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(expiresAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }
    val secondsLeft = ConfirmationText.secondsRemaining(expiresAt, now)
    var armed by remember(request.id) { mutableStateOf(false) }
    LaunchedEffect(request.id) {
        delay(ARM_DELAY_MS)
        armed = true
    }
    val colors = statusColors()
    val riskColor = when (request.risk) {
        Risk.SENSITIVE -> colors.error
        Risk.ACTION -> colors.warning
        Risk.READ -> colors.ok
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
        ) {
            Text(
                "Approve agent action?",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .semantics { heading() },
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val icon = when (request.risk) {
                        Risk.SENSITIVE -> Icons.Filled.Warning
                        Risk.ACTION -> Icons.Filled.Edit
                        Risk.READ -> Icons.Filled.Info
                    }
                    IconBadge(icon, riskColor, size = 64.dp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${request.tokenName.trim().ifEmpty { "An agent" }} wants to:",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = request.toolTitle.trim().ifEmpty { request.toolName },
                        style = MaterialTheme.typography.headlineMedium,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(2.dp))
                    StatusPill(ConfirmationText.countdownLabel(secondsLeft), if (secondsLeft > 0) colors.warning else colors.error)
                }
                AppCard {
                    SelectionContainer {
                        Text(text = request.summary, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                ListSection(title = "Details") {
                    ListRow(title = "Risk", trailing = { StatusPill(ConfirmationText.riskLabel(request.risk), riskColor) })
                    ListDivider()
                    ListRow(title = "Connection", trailing = { RowValue(ConfirmationText.transportLabel(request.transport)) })
                    ListDivider()
                    ListRow(title = "Tool", trailing = { RowValue(request.toolName) })
                }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Only allow actions you asked your agent to do. Messages it read may try to trick it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("Deny", onClick = onDeny, size = ButtonSize.LARGE, modifier = Modifier.weight(1f))
                    PrimaryButton(
                        "Allow",
                        onClick = onAllow,
                        enabled = armed && secondsLeft > 0,
                        size = ButtonSize.LARGE,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
