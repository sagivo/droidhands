package dev.phonebridge.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonebridge.android.BuildConfig
import dev.phonebridge.android.ui.components.BottomActionBar
import dev.phonebridge.android.ui.components.CodeBlock
import dev.phonebridge.android.ui.components.Notice
import dev.phonebridge.android.ui.components.NoticeKind
import dev.phonebridge.android.ui.components.ScreenScaffold
import dev.phonebridge.android.ui.components.SectionHeader
import dev.phonebridge.android.ui.components.SwitchRow
import dev.phonebridge.android.ui.components.padded
import dev.phonebridge.android.ui.components.rememberCopier

/** Connection settings (relay, local server, boot) plus device info and docs. */
@Composable
fun SettingsScreen(vm: SettingsViewModel) {
    val current by vm.settings.collectAsStateWithLifecycle()
    val form = vm.form
    val validation = remember(form) { form.validate() }
    val dirty = form.differsFrom(current)

    ScreenScaffold(
        title = "Settings",
        bottomBar = {
            if (dirty || vm.saving) {
                BottomActionBar {
                    TextButton(onClick = vm::discard, enabled = !vm.saving) { Text("Discard") }
                    Button(onClick = vm::save, enabled = validation.isValid && !vm.saving) {
                        if (vm.saving) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Save")
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(contentPadding = padding.padded(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "relay-header") { SectionHeader("Relay") }
            item(key = "relay") { RelaySection(form, validation, vm::edit) }
            item(key = "local-header") { SectionHeader("Local server") }
            item(key = "local") { LocalServerSection(form, validation, vm::edit) }
            item(key = "general-header") { SectionHeader("General") }
            item(key = "general") {
                SettingsCard {
                    SwitchRow(
                        title = "Start on boot",
                        subtitle = "Reconnect automatically after the phone restarts.",
                        checked = form.startOnBoot,
                        onCheckedChange = { v -> vm.edit { it.copy(startOnBoot = v) } },
                    )
                }
            }
            item(key = "about-header") { SectionHeader("About") }
            item(key = "about") { AboutSection(vm.deviceId) }
        }
    }
}

@Composable
private fun RelaySection(form: SettingsForm, validation: SettingsForm.Validation, edit: ((SettingsForm) -> SettingsForm) -> Unit) {
    var showKey by rememberSaveable { mutableStateOf(false) }
    SettingsCard {
        SwitchRow(
            title = "Connect to a relay",
            subtitle = "Reach this phone from anywhere. The phone connects out to your self-hosted relay, so it " +
                "works behind NAT and on mobile data.",
            checked = form.relayEnabled,
            onCheckedChange = { v -> edit { it.copy(relayEnabled = v) } },
        )
        OutlinedTextField(
            value = form.relayUrl,
            onValueChange = { v -> edit { it.copy(relayUrl = v) } },
            label = { Text("Relay URL") },
            placeholder = { Text("https://relay.example.com") },
            isError = validation.relayUrlError != null,
            supportingText = {
                Text(validation.relayUrlError ?: "Base URL of your relay. Agents connect to <URL>/mcp.")
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        validation.relayUrlWarning?.let { Notice(it, kind = NoticeKind.DANGER) }
        OutlinedTextField(
            value = form.relayAccessKey,
            onValueChange = { v -> edit { it.copy(relayAccessKey = v) } },
            label = { Text("Access key (optional)") },
            supportingText = { Text("Must match RELAY_ACCESS_KEY on the relay, if the relay sets one.") },
            singleLine = true,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            trailingIcon = {
                TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Hide" else "Show") }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun LocalServerSection(form: SettingsForm, validation: SettingsForm.Validation, edit: ((SettingsForm) -> SettingsForm) -> Unit) {
    SettingsCard {
        SwitchRow(
            title = "Serve MCP on this phone",
            subtitle = "For USB (adb forward) or same-network use, without a relay.",
            checked = form.localServerEnabled,
            onCheckedChange = { v -> edit { it.copy(localServerEnabled = v) } },
        )
        OutlinedTextField(
            value = form.localServerPort,
            onValueChange = { v -> edit { it.copy(localServerPort = v.filter { c -> c in '0'..'9' }.take(5)) } },
            label = { Text("Port") },
            isError = validation.portError != null,
            supportingText = { Text(validation.portError ?: "Default ${SettingsForm.DEFAULT_PORT}.") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        SwitchRow(
            title = "Listen on all interfaces",
            subtitle = "Off: only 127.0.0.1, reached over USB with adb forward. On: also reachable from your " +
                "Wi-Fi/LAN and Tailscale.",
            checked = form.localServerBindAll,
            onCheckedChange = { v -> edit { it.copy(localServerBindAll = v) } },
        )
        if (form.localServerBindAll) Notice(SettingsForm.BIND_ALL_WARNING, kind = NoticeKind.WARNING)
    }
}

@Composable
private fun AboutSection(deviceId: String) {
    val copy = rememberCopier()
    SettingsCard {
        Text("Device ID", style = MaterialTheme.typography.labelLarge)
        CodeBlock(deviceId)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Part of every token; the relay routes requests by it. Not a secret on its own.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { copy("Device ID", deviceId, false) }) { Text("Copy") }
        }
        Text("Version", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        Text(BuildConfig.VERSION_NAME, style = MaterialTheme.typography.bodyMedium)
        Text("Documentation", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        Text(
            DOCS,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val DOCS = """In the PhoneBridge repository:
• docs/ARCHITECTURE.md: how agent, relay and phone fit together
• docs/SECURITY.md: threat model, scopes and approval policies
• docs/TOOLS.md: every tool agents can call
• docs/PROTOCOL.md: MCP endpoint, relay tunnel and token format
• relay/: self-host the relay (Node.js or Docker)"""

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}
