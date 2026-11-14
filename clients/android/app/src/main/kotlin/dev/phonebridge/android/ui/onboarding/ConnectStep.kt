package dev.phonebridge.android.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonebridge.android.graph.DefaultRelay
import dev.phonebridge.android.graph.LocalServerStatus
import dev.phonebridge.android.service.TailscaleState
import dev.phonebridge.android.settings.BridgeSettings
import dev.phonebridge.android.ui.MainViewModel
import dev.phonebridge.android.ui.components.Notice
import dev.phonebridge.android.ui.components.SectionHeader
import dev.phonebridge.android.ui.connect.ConnectionRoutes
import dev.phonebridge.android.ui.connect.ConnectionStatusLine
import dev.phonebridge.android.ui.connect.RelaySetupForm
import dev.phonebridge.android.ui.connect.RouteKind
import dev.phonebridge.android.ui.connect.RouteUrl
import dev.phonebridge.android.ui.connect.TailscaleStatus
import dev.phonebridge.android.ui.deeplink.ConfigureLinks
import dev.phonebridge.core.transport.RelayStatus

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
    StepTitle(
        "Connect this phone",
        "Your AI agents reach this phone through a small relay you host yourself, which works on Wi-Fi and mobile " +
            "data, or directly over Tailscale.",
    )
    if (relay is RelayStatus.Connected) {
        ConnectedCard("Connected to ${ConfigureLinks.hostOf(settings.relayUrl) ?: "your relay"}")
        return
    }
    ConnectionStatusLine(settings, relay, local, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    val defaultUrl = main.defaultRelayUrl
    if (defaultUrl != null && DefaultRelay.canOffer(settings, defaultUrl)) {
        OptionCard(
            title = "Go online now",
            text = "No relay of your own? Use ${ConfigureLinks.hostOf(defaultUrl) ?: defaultUrl}, the relay this app comes " +
                "with. You can switch to your own relay at any time.",
        ) {
            Button(onClick = main::useDefaultRelay) { Text("Go online") }
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
        StepTitle("You're online", "AI agents can reach this phone from anywhere, on Wi-Fi or mobile data. Nothing to set up here.")
        ConnectedCard("Online through $host")
    } else {
        StepTitle("Going online", "PhoneBridge connects this phone to a relay, so AI agents can reach it on Wi-Fi or mobile data.")
        ConnectionStatusLine(settings, relay, local, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
    Notice(title = "About the relay", text = DEFAULT_RELAY_NOTE)
    if (more) {
        SectionHeader("More ways to connect")
        TailscaleOption(main, settings, local, tailscale)
        OwnRelayOption(main, settings.copy(relayUrl = "", relayAccessKey = ""))
        LocalOnlyOption(main, settings)
    } else {
        TextButton(onClick = { more = true }) { Text("More ways to connect") }
    }
}

/** Tailscale: the local server on the phone's tailnet address only. Shows its URL once it listens. */
@Composable
private fun TailscaleOption(main: MainViewModel, settings: BridgeSettings, local: LocalServerStatus, tailscale: TailscaleState) {
    val on = ConnectionRoutes.tailscaleOn(settings)
    OptionCard(title = ConnectionRoutes.TAILSCALE_TITLE, text = ConnectionRoutes.TAILSCALE_SUMMARY) {
        TailscaleStatus(tailscale)
        if (on) {
            ConnectionRoutes.active(settings, RelayStatus.Stopped, local).filter { it.kind == RouteKind.TAILSCALE }.forEach { route ->
                RouteUrl(route)
            }
            TextButton(onClick = { main.setTailscale(false) }) { Text("Stop using Tailscale") }
        } else {
            Button(onClick = { main.setTailscale(true) }) { Text("Use Tailscale") }
        }
    }
}

/** The user's own relay: the setup link or the URL form, and the script that runs one through Tailscale. */
@Composable
private fun OwnRelayOption(main: MainViewModel, settings: BridgeSettings) {
    OptionCard(title = ConnectionRoutes.OWN_RELAY_TITLE, text = ConnectionRoutes.OWN_RELAY_SUMMARY) {
        Text(
            "On this phone, open your relay's setup page (its address ends in /setup; the relay prints it, with a QR " +
                "code, when it starts) and tap \"Open in PhoneBridge\". PhoneBridge asks you to confirm, then connects. " +
                "Or type the address yourself:",
            style = MaterialTheme.typography.bodyMedium,
        )
        RelaySetupForm(settings, onConnect = main::connectRelay)
        Text(ConnectionRoutes.OWN_RELAY_SCRIPT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** No relay at all: the local server, over USB or the user's own network. */
@Composable
private fun LocalOnlyOption(main: MainViewModel, settings: BridgeSettings) {
    OptionCard(
        title = "Local only (advanced)",
        text = "No relay: serve agents on this phone directly, over USB (adb forward) or your own network. Agents connect " +
            "the same way: paste the URL, then tap Allow on this phone.",
    ) {
        if (settings.localServerEnabled && !ConnectionRoutes.tailscaleOn(settings)) {
            Text("Local server is on (port ${settings.localServerPort}).", style = MaterialTheme.typography.bodyMedium)
        } else {
            OutlinedButton(onClick = main::enableLocalServer) { Text("Use the local server") }
        }
    }
}

@Composable
private fun ConnectedCard(text: String) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun OptionCard(title: String, text: String, content: @Composable () -> Unit = {}) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

/** What the default relay can and can't do (docs/SECURITY.md, "Relay operator"). */
internal const val DEFAULT_RELAY_NOTE =
    "The relay forwards your agents' requests to this phone over HTTPS. It can't do anything by itself: every agent " +
        "needs a token you approve on this phone. Whoever runs the relay could see the requests passing through, so for " +
        "maximum privacy, run your own relay."
