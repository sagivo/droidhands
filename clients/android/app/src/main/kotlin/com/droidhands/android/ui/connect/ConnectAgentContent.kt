package com.droidhands.android.ui.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidhands.android.graph.DefaultRelay
import com.droidhands.android.ui.MainViewModel
import com.droidhands.android.ui.components.AppCard
import com.droidhands.android.ui.components.CardPadding
import com.droidhands.android.ui.components.ChoiceChip
import com.droidhands.android.ui.components.Clipboard
import com.droidhands.android.ui.components.CopyableCode
import com.droidhands.android.ui.components.IconBadge
import com.droidhands.android.ui.components.ListSection
import com.droidhands.android.ui.components.LocalSnackbarHostState
import com.droidhands.android.ui.components.Notice
import com.droidhands.android.ui.components.NoticeKind
import com.droidhands.android.ui.components.PrimaryButton
import com.droidhands.android.ui.components.SecondaryButton
import com.droidhands.android.ui.components.SectionHeader
import com.droidhands.android.ui.components.TertiaryButton
import com.droidhands.android.ui.components.rememberCopier
import com.droidhands.android.ui.components.rememberNow
import com.droidhands.android.ui.deeplink.ConfigureLinks
import com.droidhands.android.ui.onboarding.DEFAULT_RELAY_NOTE
import com.droidhands.android.ui.theme.TINT_ALPHA
import com.droidhands.android.ui.theme.statusColors
import com.droidhands.core.auth.TokenRecord
import com.droidhands.core.transport.RelayStatus
import kotlinx.coroutines.launch

/**
 * "Add an agent": pick the agent (chips), then its card with one main action ("Add to Claude" opens Claude
 * with everything filled in; CLIs get a command to copy or send to the computer), at most two short steps,
 * and "Tap Allow when this phone asks", spinning while it waits. A request that arrives while this is on
 * screen opens the approval screen by itself; then "✓ Claude connected". The connection URL stays below for
 * anything else. Shared by the Connect page and the last onboarding step. Scroll is up to the host.
 *
 * @param onConnected label and action of the button shown once an agent connected; null for none (the host
 *   has its own, like the wizard's Done).
 */
@Composable
internal fun ConnectAgentContent(
    main: MainViewModel,
    onUseToken: () -> Unit,
    onOpenConnectionSettings: (() -> Unit)?,
    onConnected: Pair<String, (TokenRecord) -> Unit>?,
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
    // Agent apps on this phone, checked again each time the page comes back (one may have just been installed).
    var appsCheck by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { appsCheck++ }
    val agentApps by produceState<List<AgentApp>?>(null, appsCheck) { value = AgentAppIntents.installed(context) }
    // The agent app tapped last ("On this phone") and the agent picked below it.
    var added by rememberSaveable { mutableStateOf<AgentClient?>(null) }
    var picked by rememberSaveable { mutableStateOf<AgentClient?>(null) }
    // Once an agent connected, its setup folds away behind "Add another agent".
    var addAnother by rememberSaveable { mutableStateOf(false) }
    // The user is here waiting for the agent: open each new request's approval screen once, by itself,
    // instead of making them find the notification. Requests from before the page opened only get the card.
    val seenPairings = rememberSaveable { ArrayList(pairings.map { it.id }) }
    LaunchedEffect(livePairings) {
        livePairings.filter { it.id !in seenPairings }.forEach { request ->
            seenPairings += request.id
            main.openPairing(context, request.id)
        }
    }

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
            connectUrl != null && connected != null && !addAnother -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TertiaryButton("Add another agent", onClick = {
                    addAnother = true
                    added = null
                })
            }
            connectUrl != null -> agentApps?.let { apps ->
                val waiting = connected == null && livePairings.isEmpty()
                if (apps.isNotEmpty()) {
                    AgentAppsSection(apps, connectUrl, added = added, waiting = waiting, onAdded = {
                        added = it
                        main.expectAgent(it.label)
                    })
                }
                // Agents whose app is here are above; with none, the most used one is picked to begin with.
                val others = AgentClient.entries.filter { client -> apps.none { it.client == client } }
                val client = picked?.takeIf { it in others } ?: others.first().takeIf { apps.isEmpty() }
                AgentPicker(others, client, title = if (apps.isEmpty()) "Your agent" else "Another agent", onSelect = {
                    picked = it
                    // "Other" names no agent.
                    if (it != AgentClient.OTHER) main.expectAgent(it.label)
                })
                if (client != null) {
                    val guide = remember(client, connectUrl) { ClientInstructions.guide(client, connectUrl) }
                    GuideCard(guide, connectUrl, waiting = waiting)
                }
                ConnectionUrlSection(connectUrl)
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
                    "Agents that can't sign in this way can use a token instead: DroidHands shows ready-to-paste setup " +
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
                    "page (/setup) on this phone and tap \"Open in DroidHands\".",
            ) {
                RelaySetupForm(settings, onConnect = main::connectRelay)
            }
        }
        if (connected == null || addAnother) TokenAlternative(onUseToken)
    }
}

/** Which agent: each of [clients] as a chip, wrapping so all of them are visible at once. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AgentPicker(clients: List<AgentClient>, selected: AgentClient?, title: String, onSelect: (AgentClient) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(title)
        FlowRow(
            Modifier
                .fillMaxWidth()
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            clients.forEach { client ->
                ChoiceChip(client.label, selected = client == selected, onClick = { onSelect(client) }, showCheck = true)
            }
        }
    }
}

/**
 * One client's setup as a card: its main action (a link that fills everything in, or a command to copy and
 * send to the computer), one or two short steps, and the last step on this phone, "Tap Allow", which spins
 * while waiting for the agent.
 */
@Composable
private fun GuideCard(guide: ClientGuide, url: String, waiting: Boolean) {
    val context = LocalContext.current
    val copy = rememberCopier()
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()
    val shareFailed: () -> Unit = { scope.launch { snackbar.showSnackbar("No app can receive shared text.") } }
    AppCard {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(guide.client.label, style = MaterialTheme.typography.titleMedium)
            Text(
                "For ${guide.appliesTo}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        guide.open?.let { link ->
            PrimaryButton(link.label, fullWidth = true, onClick = {
                if (link.copyUrlFirst) copy("Connection URL", url, false)
                if (!AgentAppIntents.open(context, link)) {
                    scope.launch { snackbar.showSnackbar("No browser found to open ${guide.client.label}.") }
                }
            })
        }
        guide.command?.let { command ->
            CopyableCode(
                command,
                copyLabel = "${guide.client.label} command",
                sensitive = false,
                shareTitle = "Send to your computer",
                onShareFailed = shareFailed,
            )
        }
        guide.message?.let { message ->
            // Shared straight into the agent's app, or copied and pasted there.
            CopyableCode(message, copyLabel = "Message", sensitive = false, shareTitle = "Send to your agent", onShareFailed = shareFailed)
        }
        if (guide.open == null && guide.command == null && guide.message == null) {
            CopyableCode(url, copyLabel = "Connection URL", sensitive = false, shareTitle = "Share connection URL", onShareFailed = shareFailed)
        }
        GuideSteps(guide.steps, waiting)
        guide.note?.let { note ->
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * [steps] numbered, then the last one on this phone, "Tap Allow when this phone asks", which spins while
 * [waiting] for the agent.
 */
@Composable
internal fun GuideSteps(steps: List<String>, waiting: Boolean, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        steps.forEachIndexed { index, step -> StepLine(leading = { StepNumber(index + 1) }, text = step) }
        StepLine(
            leading = {
                if (waiting) {
                    Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                } else {
                    StepNumber(steps.size + 1)
                }
            },
            text = if (waiting) ALLOW_STEP_WAITING else ALLOW_STEP,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/** The connection URL for any other use, one tap to copy or share; it is not a secret. */
@Composable
private fun ConnectionUrlSection(url: String) {
    val copy = rememberCopier()
    val context = LocalContext.current
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()
    ListSection(
        title = "Your connection URL",
        footer = "Not a password: every agent still needs your OK on this phone.",
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = CardPadding, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Text(
                    url,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            TertiaryButton("Share", icon = Icons.Filled.Share, onClick = {
                if (!Clipboard.share(context, "Share connection URL", ClientInstructions.shareText(url))) {
                    scope.launch { snackbar.showSnackbar("No app can receive shared text.") }
                }
            })
            SecondaryButton("Copy", onClick = { copy("Connection URL", url, false) })
        }
    }
}

/** "✓ Claude connected", with the caller's next action ("View", "Finish"). */
@Composable
private fun ConnectedCard(record: TokenRecord, action: Pair<String, (TokenRecord) -> Unit>?) {
    AppCard(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Filled.CheckCircle, statusColors().ok)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${record.name} connected", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Try asking it \"What's my battery level?\"",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (action != null) {
                Spacer(Modifier.width(8.dp))
                PrimaryButton(action.first, onClick = { action.second(record) })
            }
        }
    }
}

@Composable
private fun StepLine(leading: @Composable () -> Unit, text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.Top) {
        leading()
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
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

private const val ALLOW_STEP = "Tap Allow when this phone asks."
private const val ALLOW_STEP_WAITING = "Waiting for your agent… then tap Allow when this phone asks."

/** A titled white group holding free-form content (a form, a URL, a button), padded like a card. */
@Composable
private fun FormSection(title: String, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    ListSection(title = title, footer = footer) {
        Column(Modifier.padding(CardPadding), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

/** Clients without sign-in, or no relay: a token instead (works with the local server too). */
@Composable
private fun TokenAlternative(onUseToken: () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        TertiaryButton("Agent can't sign in? Use a token instead", onClick = onUseToken)
    }
}
