package com.droidhands.android.ui.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.droidhands.android.ui.components.Accents
import com.droidhands.android.ui.components.AppCard
import com.droidhands.android.ui.components.BottomActionBar
import com.droidhands.android.ui.components.ButtonSize
import com.droidhands.android.ui.components.CardPadding
import com.droidhands.android.ui.components.IconTile
import com.droidhands.android.ui.components.PrimaryButton
import com.droidhands.android.ui.components.ScreenScaffold
import com.droidhands.android.ui.components.SecondaryButton
import com.droidhands.android.ui.components.StatusPill
import com.droidhands.android.ui.components.TertiaryButton
import com.droidhands.android.ui.components.padded
import com.droidhands.android.ui.theme.statusColors
import com.droidhands.core.transport.RelayStatus

/**
 * Self-hosted and direct connection options: your own relay, Tailscale, or local server. Opened from
 * "Do it yourself" / "More ways to connect" in onboarding and on the Connect page.
 */
@Composable
internal fun MoreConnectOptionsScreen(
    main: MainViewModel,
    onBack: () -> Unit,
    onContinue: (() -> Unit)? = null,
) {
    val settings by main.settings.collectAsStateWithLifecycle()
    val relay by main.relayStatus.collectAsStateWithLifecycle()
    val local by main.localServerStatus.collectAsStateWithLifecycle()
    val tailscale by main.tailscale.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { main.refreshTailscale() }

    val ownRelaySettings = if (DefaultRelay.inUse(settings)) settings.copy(relayUrl = "", relayAccessKey = "") else settings
    val connectedOwnRoute = !DefaultRelay.inUse(settings) && (relay is RelayStatus.Connected || settings.localServerEnabled)

    ScreenScaffold(
        title = "More ways to connect",
        onBack = onBack,
        bottomBar = {
            if (onContinue != null && connectedOwnRoute) {
                BottomActionBar {
                    PrimaryButton("Continue", onClick = onContinue, size = ButtonSize.LARGE, fullWidth = true)
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding.padded()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                "Connect without the DroidHands relay: host your own relay, use Tailscale directly, or serve locally.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = CardPadding),
            )
            ConnectionStatusLine(settings, relay, local, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            OwnRelayOption(main, ownRelaySettings)
            TailscaleOption(main, settings, local, tailscale)
            LocalOnlyOption(main, settings)
        }
    }
}

/** Tailscale: the local server on the phone's tailnet address only. Shows its URL once it listens. */
@Composable
internal fun TailscaleOption(main: MainViewModel, settings: BridgeSettings, local: LocalServerStatus, tailscale: TailscaleState) {
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
internal fun OwnRelayOption(main: MainViewModel, settings: BridgeSettings) {
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
internal fun LocalOnlyOption(main: MainViewModel, settings: BridgeSettings) {
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

/** One way to connect: tile, title and what it is for, then its controls. [live] adds an "On" pill. */
@Composable
internal fun OptionCard(
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
