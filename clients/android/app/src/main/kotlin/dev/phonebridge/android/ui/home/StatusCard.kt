package dev.phonebridge.android.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.phonebridge.android.ui.components.StatusDot
import dev.phonebridge.android.ui.components.forLevel
import dev.phonebridge.android.ui.theme.statusColors

/**
 * The main status card: global access switch on a colored header, then relay and local server
 * status (with copyable URLs) and the short device id.
 */
@Composable
internal fun AccessStatusCard(
    accessEnabled: Boolean,
    relay: StatusLine,
    local: StatusLine,
    deviceIdShort: String,
    showRestart: Boolean,
    onAccessChange: (Boolean) -> Unit,
    onRestart: () -> Unit,
    onCopyUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(modifier.fillMaxWidth()) {
        AccessHeader(accessEnabled, onAccessChange)
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StatusLineRow(relay, onCopyUrl)
            HorizontalDivider()
            StatusLineRow(local, onCopyUrl)
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Device",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Text(deviceIdShort, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (showRestart) {
                OutlinedButton(onClick = onRestart, modifier = Modifier.align(Alignment.End)) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text("Restart")
                }
            }
        }
    }
}

@Composable
private fun AccessHeader(accessEnabled: Boolean, onAccessChange: (Boolean) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val container = if (accessEnabled) scheme.primaryContainer else scheme.errorContainer
    val content = if (accessEnabled) scheme.onPrimaryContainer else scheme.onErrorContainer
    val state = if (accessEnabled) "On" else "Paused"
    Surface(color = container, contentColor = content, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .toggleable(value = accessEnabled, role = Role.Switch, onValueChange = onAccessChange)
                .semantics { stateDescription = state }
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Agent access", style = MaterialTheme.typography.labelLarge)
                Text(state, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    if (accessEnabled) {
                        "Agents you connected can use this phone, within what you allowed."
                    } else {
                        "Every agent request is refused until you turn access back on. Agents stay connected."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.width(16.dp))
            Switch(
                checked = accessEnabled,
                onCheckedChange = null,
                colors = if (accessEnabled) SwitchDefaults.colors() else SwitchDefaults.colors(uncheckedTrackColor = scheme.surface),
            )
        }
    }
}

@Composable
private fun StatusLineRow(line: StatusLine, onCopyUrl: (String) -> Unit) {
    val color = statusColors().forLevel(line.level)
    Row(verticalAlignment = Alignment.Top) {
        StatusDot(color, Modifier.padding(top = 5.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(line.title, style = MaterialTheme.typography.titleSmall)
            line.detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            line.urls.forEach { url ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(
                            url,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    TextButton(onClick = { onCopyUrl(url) }) { Text("Copy") }
                }
            }
        }
    }
}
