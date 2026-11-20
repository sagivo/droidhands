package com.droidhands.android.ui.connect

import androidx.compose.foundation.background
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidhands.android.graph.DefaultRelay
import com.droidhands.android.ui.MainViewModel
import com.droidhands.android.ui.account.AccountRequiredNotice
import com.droidhands.android.ui.account.DefaultRelayButton
import com.droidhands.android.ui.components.AppCard
import com.droidhands.android.ui.components.CardPadding
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
import com.droidhands.android.ui.components.rememberNow
import com.droidhands.android.ui.onboarding.DEFAULT_RELAY_NOTE
import com.droidhands.android.ui.theme.TINT_ALPHA
import com.droidhands.android.ui.theme.statusColors
import com.droidhands.core.auth.TokenRecord
import com.droidhands.core.transport.RelayStatus
import kotlinx.coroutines.launch

/**
 * "Add an agent": one message for every agent ([AgentPrompt]) to copy or share, then "Tap Allow when this phone
 * asks", spinning once it was copied. A request that arrives while this is on screen opens the approval screen by
 * itself; then "✓ Claude connected". Shared by the Connect page and the last onboarding step. Scroll is up to the
 * host.
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
    var baseline by rememberSaveable { mutableStateOf(ArrayList(tokens.map { it.id })) }
    val connected = remember(tokens, baseline) { PairingWatch.newlyPaired(tokens, baseline.toSet()) }
    val connectUrl = (relay as? RelayStatus.Connected)?.connectUrl?.takeIf { it.isNotBlank() }
    val livePairings = pairings.filter { it.expiresAt > now }
    // No relay yet, but the build comes with one: offer it first.
    val offerDefault = main.defaultRelayUrl?.takeIf { DefaultRelay.canOffer(settings, it) }
    // Whether the message was copied or shared (then the last step waits for the agent).
    var started by rememberSaveable { mutableStateOf(false) }
    // Once an agent connected, its setup folds away behind "Add another agent".
    var addAnother by rememberSaveable { mutableStateOf(false) }
    // "Do it myself": the form for the user's own relay, instead of the choice.
    var ownRelay by rememberSaveable { mutableStateOf(false) }
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
                    started = false
                    // The agent that just connected is done: the next one is what the page waits for.
                    baseline = ArrayList(baseline + connected.id)
                })
            }
            connectUrl != null -> PromptCard(
                prompt = remember(connectUrl) { AgentPrompt.signIn(connectUrl) },
                waiting = started && connected == null && livePairings.isEmpty(),
                onStarted = { started = true },
                onUseToken = onUseToken,
            )
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
                AccountRequiredNotice(main, relay)
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
            offerDefault != null -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeader("How do you want to connect?")
                Column(Modifier.selectableGroup()) {
                    ConnectOption("Use DroidHands Connect", "Easiest. Free account, sign in with Google.", selected = !ownRelay, onClick = { ownRelay = false })
                    ConnectOption("Do it myself", "Use your own relay.", selected = ownRelay, onClick = { ownRelay = true })
                }
                if (ownRelay) {
                    FormSection(title = "Your own relay") {
                        RelaySetupForm(settings.copy(relayUrl = "", relayAccessKey = ""), onConnect = main::connectRelay)
                    }
                } else {
                    DefaultRelayButton(main)
                    Text(DEFAULT_RELAY_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    }
}

/** One radio option: [title] and [subtitle] beside a radio button; the whole row selects. */
@Composable
private fun ConnectOption(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The message to send any agent, with Copy and Share, then the steps: send it, then "Tap Allow when this phone
 * asks", which spins while [waiting]. [onStarted] runs once it was copied or shared.
 */
@Composable
private fun PromptCard(prompt: String, waiting: Boolean, onStarted: () -> Unit, onUseToken: () -> Unit) {
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.AutoMirrored.Filled.Send, MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Text("Send this to your AI agent", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        }
        CopyableCode(
            prompt,
            copyLabel = "Message",
            sensitive = false,
            shareTitle = "Send to your agent",
            onShareFailed = { scope.launch { snackbar.showSnackbar("No app can receive shared text.") } },
            onCopied = onStarted,
        )
        GuideSteps(listOf(SEND_STEP), waiting)
        // Clients that can't sign in at all (config-file clients) take a token instead.
        TertiaryButton("Agent can't sign in? Use a token instead", onClick = onUseToken)
    }
}

/**
 * [steps] numbered, then the last one on this phone, "Tap Allow when this phone asks", which spins while
 * [waiting] for the agent.
 */
@Composable
private fun GuideSteps(steps: List<String>, waiting: Boolean, modifier: Modifier = Modifier) {
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

private const val SEND_STEP = "Send it to your agent (Claude, ChatGPT, Grok or any other) in a chat, or on the computer it runs on."
private const val ALLOW_STEP = "Tap Allow when this phone asks."
private const val ALLOW_STEP_WAITING = "Waiting for your agent… then tap Allow when this phone asks."

/** A titled white group holding free-form content (a form, a URL, a button), padded like a card. */
@Composable
private fun FormSection(title: String, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    ListSection(title = title, footer = footer) {
        Column(Modifier.padding(CardPadding), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}
