package dev.phonebridge.android.ui.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import dev.phonebridge.android.ui.components.IconRowInset
import dev.phonebridge.android.ui.components.IconTile
import dev.phonebridge.android.ui.components.ListDivider
import dev.phonebridge.android.ui.components.ListRow
import dev.phonebridge.android.ui.components.ListSection
import dev.phonebridge.android.ui.components.Notice
import dev.phonebridge.android.ui.components.NoticeKind
import dev.phonebridge.android.ui.components.PrimaryButton
import dev.phonebridge.android.ui.components.StatusPill
import dev.phonebridge.android.ui.components.TertiaryButton
import dev.phonebridge.android.ui.components.accent
import dev.phonebridge.android.ui.components.forLevel
import dev.phonebridge.android.ui.deeplink.SetupQrPayload
import dev.phonebridge.android.ui.home.StatusLevel
import dev.phonebridge.android.ui.home.StatusPresentation
import dev.phonebridge.android.ui.settings.RelayUrlCheck
import dev.phonebridge.android.ui.settings.RelayUrls
import dev.phonebridge.android.ui.theme.statusColors
import dev.phonebridge.core.transport.RelayStatus

/**
 * "Paste your relay URL": URL and optional access key, validated as you type, then Connect. The
 * access key stays in memory only (never in saved instance state). A setup link pasted into the URL
 * field (`…/setup#key=…` or `phonebridge://configure…`) goes to the "Connect to relay?" dialog instead,
 * as does a scanned QR code ([ScanSetupQrButton]). Has no container of its own, so it sits inside the
 * caller's card.
 */
@Composable
internal fun RelaySetupForm(settings: BridgeSettings, onConnect: (url: String, key: String) -> Unit, modifier: Modifier = Modifier) {
    var url by rememberSaveable { mutableStateOf(settings.relayUrl) }
    var key by remember { mutableStateOf(settings.relayAccessKey) }
    var showKey by remember { mutableStateOf(false) }
    val check = remember(url) { RelayUrls.check(url) }
    val links = rememberConfigureLinks()
    val setupLink = remember(url) { links != null && SetupQrPayload.isSetupLink(url) }
    val error = (check as? RelayUrlCheck.Invalid)?.message?.takeUnless { setupLink }
    val usable = check is RelayUrlCheck.Ok || check is RelayUrlCheck.Insecure
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Relay URL") },
            placeholder = { Text("https://relay.example.com") },
            isError = error != null,
            supportingText = {
                Text(error ?: if (setupLink) "A setup link: tap Connect to review it." else "Printed by your relay when it starts.")
            },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        (check as? RelayUrlCheck.Insecure)?.let { Notice(it.message, kind = NoticeKind.DANGER) }
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text("Access key (if your relay has one)") },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            trailingIcon = { TertiaryButton(if (showKey) "Hide" else "Show", onClick = { showKey = !showKey }) },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ScanSetupQrButton()
            PrimaryButton(
                "Connect",
                onClick = { if (setupLink) links?.offerScanned(url) else onConnect(url, key) },
                enabled = usable || setupLink,
            )
        }
    }
}

/**
 * The relay and local server status as list rows (tile in the status color, a "Live" pill once
 * connected), as on Home. [footer] explains below the group. Nothing when neither is on.
 */
@Composable
internal fun ConnectionStatusLine(
    settings: BridgeSettings,
    relay: RelayStatus,
    local: LocalServerStatus,
    modifier: Modifier = Modifier,
    footer: String? = null,
) {
    val colors = statusColors()
    val lines = buildList {
        if (settings.relayEnabled || relay !is RelayStatus.Stopped) {
            add(Icons.Filled.Share to StatusPresentation.relay(relay, settings.relayEnabled))
        }
        if (settings.localServerEnabled) add(Icons.Filled.Phone to StatusPresentation.local(local, true, settings.localServerBind))
    }
    if (lines.isEmpty()) return
    ListSection(modifier = modifier, footer = footer) {
        lines.forEachIndexed { index, (icon, line) ->
            if (index > 0) ListDivider(IconRowInset)
            ListRow(
                title = line.title,
                subtitle = line.detail?.takeIf { line.urls.isEmpty() },
                leading = { IconTile(icon, line.level.accent()) },
                trailing = if (line.level == StatusLevel.OK) {
                    { StatusPill("Live", colors.forLevel(line.level), dot = true) }
                } else {
                    null
                },
            )
        }
    }
}
