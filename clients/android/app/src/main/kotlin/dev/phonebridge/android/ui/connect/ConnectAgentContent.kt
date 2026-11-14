package dev.phonebridge.android.ui.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import dev.phonebridge.android.graph.DefaultRelay
import dev.phonebridge.android.ui.MainViewModel
import dev.phonebridge.android.ui.components.Accents
import dev.phonebridge.android.ui.components.AppCard
import dev.phonebridge.android.ui.components.CardPadding
import dev.phonebridge.android.ui.components.ChoiceChip
import dev.phonebridge.android.ui.components.Clipboard
import dev.phonebridge.android.ui.components.CodeBlock
import dev.phonebridge.android.ui.components.CopyableCode
import dev.phonebridge.android.ui.components.IconBadge
import dev.phonebridge.android.ui.components.IconTile
import dev.phonebridge.android.ui.components.ListRow
import dev.phonebridge.android.ui.components.ListSection
import dev.phonebridge.android.ui.components.LocalSnackbarHostState
import dev.phonebridge.android.ui.components.Notice
import dev.phonebridge.android.ui.components.NoticeKind
import dev.phonebridge.android.ui.components.PrimaryButton
import dev.phonebridge.android.ui.components.SecondaryButton
import dev.phonebridge.android.ui.components.SectionHeader
import dev.phonebridge.android.ui.components.TertiaryButton
import dev.phonebridge.android.ui.components.rememberCopier
import dev.phonebridge.android.ui.components.rememberNow
import dev.phonebridge.android.ui.deeplink.ConfigureLinks
import dev.phonebridge.android.ui.onboarding.DEFAULT_RELAY_NOTE
import dev.phonebridge.android.ui.theme.TINT_ALPHA
import dev.phonebridge.android.ui.theme.statusColors
import dev.phonebridge.core.auth.TokenRecord
import dev.phonebridge.core.transport.RelayStatus
import kotlinx.coroutines.launch

/**
 * "Add an agent", as three numbered steps: 1. the connector URL with Copy/Share, 2. per-client
 * instructions (Claude, Claude Code, ChatGPT, Other as chips), 3. a live "Waiting for your agent…"
 * that turns into "✓ Claude connected" once a pairing completes. Shared by the Connect page and the
 * last onboarding step. Scroll is up to the host.
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
    // No relay yet, but the build comes with one: offer it first.
    val offerDefault = main.defaultRelayUrl?.takeIf { DefaultRelay.canOffer(settings, it) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(20.dp)) {
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
                ConnectionUrlSection(connectUrl)
                ClientGuides(connectUrl)
                if (connected == null && livePairings.isEmpty()) WaitingSection()
            }
            relay is RelayStatus.Connected -> Notice(
                title = "One-tap connect isn't available",
                text = "Your relay is an older version without one-tap connect. Update the relay, or use a token below.",
                kind = NoticeKind.WARNING,
            )
            settings.relayEnabled -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ConnectionStatusLine(
                    settings,
                    relay,
                    local,
                    footer = "Your connection URL appears here as soon as the phone is connected to the relay.",
                )
                if (onOpenConnectionSettings != null) {
                    SecondaryButton("Connection settings", onClick = onOpenConnectionSettings)
                }
            }
            settings.localServerEnabled -> Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionHeader("Your connection URL")
                    ActiveRoutesCard(ConnectionRoutes.active(settings, relay, local))
                }
                Notice(
                    "Agents that can't sign in this way can use a token instead: PhoneBridge shows ready-to-paste setup " +
                        "for Claude Code, Claude Desktop and other clients.",
                    action = { SecondaryButton("Create a token", onClick = onUseToken) },
                )
                FormSection(title = "Add a relay", footer = "For claude.ai or ChatGPT, which run in the cloud, turn on a relay.") {
                    RelaySetupForm(settings, onConnect = main::connectRelay)
                }
            }
            offerDefault != null -> Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                FormSection(title = "First, get this phone online", footer = DEFAULT_RELAY_NOTE) {
                    Text(
                        "Use ${ConfigureLinks.hostOf(offerDefault) ?: offerDefault}, the relay this app comes with.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    PrimaryButton("Go online", onClick = main::useDefaultRelay, fullWidth = true)
                }
                FormSection(title = "Or connect to your own relay") {
                    RelaySetupForm(settings.copy(relayUrl = "", relayAccessKey = ""), onConnect = main::connectRelay)
                }
            }
            else -> FormSection(
                title = "First, connect this phone to your relay",
                footer = "Agents reach the phone through your self-hosted relay. Paste its URL, or open the relay's setup " +
                    "page (/setup) on this phone and tap \"Open in PhoneBridge\".",
            ) {
                RelaySetupForm(settings, onConnect = main::connectRelay)
            }
        }
        TokenAlternative(onUseToken)
    }
}

/** Step 1: the connection URL in a code field, with Share (URL plus a hint) and Copy. */
@Composable
private fun ConnectionUrlSection(url: String) {
    val copy = rememberCopier()
    val context = LocalContext.current
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()
    FormSection(
        title = "1. Copy your connection URL",
        footer = "Paste it into your AI agent. It isn't a password: every agent still has to be allowed on this phone.",
    ) {
        CodeBlock(url)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TertiaryButton("Share", icon = Icons.Filled.Share, onClick = {
                if (!Clipboard.share(context, "Share connection URL", ClientInstructions.shareText(url))) {
                    scope.launch { snackbar.showSnackbar("No app can receive shared text.") }
                }
            })
            SecondaryButton("Copy", onClick = { copy("Connection URL", url, false) })
        }
    }
}

/** Step 3: what happens next, until the agent shows up (then the pairing card above takes over). */
@Composable
private fun WaitingSection() {
    ListSection(title = "3. Tap Allow on this phone", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        ListRow(
            title = "Waiting for your agent…",
            subtitle = "You'll get a prompt on this phone.",
            leading = {
                Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            },
        )
    }
}

/** "✓ Claude connected", with the caller's next action ("View", "Finish"). */
@Composable
private fun ConnectedCard(record: TokenRecord, action: Pair<String, (TokenRecord) -> Unit>) {
    AppCard(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Filled.CheckCircle, statusColors().ok)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${record.name} connected", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Try asking it something like \"What's my battery level?\"",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            PrimaryButton(action.first, onClick = { action.second(record) })
        }
    }
}

/** Step 2: pick the client (chips), then its numbered instructions and, for Claude Code, the command. */
@Composable
private fun ClientGuides(url: String) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val client = AgentClient.entries[tab.coerceIn(0, AgentClient.entries.lastIndex)]
    val guide = remember(client, url) { ClientInstructions.guide(client, url) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader("2. Add it to your agent")
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AgentClient.entries.forEachIndexed { index, c ->
                ChoiceChip(c.label, selected = index == tab, onClick = { tab = index }, showCheck = true)
            }
        }
        ListSection(footer = "For ${guide.appliesTo}.") {
            Column(Modifier.padding(CardPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberedSteps(guide.steps, startAt = 1)
                guide.command?.let { command ->
                    CopyableCode(command, copyLabel = "${client.label} command", sensitive = false)
                }
                NumberedSteps(guide.afterCommand, startAt = guide.steps.size + 1)
            }
        }
    }
}

@Composable
private fun NumberedSteps(steps: List<String>, startAt: Int) {
    steps.forEachIndexed { index, step ->
        Row(verticalAlignment = Alignment.Top) {
            StepNumber(startAt + index)
            Spacer(Modifier.width(12.dp))
            Text(step, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
    }
}

/** A step number in a small brand-tinted circle; TalkBack reads it before the step. */
@Composable
private fun StepNumber(number: Int) {
    val color = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .size(22.dp)
            .background(color.copy(alpha = TINT_ALPHA), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(number.toString(), style = MaterialTheme.typography.labelMedium, color = color)
    }
}

/** A titled white group holding free-form content (a form, a URL, a button), padded like a card. */
@Composable
private fun FormSection(title: String, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    ListSection(title = title, footer = footer) {
        Column(Modifier.padding(CardPadding), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun TokenAlternative(onUseToken: () -> Unit) {
    ListSection(
        title = "Advanced",
        footer = "Client without sign-in support, or no relay? Create a token and paste it into the client's configuration " +
            "(works with the local server too).",
    ) {
        ListRow(
            title = "Use a token instead",
            leading = { IconTile(Icons.Filled.Lock, Accents.Gray) },
            onClick = onUseToken,
        )
    }
}
