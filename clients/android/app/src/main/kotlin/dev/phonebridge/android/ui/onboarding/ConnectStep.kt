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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonebridge.android.ui.MainViewModel
import dev.phonebridge.android.ui.connect.ConnectionStatusLine
import dev.phonebridge.android.ui.connect.RelaySetupForm
import dev.phonebridge.android.ui.deeplink.ConfigureLinks
import dev.phonebridge.core.transport.RelayStatus

/**
 * Step 3: connect the phone to the user's relay: paste the URL, or open the relay's setup link
 * (handled by the deep link), or go local-only.
 */
@Composable
internal fun ConnectStep(main: MainViewModel) {
    val settings by main.settings.collectAsStateWithLifecycle()
    val relay by main.relayStatus.collectAsStateWithLifecycle()
    val local by main.localServerStatus.collectAsStateWithLifecycle()

    StepTitle(
        "Connect to your relay",
        "Your AI agents reach this phone through a small relay you host yourself. The phone connects out to it, so it " +
            "works on Wi-Fi and mobile data.",
    )
    val connected = relay as? RelayStatus.Connected
    if (connected != null) {
        ElevatedCard(
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.width(12.dp))
                Text(
                    "Connected to ${ConfigureLinks.hostOf(settings.relayUrl) ?: "your relay"}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        return
    }
    ConnectionStatusLine(settings, relay, local, Modifier.semantics { liveRegion = LiveRegionMode.Polite })

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
private fun OptionCard(title: String, text: String, content: @Composable () -> Unit = {}) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}
