package com.droidhands.android.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidhands.android.graph.DefaultRelay
import com.droidhands.android.graph.LocalServerStatus
import com.droidhands.android.service.TailscaleState
import com.droidhands.android.settings.BridgeSettings
import com.droidhands.android.ui.MainViewModel
import com.droidhands.android.ui.account.AccountRequiredNotice
import com.droidhands.android.ui.account.DefaultRelayButton
import com.droidhands.android.ui.account.rememberSignInNeeded
import com.droidhands.android.ui.components.Accents
import com.droidhands.android.ui.components.AppCard
import com.droidhands.android.ui.components.IconTile
import com.droidhands.android.ui.components.ListRow
import com.droidhands.android.ui.components.ListSection
import com.droidhands.android.ui.components.Notice
import com.droidhands.android.ui.components.SecondaryButton
import com.droidhands.android.ui.components.SectionHeader
import com.droidhands.android.ui.components.StatusPill
import com.droidhands.android.ui.components.TertiaryButton
import com.droidhands.android.ui.connect.ConnectionRoutes
import com.droidhands.android.ui.connect.ConnectionStatusLine
import com.droidhands.android.ui.connect.QrScanner
import com.droidhands.android.ui.connect.RelaySetupForm
import com.droidhands.android.ui.connect.RouteKind
import com.droidhands.android.ui.connect.RouteUrl
import com.droidhands.android.ui.connect.SCAN_LABEL
import com.droidhands.android.ui.connect.TailscaleStatus
import com.droidhands.android.ui.connect.routeIcon
import com.droidhands.android.ui.deeplink.ConfigureLinks
import com.droidhands.android.ui.theme.statusColors
import com.droidhands.core.transport.RelayStatus

/**
 * Step 3: get the phone online. With the build's default relay in use (a fresh install of a
 * release build) there is nothing to do: "You're online", an honest note on what the relay can
 * see, and "More ways to connect" below (Tailscale, your own relay, local only). Otherwise connect
 * to the user's relay: paste the URL, or open the relay's setup link (handled by the deep link), or
 * use Tailscale or the local server. Routes combine (the relay and Tailscale at once).
 */
@Composable
internal fun ConnectStep(main: MainViewModel) {
    val settings by main.settings.collectAsStateWithLifecycle()
    val relay by main.relayStatus.collectAsStateWithLifecycle()
    val local by main.localServerStatus.collectAsStateWithLifecycle()
    val tailscale by main.tailscale.collectAsStateWithLifecycle()
    // Back from installing or connecting Tailscale: look again.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { main.refreshTailscale() }

    if (DefaultRelay.inUse(settings)) {
        DefaultRelayOnline(main, settings, relay, local, tailscale)
        return
    }
    val online = relay is RelayStatus.Connected
    StepHeader(
        if (online) Icons.Filled.CheckCircle else Icons.Filled.Share,
        "Connect this phone",
        "Your AI agents reach this phone through a small relay you host yourself, on Wi-Fi or mobile data, or directly " +
            "over Tailscale.",
        color = if (online) statusColors().ok else MaterialTheme.colorScheme.primary,
    )
    if (online) {
        ConnectedCard("Connected to ${ConfigureLinks.hostOf(settings.relayUrl) ?: "your relay"}")
        return
    }
    ConnectionStatusLine(settings, relay, local, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    AccountRequiredNotice(main, relay)
    val defaultUrl = main.defaultRelayUrl
    if (defaultUrl != null && DefaultRelay.canOffer(settings, defaultUrl)) {
        val host = ConfigureLinks.hostOf(defaultUrl) ?: defaultUrl
        OptionCard(
            title = "Go online now",
            text = if (rememberSignInNeeded(main)) {
                "No relay of your own? Use $host, the relay this app comes with: sign in with Google to create your free " +
                    "DroidHands account. You can switch to your own relay at any time."
            } else {
                "No relay of your own? Use $host, the relay this app comes with. You can switch to your own relay at any time."
            },
            icon = Icons.Filled.CheckCircle,
            color = Accents.Indigo,
        ) {
            DefaultRelayButton(main)
        }
    }
    OwnRelayOption(main, settings)
    TailscaleOption(main, settings, local, tailscale)
    LocalOnlyOption(main, settings)
}

/** The default relay is in use: online (or getting there), what that means, and the other routes. */
@Composable
private fun DefaultRelayOnline(
    main: MainViewModel,
    settings: BridgeSettings,
    relay: RelayStatus,
    local: LocalServerStatus,
    tailscale: TailscaleState,
) {
    var more by rememberSaveable { mutableStateOf(settings.localServerEnabled) }
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
    if (more) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeader("More ways to connect")
            TailscaleOption(main, settings, local, tailscale)
            OwnRelayOption(main, settings.copy(relayUrl = "", relayAccessKey = ""))
            LocalOnlyOption(main, settings)
        }
    } else {
        TertiaryButton("More ways to connect", onClick = { more = true })
    }
}

/** Tailscale: the local server on the phone's tailnet address only. Shows its URL once it listens. */
@Composable
private fun TailscaleOption(main: MainViewModel, settings: BridgeSettings, local: LocalServerStatus, tailscale: TailscaleState) {
    val on = ConnectionRoutes.tailscaleOn(settings)
    OptionCard(
        title = ConnectionRoutes.TAILSCALE_TITLE,
        text = ConnectionRoutes.TAILSCALE_SUMMARY,
        icon = routeIcon(RouteKind.TAILSCALE),
        color = Accents.Blue,
        live = on,
    ) {
        TailscaleStatus(tailscale)
        if (on) {
            ConnectionRoutes.active(settings, RelayStatus.Stopped, local).filter { it.kind == RouteKind.TAILSCALE }.forEach { route ->
                RouteUrl(route)
            }
            TertiaryButton("Stop using Tailscale", onClick = { main.setTailscale(false) })
        } else {
            SecondaryButton("Use Tailscale", onClick = { main.setTailscale(true) })
        }
    }
}

/** The user's own relay: the setup link or the URL form, and the script that runs one through Tailscale. */
@Composable
private fun OwnRelayOption(main: MainViewModel, settings: BridgeSettings) {
    OptionCard(
        title = ConnectionRoutes.OWN_RELAY_TITLE,
        text = ConnectionRoutes.OWN_RELAY_SUMMARY,
        icon = routeIcon(RouteKind.RELAY),
        color = Accents.Indigo,
    ) {
        val context = LocalContext.current
        val scan = if (remember { QrScanner.isAvailable(context) }) "Tap \"$SCAN_LABEL\" and point the camera at the code, or open" else "Open"
        Text(
            "Your relay prints its setup link, with a QR code, when it starts. $scan the setup page on this phone (its " +
                "address ends in /setup) and tap \"Open in DroidHands\". DroidHands asks you to confirm, then connects. " +
                "Or paste the setup link or the relay's address here:",
            style = MaterialTheme.typography.bodyMedium,
        )
        RelaySetupForm(settings, onConnect = main::connectRelay)
        Text(ConnectionRoutes.OWN_RELAY_SCRIPT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** No relay at all: the local server, over USB or the user's own network. */
@Composable
private fun LocalOnlyOption(main: MainViewModel, settings: BridgeSettings) {
    val on = settings.localServerEnabled && !ConnectionRoutes.tailscaleOn(settings)
    OptionCard(
        title = "Local only (advanced)",
        text = "No relay: serve agents on this phone directly, over USB (adb forward) or your own network. Agents connect " +
            "the same way: paste the URL, then tap Allow on this phone.",
        icon = Icons.Filled.Build,
        color = Accents.Gray,
        live = on,
    ) {
        if (on) {
            Text("Local server is on (port ${settings.localServerPort}).", style = MaterialTheme.typography.bodyMedium)
        } else {
            SecondaryButton("Use the local server", onClick = main::enableLocalServer)
        }
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

/** One way to connect: tile, title and what it is for, then its controls. [live] adds an "On" pill. */
@Composable
private fun OptionCard(
    title: String,
    text: String,
    icon: ImageVector,
    color: Color,
    live: Boolean = false,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    AppCard {
        Row(verticalAlignment = Alignment.Top) {
            IconTile(icon, color)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                    if (live) {
                        Spacer(Modifier.width(8.dp))
                        StatusPill("On", statusColors().ok, dot = true)
                    }
                }
                Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        content()
    }
}

/** What the default relay can and can't do (docs/SECURITY.md, "Relay operator"). */
internal const val DEFAULT_RELAY_NOTE =
    "The relay forwards your agents' requests to this phone over HTTPS. It can't do anything by itself: every agent " +
        "needs a token you approve on this phone. Whoever runs the relay could see the requests passing through, so for " +
        "maximum privacy, run your own relay."
