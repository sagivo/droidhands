package dev.phonebridge.android.ui.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.phonebridge.android.service.Tailnet
import dev.phonebridge.android.service.TailscaleState
import dev.phonebridge.android.ui.components.CopyableCode
import dev.phonebridge.android.ui.components.LocalSnackbarHostState
import dev.phonebridge.android.ui.components.SystemIntents
import kotlinx.coroutines.launch

/**
 * Every route that is on, each with its connection URL (Copy / Share) or what it is waiting for.
 * Nothing when no route is on.
 */
@Composable
internal fun ActiveRoutesCard(routes: List<ActiveRoute>, modifier: Modifier = Modifier) {
    if (routes.isEmpty()) return
    OutlinedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (routes.size == 1) "Your connection URL" else "Your connection URLs", style = MaterialTheme.typography.titleMedium)
            Text(ConnectionRoutes.PASTE_HINT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            routes.forEachIndexed { index, route ->
                if (index > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text(route.title, style = MaterialTheme.typography.titleSmall)
                RouteUrl(route, withHint = false)
            }
        }
    }
}

/** One route's URL with Copy / Share, or what it is waiting for. [withHint]: say what to do with the URL. */
@Composable
internal fun RouteUrl(route: ActiveRoute, modifier: Modifier = Modifier, withHint: Boolean = true) {
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(route.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        route.url?.let { url ->
            if (withHint) Text(ConnectionRoutes.PASTE_HINT, style = MaterialTheme.typography.bodyMedium)
            CopyableCode(
                url,
                copyLabel = "Connection URL",
                sensitive = false,
                shareTitle = "Share connection URL",
                onShareFailed = { scope.launch { snackbar.showSnackbar("No app can receive shared text.") } },
            )
        }
    }
}

/** Tailscale on this phone in one sentence, with Get Tailscale / Open Tailscale while it isn't connected. */
@Composable
internal fun TailscaleStatus(state: TailscaleState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(ConnectionRoutes.tailscaleStatus(state), style = MaterialTheme.typography.bodyMedium)
        if (!state.connected) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val open = if (state.installed) SystemIntents.openApp(context, Tailnet.TAILSCALE_PACKAGE) else null
                if (open != null) {
                    OutlinedButton(onClick = { SystemIntents.launch(context, open) }) { Text("Open Tailscale") }
                } else {
                    OutlinedButton(onClick = { SystemIntents.launch(context, *SystemIntents.playStore(Tailnet.TAILSCALE_PACKAGE)) }) {
                        Text("Get Tailscale")
                    }
                }
            }
        }
    }
}
