package dev.phonebridge.android.ui.agents

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Face
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonebridge.android.ui.components.EmptyState
import dev.phonebridge.android.ui.components.ScreenScaffold
import dev.phonebridge.android.ui.components.StatusPill
import dev.phonebridge.android.ui.components.padded
import dev.phonebridge.android.ui.components.rememberNow
import dev.phonebridge.android.ui.theme.statusColors
import dev.phonebridge.android.ui.tokens.TokenPresentation
import dev.phonebridge.android.ui.tokens.TokenState
import dev.phonebridge.core.audit.TokenUsage
import dev.phonebridge.core.auth.TokenRecord
import java.time.Instant
import java.time.ZoneId

/** Agents tab: one card per agent (token) with status and today's usage; tap for details. */
@Composable
fun AgentsScreen(vm: AgentsViewModel, onOpenAgent: (String) -> Unit, onConnect: () -> Unit) {
    val tokens by vm.tokens.collectAsStateWithLifecycle()
    val usage by vm.usage.collectAsStateWithLifecycle()
    val now by rememberNow()
    var revoking by remember { mutableStateOf<TokenRecord?>(null) }
    val sorted = remember(tokens, usage, now) { AgentPresentation.sorted(tokens, usage, now) }
    val today = remember(now) { Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate() }
    val is24h = DateFormat.is24HourFormat(LocalContext.current)

    ScreenScaffold(
        title = "Agents",
        floatingActionButton = {
            if (tokens.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = onConnect,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Connect an AI agent") },
                )
            }
        },
    ) { padding ->
        LazyColumn(contentPadding = padding.padded(extraBottom = 80.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "intro") {
                Text(
                    "Each AI agent you connect appears here. Tap one to see what it did, change what it may do, or revoke it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (tokens.isEmpty()) {
                item(key = "empty") {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        EmptyState(
                            title = "No agents yet",
                            message = "Connect Claude, ChatGPT or any MCP client: paste one URL into it, then tap Allow on this phone.",
                            icon = Icons.Filled.Face,
                        )
                        Button(onClick = onConnect) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                            Text("Connect an AI agent")
                        }
                    }
                }
            }
            items(sorted, key = { it.id }) { record ->
                AgentCard(
                    record = record,
                    usage = usage[record.id],
                    now = now,
                    callsToday = AgentPresentation.callsOn(usage[record.id], today),
                    restrictions = AgentPresentation.restrictions(record) { Schedule.range(it, is24h) },
                    onOpen = { onOpenAgent(record.id) },
                    onRevoke = { revoking = record },
                )
            }
        }
    }

    revoking?.let { record ->
        RevokeAgentDialog(record, onConfirm = { vm.revoke(record) }, onDismiss = { revoking = null })
    }
}

@Composable
private fun AgentCard(
    record: TokenRecord,
    usage: TokenUsage?,
    now: Long,
    callsToday: Long,
    restrictions: List<String>,
    onOpen: () -> Unit,
    onRevoke: () -> Unit,
) {
    val colors = statusColors()
    val state = TokenPresentation.state(record, now)
    val level = AccessLevel.of(record.scopes)
    OutlinedCard(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(record.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        AgentPresentation.clientLabel(record),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                when (state) {
                    TokenState.ACTIVE -> StatusPill(AgentPresentation.stateLabel(state), colors.ok)
                    TokenState.REVOKED -> StatusPill(AgentPresentation.stateLabel(state), colors.error)
                    TokenState.EXPIRED -> StatusPill(AgentPresentation.stateLabel(state), colors.warning)
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            Text(
                "${level.label} · ${TokenPresentation.policyLabel(record.confirmPolicy)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                listOf(
                    AgentPresentation.lastActiveText(AgentPresentation.lastActive(record, usage), now),
                    AgentPresentation.callsTodayText(callsToday),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (restrictions.isNotEmpty()) {
                Text(
                    "Restricted: " + restrictions.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state == TokenState.ACTIVE) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        onClick = onRevoke,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("Revoke") }
                }
            }
        }
    }
}
