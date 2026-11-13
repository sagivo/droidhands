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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.phonebridge.core.confirm.ConfirmationRequest
import dev.phonebridge.core.tools.Risk
import kotlinx.coroutines.delay

/** Allow stays disabled this long after a request appears, so a tap meant for something else can't approve it. */
private const val ARM_DELAY_MS = 700L

/** Full details of a pending agent action with Allow/Deny. */
@Composable
internal fun ConfirmationScreen(
    request: ConfirmationRequest,
    expiresAt: Long,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
) {
    val now by produceState(System.currentTimeMillis(), expiresAt) {
        while (true) {
            value = System.currentTimeMillis()
            delay(250)
        }
    }
    val secondsLeft = ConfirmationText.secondsRemaining(expiresAt, now)
    var armed by remember(request.id) { mutableStateOf(false) }
    LaunchedEffect(request.id) {
        delay(ARM_DELAY_MS)
        armed = true
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Text("Approve agent action?", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = "${request.tokenName.trim().ifEmpty { "An agent" }} wants to:",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = request.toolTitle.trim().ifEmpty { request.toolName },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    SelectionContainer {
                        Text(
                            text = request.summary,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                DetailRow(
                    label = "Risk",
                    value = ConfirmationText.riskLabel(request.risk),
                    valueColor = if (request.risk == Risk.SENSITIVE) MaterialTheme.colorScheme.error else Color.Unspecified,
                )
                DetailRow(label = "Connection", value = ConfirmationText.transportLabel(request.transport))
                DetailRow(label = "Tool", value = request.toolName)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = ConfirmationText.countdownLabel(secondsLeft),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Only allow actions you asked your agent to do. Messages it read may try to trick it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDeny, modifier = Modifier.weight(1f)) { Text("Deny") }
                Button(onClick = onAllow, modifier = Modifier.weight(1f), enabled = armed && secondsLeft > 0) { Text("Allow") }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, valueColor: Color = Color.Unspecified) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(104.dp),
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium, color = valueColor)
    }
}
