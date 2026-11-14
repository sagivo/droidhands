package dev.phonebridge.android.ui.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.phonebridge.android.service.Tailnet
import dev.phonebridge.android.service.TailscaleState
import dev.phonebridge.android.ui.components.CardPadding
import dev.phonebridge.android.ui.components.CopyableCode
import dev.phonebridge.android.ui.components.IconRowInset
import dev.phonebridge.android.ui.components.IconTile
import dev.phonebridge.android.ui.components.ListDivider
import dev.phonebridge.android.ui.components.ListSection
import dev.phonebridge.android.ui.components.LocalSnackbarHostState
import dev.phonebridge.android.ui.components.SecondaryButton
import dev.phonebridge.android.ui.components.StatusPill
import dev.phonebridge.android.ui.components.SystemIntents
import dev.phonebridge.android.ui.components.accent
import dev.phonebridge.android.ui.components.forLevel
import dev.phonebridge.android.ui.home.StatusLevel
import dev.phonebridge.android.ui.theme.statusColors
import kotlinx.coroutines.launch

/**
 * Every route that is on, as one list group: a row per route (tile, name, who can use it, a "Live"
 * pill once it works) with its connection URL (Copy / Share) below, and the paste hint as the
 * footer. Nothing when no route is on. It has no title, so the caller's section header names it.
 */
@Composable
internal fun ActiveRoutesCard(routes: List<ActiveRoute>, modifier: Modifier = Modifier) {
    if (routes.isEmpty()) return
    ListSection(modifier = modifier, footer = ConnectionRoutes.PASTE_HINT) {
        routes.forEachIndexed { index, route ->
            if (index > 0) ListDivider(IconRowInset)
            RouteRow(route)
        }
    }
}

@Composable
private fun RouteRow(route: ActiveRoute) {
    val level = if (route.url != null) StatusLevel.OK else StatusLevel.PENDING
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = CardPadding, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(routeIcon(route.kind), level.accent())
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(route.title, style = MaterialTheme.typography.bodyLarge)
                Text(route.status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (route.url != null) {
                Spacer(Modifier.width(8.dp))
                StatusPill("Live", statusColors().forLevel(StatusLevel.OK), dot = true)
            }
        }
        route.url?.let { RouteCode(it, Modifier.padding(start = IconRowInset - CardPadding)) }
    }
}

/**
 * One route's URL with Copy / Share, or what it is waiting for. [withHint]: say what to do with the URL.
 * Has no container of its own, so it sits inside the caller's card.
 */
@Composable
internal fun RouteUrl(route: ActiveRoute, modifier: Modifier = Modifier, withHint: Boolean = true) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(route.status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        route.url?.let { url ->
            if (withHint) Text(ConnectionRoutes.PASTE_HINT, style = MaterialTheme.typography.bodyMedium)
            RouteCode(url)
        }
    }
}

@Composable
private fun RouteCode(url: String, modifier: Modifier = Modifier) {
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()
    CopyableCode(
        url,
        copyLabel = "Connection URL",
        sensitive = false,
        modifier = modifier,
        shareTitle = "Share connection URL",
        onShareFailed = { scope.launch { snackbar.showSnackbar("No app can receive shared text.") } },
    )
}

/**
 * Tailscale on this phone in one sentence, with Get Tailscale / Open Tailscale while it isn't connected.
 * Has no container of its own, so it sits inside the caller's card.
 */
@Composable
internal fun TailscaleStatus(state: TailscaleState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            ConnectionRoutes.tailscaleStatus(state),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!state.connected) {
            val open = if (state.installed) SystemIntents.openApp(context, Tailnet.TAILSCALE_PACKAGE) else null
            if (open != null) {
                SecondaryButton("Open Tailscale", onClick = { SystemIntents.launch(context, open) })
            } else {
                SecondaryButton(
                    "Get Tailscale",
                    onClick = { SystemIntents.launch(context, *SystemIntents.playStore(Tailnet.TAILSCALE_PACKAGE)) },
                )
            }
        }
    }
}

/** The tile glyph of a route: the same one everywhere routes are listed. */
internal fun routeIcon(kind: RouteKind): ImageVector = when (kind) {
    RouteKind.RELAY -> Icons.Filled.Share
    RouteKind.TAILSCALE -> Icons.Filled.Lock
    RouteKind.LAN -> Icons.Filled.Home
    RouteKind.USB -> Icons.Filled.Build
}
