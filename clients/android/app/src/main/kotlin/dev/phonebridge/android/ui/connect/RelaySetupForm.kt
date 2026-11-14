package dev.phonebridge.android.ui.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.phonebridge.android.graph.LocalServerStatus
import dev.phonebridge.android.settings.BridgeSettings
import dev.phonebridge.android.ui.components.Notice
import dev.phonebridge.android.ui.components.NoticeKind
import dev.phonebridge.android.ui.components.StatusDot
import dev.phonebridge.android.ui.components.forLevel
import dev.phonebridge.android.ui.home.StatusPresentation
import dev.phonebridge.android.ui.settings.RelayUrlCheck
import dev.phonebridge.android.ui.settings.RelayUrls
import dev.phonebridge.android.ui.theme.statusColors
import dev.phonebridge.core.transport.RelayStatus

/**
 * "Paste your relay URL": URL and optional access key, validated as you type, then Connect. The
 * access key stays in memory only (never in saved instance state).
 */
@Composable
internal fun RelaySetupForm(settings: BridgeSettings, onConnect: (url: String, key: String) -> Unit, modifier: Modifier = Modifier) {
    var url by rememberSaveable { mutableStateOf(settings.relayUrl) }
    var key by remember { mutableStateOf(settings.relayAccessKey) }
    var showKey by remember { mutableStateOf(false) }
    val check = remember(url) { RelayUrls.check(url) }
    val error = (check as? RelayUrlCheck.Invalid)?.message
    val usable = check is RelayUrlCheck.Ok || check is RelayUrlCheck.Insecure
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Relay URL") },
            placeholder = { Text("https://relay.example.com") },
            isError = error != null,
            supportingText = { Text(error ?: "Printed by your relay when it starts.") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        (check as? RelayUrlCheck.Insecure)?.let { Notice(it.message, kind = NoticeKind.DANGER) }
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text("Access key (if your relay has one)") },
            singleLine = true,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Hide" else "Show") } },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(onClick = { onConnect(url, key) }, enabled = usable) { Text("Connect") }
        }
    }
}

/** One status line (relay or local server) with a colored dot, as on Home. */
@Composable
internal fun ConnectionStatusLine(settings: BridgeSettings, relay: RelayStatus, local: LocalServerStatus, modifier: Modifier = Modifier) {
    val colors = statusColors()
    val lines = buildList {
        if (settings.relayEnabled || relay !is RelayStatus.Stopped) add(StatusPresentation.relay(relay, settings.relayEnabled))
        if (settings.localServerEnabled) add(StatusPresentation.local(local, true))
    }
    if (lines.isEmpty()) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        lines.forEach { line ->
            Row(verticalAlignment = Alignment.Top) {
                StatusDot(colors.forLevel(line.level), Modifier.padding(top = 5.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(line.title, style = MaterialTheme.typography.titleSmall)
                    line.detail?.takeIf { line.urls.isEmpty() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
