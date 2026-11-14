package dev.phonebridge.android.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.phonebridge.android.ui.components.Accents
import dev.phonebridge.android.ui.components.AppCard
import dev.phonebridge.android.ui.components.CardPadding
import dev.phonebridge.android.ui.components.IconBadge
import dev.phonebridge.android.ui.components.IconRowInset
import dev.phonebridge.android.ui.components.IconTile
import dev.phonebridge.android.ui.components.ListDivider
import dev.phonebridge.android.ui.components.ListRow
import dev.phonebridge.android.ui.components.ListSection
import dev.phonebridge.android.ui.components.SecondaryButton
import dev.phonebridge.android.ui.components.StatusPill
import dev.phonebridge.android.ui.components.TertiaryButton
import dev.phonebridge.android.ui.components.accent
import dev.phonebridge.android.ui.components.forLevel
import dev.phonebridge.android.ui.theme.statusColors

/**
 * The hero card at the top of Home: the global access switch, big and unmistakable (the pattern of
 * Revolut's card freeze or Tailscale's connect switch). The whole card toggles.
 */
@Composable
internal fun AccessHeroCard(accessEnabled: Boolean, onAccessChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val colors = statusColors()
    val state = if (accessEnabled) "On" else "Paused"
    AppCard(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .toggleable(value = accessEnabled, role = Role.Switch, onValueChange = onAccessChange)
            .semantics { stateDescription = state },
        contentPadding = PaddingValues(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(
                if (accessEnabled) Icons.Filled.CheckCircle else Icons.Filled.Lock,
                if (accessEnabled) colors.ok else colors.warning,
                size = 48.dp,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Agent access", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(state, style = MaterialTheme.typography.headlineMedium)
            }
            Switch(checked = accessEnabled, onCheckedChange = null)
        }
        Text(
            if (accessEnabled) {
                "Agents you connected can use this phone, within what you allowed."
            } else {
                "Every agent request is refused until you turn access back on. Agents stay connected."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "Connection": relay and local server status (with copyable URLs), then the short device id. */
@Composable
internal fun ConnectionSection(
    relay: StatusLine,
    local: StatusLine,
    deviceIdShort: String,
    showRestart: Boolean,
    onRestart: () -> Unit,
    onCopyUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    ListSection(
        modifier = modifier,
        title = "Connection",
        action = if (showRestart) {
            { TertiaryButton("Restart", icon = Icons.Filled.Refresh, onClick = onRestart) }
        } else {
            null
        },
    ) {
        StatusLineRow(relay, Icons.Filled.Share, onCopyUrl)
        ListDivider(IconRowInset)
        StatusLineRow(local, Icons.Filled.Phone, onCopyUrl)
        ListDivider(IconRowInset)
        ListRow(
            title = "Device",
            leading = { IconTile(Icons.Filled.Info, Accents.Gray) },
            trailing = {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Text(deviceIdShort, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
        )
    }
}

@Composable
private fun StatusLineRow(line: StatusLine, icon: ImageVector, onCopyUrl: (String) -> Unit) {
    val color = statusColors().forLevel(line.level)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = CardPadding, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon, line.level.accent())
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(line.title, style = MaterialTheme.typography.bodyLarge)
                line.detail?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (line.level == StatusLevel.OK) {
                Spacer(Modifier.width(8.dp))
                StatusPill("Live", color, dot = true)
            }
        }
        line.urls.forEach { url -> UrlChip(url, onCopy = { onCopyUrl(url) }) }
    }
}

/** A URL in a code-style field, with Copy. Indented to line up with the row text. */
@Composable
private fun UrlChip(url: String, onCopy: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = IconRowInset - CardPadding),
    ) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Text(
                    url,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 10.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            SecondaryButton("Copy", onClick = onCopy)
        }
    }
}
