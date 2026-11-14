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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonebridge.android.graph.DefaultRelay
import dev.phonebridge.android.graph.LocalServerStatus
import dev.phonebridge.android.settings.BridgeSettings
import dev.phonebridge.android.ui.MainViewModel
import dev.phonebridge.android.ui.components.Notice
import dev.phonebridge.android.ui.connect.ConnectionStatusLine
import dev.phonebridge.android.ui.connect.RelaySetupForm
import dev.phonebridge.android.ui.deeplink.ConfigureLinks
import dev.phonebridge.core.transport.RelayStatus

/**
 * Step 3: get the phone online. With the build's default relay in use (a fresh install of a
 * release build) there is nothing to do: "You're online", an honest note on what the relay can
 * see, and "Use my own relay" below. Otherwise connect to the user's relay: paste the URL, or open
 * the relay's setup link (handled by the deep link), or go local-only.
 */
@Composable
internal fun ConnectStep(main: MainViewModel) {
    val settings by main.settings.collectAsStateWithLifecycle()
    val relay by main.relayStatus.collectAsStateWithLifecycle()
    val local by main.localServerStatus.collectAsStateWithLifecycle()

    if (DefaultRelay.inUse(settings)) {
        DefaultRelayOnline(main, settings, relay, local)
        return
    }
    StepTitle(
        "Connect to your relay",
        "Your AI agents reach this phone through a small relay you host yourself. The phone connects out to it, so it " +
            "works on Wi-Fi and mobile data.",
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
    OwnRelayOptions(main, settings)
}

/** The default relay is in use: online (or getting there), what that means, and the way out. */
@Composable
private fun DefaultRelayOnline(main: MainViewModel, settings: BridgeSettings, relay: RelayStatus, local: LocalServerStatus) {
    var ownRelay by rememberSaveable { mutableStateOf(false) }
    val host = ConfigureLinks.hostOf(settings.relayUrl) ?: settings.relayUrl
    if (relay is RelayStatus.Connected) {
        StepTitle("You're online", "AI agents can reach this phone from anywhere, on Wi-Fi or mobile data. Nothing to set up here.")
        ConnectedCard("Online through $host")
    } else {
        StepTitle("Going online", "PhoneBridge connects this phone to a relay, so AI agents can reach it on Wi-Fi or mobile data.")
        ConnectionStatusLine(settings, relay, local, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
    Notice(title = "About the relay", text = DEFAULT_RELAY_NOTE)
    if (ownRelay) {
        OwnRelayOptions(main, settings.copy(relayUrl = "", relayAccessKey = ""))
    } else {
        TextButton(onClick = { ownRelay = true }) { Text("Use my own relay") }
    }
}

/** The user's own relay: the setup link, the URL form, or no relay at all. */
@Composable
private fun OwnRelayOptions(main: MainViewModel, settings: BridgeSettings) {
    OptionCard(
        title = "I opened the setup link",
        text = "On this phone, open your relay's setup page (its address ends in /setup; the relay prints it, with a QR " +
            "code, when it starts) and tap \"Open in PhoneBridge\". PhoneBridge asks you to confirm, then connects.",
    )
    OptionCard(title = "Paste your relay URL", text = "Or type the address yourself:") {
        RelaySetupForm(settings, onConnect = main::connectRelay)
    }
    OptionCard(
        title = "Local only (advanced)",
        text = "No relay: serve agents on this phone directly, over USB (adb forward) or your own network. One-tap agent " +
            "connect needs a relay; with local only you use tokens.",
    ) {
        if (settings.localServerEnabled) {
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
