package dev.phonebridge.android.ui.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonebridge.android.ui.MainViewModel
import dev.phonebridge.android.ui.components.Clipboard
import dev.phonebridge.android.ui.components.CodeBlock
import dev.phonebridge.android.ui.components.CopyableCode
import dev.phonebridge.android.ui.components.LocalSnackbarHostState
import dev.phonebridge.android.ui.components.Notice
import dev.phonebridge.android.ui.components.NoticeKind
import dev.phonebridge.android.ui.components.SectionHeader
import dev.phonebridge.android.ui.components.rememberCopier
import dev.phonebridge.android.ui.components.rememberNow
import dev.phonebridge.core.auth.TokenRecord
import dev.phonebridge.core.transport.RelayStatus
import kotlinx.coroutines.launch

/**
 * "Add an agent": the connector URL with Copy/Share, per-client instructions, and a live
 * "Waiting for your agent…" indicator that turns into "✓ Claude connected" once a pairing
 * completes. Shared by the Connect page and the last onboarding step. Scroll is up to the host.
 *
 * @param onConnected label and action of the button shown once an agent connected.
 */
@Composable
internal fun ConnectAgentContent(
    main: MainViewModel,
    onUseToken: () -> Unit,
    onOpenConnectionSettings: (() -> Unit)?,
    onConnected: Pair<String, (TokenRecord) -> Unit>,
    modifier: Modifier = Modifier,
) {
    val settings by main.settings.collectAsStateWithLifecycle()
    val relay by main.relayStatus.collectAsStateWithLifecycle()
    val local by main.localServerStatus.collectAsStateWithLifecycle()
    val tokens by main.tokens.collectAsStateWithLifecycle()
    val pairings by main.pendingPairings.collectAsStateWithLifecycle()
    // Tick every second only while a pairing countdown is visible.
    val now by rememberNow(intervalMs = if (pairings.isEmpty()) 30_000 else 1_000)
    val context = LocalContext.current
    // Agents that existed when the page opened; anything newer is "the one you just connected".
    val baseline = rememberSaveable { ArrayList(tokens.map { it.id }) }
    val connected = remember(tokens, baseline) { PairingWatch.newlyPaired(tokens, baseline.toSet()) }
    val connectUrl = (relay as? RelayStatus.Connected)?.connectUrl?.takeIf { it.isNotBlank() }
    val livePairings = pairings.filter { it.expiresAt > now }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (connected != null) {
            ConnectedCard(connected, onConnected)
        }
        livePairings.forEach { request ->
            PendingPairingCard(
                request = request,
                now = now,
                onReview = { main.openPairing(context, request.id) },
                onDeny = { main.denyPairing(request.id) },
            )
        }
        when {
            connectUrl != null -> {
                ConnectionUrlCard(connectUrl)
                if (connected == null && livePairings.isEmpty()) WaitingIndicator()
                ClientGuides(connectUrl)
            }
            relay is RelayStatus.Connected -> Notice(
                title = "One-tap connect isn't available",
                text = "Your relay is an older version without one-tap connect. Update the relay, or use a token below.",
                kind = NoticeKind.WARNING,
            )
            settings.relayEnabled -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ConnectionStatusLine(settings, relay, local)
                Text(
                    "Your connection URL appears here as soon as the phone is connected to the relay.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (onOpenConnectionSettings != null) {
                    OutlinedButton(onClick = onOpenConnectionSettings) { Text("Connection settings") }
                }
            }
            settings.localServerEnabled -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Notice(
                    "You're using the local server only. One-tap connect needs a relay, so give the agent a token instead: " +
                        "PhoneBridge shows ready-to-paste setup for Claude Code, Claude Desktop and other clients.",
                )
                Button(onClick = onUseToken) { Text("Create a token") }
                Text(
                    "Want one-tap connect? Turn on a relay below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RelaySetupForm(settings, onConnect = main::connectRelay)
            }
            else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader("First, connect this phone to your relay")
                Text(
                    "Agents reach the phone through your self-hosted relay. Paste its URL, or open the relay's setup page " +
                        "(/setup) on this phone and tap \"Open in PhoneBridge\".",
                    style = MaterialTheme.typography.bodyMedium,
                )
                RelaySetupForm(settings, onConnect = main::connectRelay)
            }
        }
        TokenAlternative(onUseToken)
    }
}

@Composable
private fun ConnectionUrlCard(url: String) {
    val copy = rememberCopier()
    val context = LocalContext.current
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Your connection URL", style = MaterialTheme.typography.titleMedium)
            Text(
                "Paste it into your AI agent. It isn't a password: every agent still has to be allowed on this phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CodeBlock(url)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = {
                    if (!Clipboard.share(context, "Share connection URL", ClientInstructions.shareText(url))) {
                        scope.launch { snackbar.showSnackbar("No app can receive shared text.") }
                    }
                }) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text("Share")
                }
                FilledTonalButton(onClick = { copy("Connection URL", url, false) }) { Text("Copy") }
            }
        }
    }
}

@Composable
private fun WaitingIndicator() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            "Waiting for your agent… you'll get a prompt on this phone.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ConnectedCard(record: TokenRecord, action: Pair<String, (TokenRecord) -> Unit>) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "${record.name} connected",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    "Try asking it something like \"What's my battery level?\"",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { action.second(record) }) { Text(action.first) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClientGuides(url: String) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val client = AgentClient.entries[tab.coerceIn(0, AgentClient.entries.lastIndex)]
    val guide = remember(client, url) { ClientInstructions.guide(client, url) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Add it to your agent")
        PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
            AgentClient.entries.forEachIndexed { index, c ->
                Tab(selected = index == tab, onClick = { tab = index }, text = { Text(c.label) })
            }
        }
        Text(guide.appliesTo, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        NumberedSteps(guide.steps, startAt = 1)
        guide.command?.let { command ->
            CopyableCode(command, copyLabel = "${client.label} command", sensitive = false)
        }
        NumberedSteps(guide.afterCommand, startAt = guide.steps.size + 1)
    }
}

@Composable
private fun NumberedSteps(steps: List<String>, startAt: Int) {
    steps.forEachIndexed { index, step ->
        Row {
            Text("${startAt + index}.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(24.dp))
            Text(step, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun TokenAlternative(onUseToken: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionHeader("Advanced")
        Text(
            "Client without sign-in support, or no relay? Create a token and paste it into the client's configuration " +
                "(works with the local server too).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onUseToken) { Text("Use a token instead") }
    }
}
