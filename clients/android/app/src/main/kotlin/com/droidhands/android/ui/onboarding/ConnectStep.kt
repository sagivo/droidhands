package com.droidhands.android.ui.onboarding

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidhands.android.graph.DefaultRelay
import com.droidhands.android.graph.LocalServerStatus
import com.droidhands.android.settings.BridgeSettings
import com.droidhands.android.ui.MainViewModel
import com.droidhands.android.ui.account.AccountRequiredNotice
import com.droidhands.android.ui.account.DefaultRelayButton
import com.droidhands.android.ui.account.rememberSignInNeeded
import com.droidhands.android.ui.components.Accents
import com.droidhands.android.ui.components.IconTile
import com.droidhands.android.ui.components.ListRow
import com.droidhands.android.ui.components.ListSection
import com.droidhands.android.ui.components.Notice
import com.droidhands.android.ui.components.StatusPill
import com.droidhands.android.ui.components.TertiaryButton
import com.droidhands.android.ui.connect.ConnectionStatusLine
import com.droidhands.android.ui.connect.LocalOnlyOption
import com.droidhands.android.ui.connect.OptionCard
import com.droidhands.android.ui.connect.OwnRelayOption
import com.droidhands.android.ui.connect.TailscaleOption
import com.droidhands.android.ui.deeplink.ConfigureLinks
import com.droidhands.android.ui.theme.statusColors
import com.droidhands.core.transport.RelayStatus

/**
 * Step 3: get the phone online. When the build comes with a default relay, continuing with
 * DroidHands is the one primary path, with a small "Do it yourself" option opening the screen
 * with self-hosted routes (your own relay, Tailscale, local server). Builds without a default
 * relay show the self-hosted options directly.
 */
@Composable
internal fun ConnectStep(main: MainViewModel, onOpenMoreOptions: () -> Unit) {
    val settings by main.settings.collectAsStateWithLifecycle()
    val relay by main.relayStatus.collectAsStateWithLifecycle()
    val local by main.localServerStatus.collectAsStateWithLifecycle()
    val tailscale by main.tailscale.collectAsStateWithLifecycle()
    // Back from installing or connecting Tailscale: look again.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { main.refreshTailscale() }

    if (DefaultRelay.inUse(settings)) {
        DefaultRelayOnline(main, settings, relay, local, onOpenMoreOptions)
        return
    }
    val online = relay is RelayStatus.Connected
    if (online) {
        StepHeader(
            Icons.Filled.CheckCircle,
            "Connect this phone",
            "AI agents can reach this phone from anywhere, on Wi-Fi or mobile data.",
            color = statusColors().ok,
        )
        ConnectedCard("Connected to ${ConfigureLinks.hostOf(settings.relayUrl) ?: "your relay"}")
        return
    }
    val defaultUrl = main.defaultRelayUrl?.takeIf { DefaultRelay.canOffer(settings, it) }
    if (defaultUrl != null) {
        StepHeader(
            Icons.Filled.Share,
            "Connect this phone",
            "Let your AI agents reach this phone from anywhere, on Wi-Fi or mobile data.",
        )
        ConnectionStatusLine(settings, relay, local, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        AccountRequiredNotice(main, relay)
        val host = ConfigureLinks.hostOf(defaultUrl) ?: defaultUrl
        OptionCard(
            title = "Connect using DroidHands",
            text = if (rememberSignInNeeded(main)) {
                "Sign in with Google for a free DroidHands account on $host. Works with every agent — switch to your own " +
                    "relay anytime."
            } else {
                "Connect through $host, the relay this app comes with. Works with every agent — switch to your own relay " +
                    "anytime."
            },
            icon = Icons.Filled.CheckCircle,
            color = Accents.Indigo,
        ) {
            DefaultRelayButton(main)
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            TertiaryButton("Do it yourself", onClick = onOpenMoreOptions)
        }
        return
    }
    StepHeader(
        Icons.Filled.Share,
        "Connect this phone",
        "Your AI agents reach this phone through a small relay you host yourself, on Wi-Fi or mobile data, or directly " +
            "over Tailscale.",
        color = MaterialTheme.colorScheme.primary,
    )
    ConnectionStatusLine(settings, relay, local, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    AccountRequiredNotice(main, relay)
    OwnRelayOption(main, settings)
    TailscaleOption(main, settings, local, tailscale)
    LocalOnlyOption(main, settings)
}

/** The default relay is in use: online (or getting there), what that means, and a link to other routes. */
@Composable
private fun DefaultRelayOnline(
    main: MainViewModel,
    settings: BridgeSettings,
    relay: RelayStatus,
    local: LocalServerStatus,
    onOpenMoreOptions: () -> Unit,
) {
    val host = ConfigureLinks.hostOf(settings.relayUrl) ?: settings.relayUrl
    if (relay is RelayStatus.Connected) {
        StepHeader(
            Icons.Filled.CheckCircle,
            "You're online",
            "AI agents can reach this phone from anywhere, on Wi-Fi or mobile data. Nothing to set up here.",
            color = statusColors().ok,
        )
        ConnectedCard("Online through $host")
    } else {
        StepHeader(
            Icons.Filled.Share,
            "Going online",
            "DroidHands connects this phone to a relay, so AI agents can reach it on Wi-Fi or mobile data.",
        )
        ConnectionStatusLine(settings, relay, local, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        AccountRequiredNotice(main, relay)
    }
    Notice(title = "About the relay", text = DEFAULT_RELAY_NOTE)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        TertiaryButton("More ways to connect", onClick = onOpenMoreOptions)
    }
}

/** "Connected to relay.example.com" as a green row with a Live pill; announced when it appears. */
@Composable
private fun ConnectedCard(text: String) {
    ListSection(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        ListRow(
            title = text,
            leading = { IconTile(Icons.Filled.CheckCircle, Accents.Green) },
            trailing = { StatusPill("Live", statusColors().ok, dot = true) },
        )
    }
}

/** What the default relay can and can't do (docs/SECURITY.md, "Relay operator"). */
internal const val DEFAULT_RELAY_NOTE =
    "The relay forwards your agents' requests to this phone over HTTPS. It can't do anything by itself: every agent " +
        "needs a token you approve on this phone. Whoever runs the relay could see the requests passing through, so for " +
        "maximum privacy, run your own relay."
