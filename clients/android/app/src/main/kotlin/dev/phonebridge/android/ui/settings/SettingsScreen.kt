package dev.phonebridge.android.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonebridge.android.BuildConfig
import dev.phonebridge.android.plugins.LocalPluginEntry
import dev.phonebridge.android.plugins.PluginImports
import dev.phonebridge.android.service.TailscaleState
import dev.phonebridge.android.settings.LocalBind
import dev.phonebridge.android.ui.components.BottomActionBar
import dev.phonebridge.android.ui.components.CodeBlock
import dev.phonebridge.android.ui.components.ConfirmDialog
import dev.phonebridge.android.ui.components.Notice
import dev.phonebridge.android.ui.components.NoticeKind
import dev.phonebridge.android.ui.components.ScreenScaffold
import dev.phonebridge.android.ui.components.SectionHeader
import dev.phonebridge.android.ui.components.SwitchRow
import dev.phonebridge.android.ui.components.padded
import dev.phonebridge.android.ui.components.rememberCopier
import dev.phonebridge.android.ui.connect.ActiveRoutesCard
import dev.phonebridge.android.ui.connect.ConnectionRoutes
import dev.phonebridge.android.ui.connect.TailscaleStatus
import dev.phonebridge.android.ui.onboarding.DEFAULT_RELAY_NOTE
import dev.phonebridge.android.ui.setup.SetupUiState

/** Navigation callbacks of the Settings tab. */
class SettingsActions(
    val onOpenPermissions: () -> Unit,
    val onOpenNeverAllow: () -> Unit,
    val onRevokeAll: () -> Unit,
    val onRunSetupAgain: () -> Unit,
)

/**
 * Settings: How agents reach this phone (the connection URLs of the routes that are on, then the
 * relay, Tailscale, the local server and boot; saved together from the bottom bar),
 * Permissions & access, Security (never-allow apps, hidden one-time codes, revoke all), Developer
 * (local plugins) and About.
 */
@Composable
fun SettingsScreen(vm: SettingsViewModel, setup: SetupUiState, activeAgents: Int, actions: SettingsActions) {
    val current by vm.settings.collectAsStateWithLifecycle()
    val neverAllow by vm.neverAllowApps.collectAsStateWithLifecycle()
    val redact by vm.redactOneTimeCodes.collectAsStateWithLifecycle()
    val localPluginsOn by vm.localPluginsEnabled.collectAsStateWithLifecycle()
    val localPlugins by vm.localPlugins.collectAsStateWithLifecycle()
    val relayStatus by vm.relayStatus.collectAsStateWithLifecycle()
    val localStatus by vm.localServerStatus.collectAsStateWithLifecycle()
    val tailscale by vm.tailscale.collectAsStateWithLifecycle()
    val form = vm.form
    val validation = remember(form) { form.validate() }
    val dirty = form.differsFrom(current)
    var removing by remember { mutableStateOf<LocalPluginEntry?>(null) }
    val pickPlugin = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importPlugin) }

    // Tailscale is switched on and off outside the app.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshTailscale() }
    // Files pushed with scripts/plugin-push.sh show up whenever Settings opens.
    LaunchedEffect(localPluginsOn) { if (localPluginsOn) vm.refreshLocalPlugins() }
    vm.importErrors?.let { ImportErrorsDialog(it, onDismiss = vm::dismissImportErrors) }
    removing?.let { entry ->
        ConfirmDialog(
            title = "Remove ${entry.plugin?.name ?: entry.id}?",
            text = "Its actions disappear for every agent. You can import it again later.",
            confirmLabel = "Remove",
            destructive = true,
            onConfirm = { vm.removeLocalPlugin(entry.id) },
            onDismiss = { removing = null },
        )
    }

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
            item(key = "connection-header") { SectionHeader("How agents reach this phone") }
            item(key = "routes") {
                val routes = remember(current, relayStatus, localStatus) { ConnectionRoutes.active(current, relayStatus, localStatus) }
                if (routes.isEmpty()) {
                    Notice("Agents can't reach this phone yet. Turn on the relay or Tailscale below, then tap Save.", kind = NoticeKind.WARNING)
                } else {
                    ActiveRoutesCard(routes)
                }
            }
            item(key = "relay") {
                val usingDefault = current.relayFromDefault && RelayUrls.normalize(form.relayUrl) == current.relayUrl
                RelaySection(form, validation, usingDefault, vm::edit)
            }
            item(key = "tailscale") { TailscaleSection(form, tailscale, vm::edit) }
            item(key = "local") { LocalServerSection(form, validation, vm::edit) }
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
            item(key = "activity-notification") {
                SettingsCard {
                    SwitchRow(
                        title = "Show when agents are working",
                        subtitle = "While an agent's action runs, a notification says which agent and what it is doing, " +
                            "with Stop and Pause all. On Android 16 it also shows in the status bar. Message text and " +
                            "codes are never shown in it.",
                        checked = current.showAgentActivity,
                        onCheckedChange = vm::setShowAgentActivity,
                    )
                }
            }
            item(key = "permissions-header") { SectionHeader("Permissions & access") }
            item(key = "permissions") { PermissionsRow(setup, actions.onOpenPermissions) }
            item(key = "security-header") { SectionHeader("Security") }
            item(key = "security") {
                SecuritySection(
                    neverAllowCount = neverAllow.size,
                    redactOneTimeCodes = redact,
                    activeAgents = activeAgents,
                    onOpenNeverAllow = actions.onOpenNeverAllow,
                    onRedactChange = vm::setRedactOneTimeCodes,
                    onRevokeAll = actions.onRevokeAll,
                )
            }
            item(key = "developer-header") { SectionHeader("Developer") }
            item(key = "developer") {
                DeveloperSection(
                    enabled = localPluginsOn,
                    plugins = localPlugins,
                    importing = vm.importing,
                    onEnabledChange = vm::setLocalPluginsEnabled,
                    onImport = { pickPlugin.launch(PluginImports.MIME_TYPES) },
                    onRemove = { removing = it },
                )
            }
            item(key = "about-header") { SectionHeader("About") }
            item(key = "about") {
                AboutSection(
                    deviceId = vm.deviceId,
                    onRunSetupAgain = actions.onRunSetupAgain,
                    // Only builds that name a GitHub repository (release builds) can check for updates.
                    checkForUpdates = if (vm.updatesSupported) current.checkForUpdates else null,
                    onCheckForUpdatesChange = vm::setCheckForUpdates,
                )
            }
        }
    }
}

@Composable
private fun RelaySection(
    form: SettingsForm,
    validation: SettingsForm.Validation,
    usingDefault: Boolean,
    edit: ((SettingsForm) -> SettingsForm) -> Unit,
) {
    var showKey by rememberSaveable { mutableStateOf(false) }
    SettingsCard {
        SwitchRow(
            title = "Relay",
            subtitle = "Works with every agent, including claude.ai and ChatGPT, from anywhere. The phone connects out " +
                "to the relay, so it works behind NAT and on mobile data.",
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
        if (usingDefault) Notice(DEFAULT_RELAY_NOTE, title = "The relay this app comes with")
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
        Text(
            "${ConnectionRoutes.OWN_RELAY_TITLE}: open its setup link on this phone, or enter its URL above. " +
                ConnectionRoutes.OWN_RELAY_SCRIPT,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The Tailscale route: the local server on the tailnet only (the same switch as "Tailscale only" below). */
@Composable
private fun TailscaleSection(form: SettingsForm, tailscale: TailscaleState, edit: ((SettingsForm) -> SettingsForm) -> Unit) {
    SettingsCard {
        SwitchRow(
            title = ConnectionRoutes.TAILSCALE_TITLE,
            subtitle = ConnectionRoutes.TAILSCALE_SUMMARY,
            checked = form.tailscaleOn,
            onCheckedChange = { v -> edit { it.withTailscale(v) } },
        )
        TailscaleStatus(tailscale)
    }
}

@Composable
private fun LocalServerSection(form: SettingsForm, validation: SettingsForm.Validation, edit: ((SettingsForm) -> SettingsForm) -> Unit) {
    SettingsCard {
        SwitchRow(
            title = "Local server (advanced)",
            subtitle = "Serve agents from this phone without a relay: over USB (adb forward), Tailscale or your own network.",
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
        Text("Listen on", style = MaterialTheme.typography.labelLarge)
        Column(Modifier.selectableGroup()) {
            BIND_CHOICES.forEach { (bind, title, subtitle) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(selected = form.localServerBind == bind, role = Role.RadioButton) {
                            edit { it.copy(localServerBind = bind) }
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = form.localServerBind == bind, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(title, style = MaterialTheme.typography.bodyLarge)
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (form.localServerBind == LocalBind.ALL_INTERFACES) Notice(SettingsForm.BIND_ALL_WARNING, kind = NoticeKind.WARNING)
    }
}

@Composable
private fun AboutSection(
    deviceId: String,
    onRunSetupAgain: () -> Unit,
    /** Null when this build can't check for updates (no GitHub repository built in). */
    checkForUpdates: Boolean?,
    onCheckForUpdatesChange: (Boolean) -> Unit,
) {
    val copy = rememberCopier()
    SettingsCard {
        Text("Version", style = MaterialTheme.typography.labelLarge)
        Text(BuildConfig.VERSION_NAME, style = MaterialTheme.typography.bodyMedium)
        if (checkForUpdates != null) {
            SwitchRow(
                title = "Check for updates",
                subtitle = "Once a day, ask GitHub whether a newer PhoneBridge is out, and say so on Home. The request " +
                    "carries no account or phone data, and nothing is installed without you.",
                checked = checkForUpdates,
                onCheckedChange = onCheckForUpdatesChange,
            )
        }
        Text("Device ID", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
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
        OutlinedButton(onClick = onRunSetupAgain, modifier = Modifier.padding(top = 8.dp)) { Text("Run setup again") }
        Text("Documentation", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        Text(
            DOCS,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The local server's "Listen on" choices: mode, title, who can reach it. */
private val BIND_CHOICES = listOf(
    Triple(LocalBind.LOOPBACK, "This phone only", "Over USB, with adb forward on your computer."),
    Triple(LocalBind.TAILSCALE, "Tailscale only", "Your own devices on your tailnet."),
    Triple(LocalBind.ALL_INTERFACES, "All networks", "Anyone on the same Wi-Fi, hotspot or VPN. Unencrypted http://."),
)

private const val DOCS = """In the PhoneBridge repository:
• docs/CONNECTING.md: the ways agents reach this phone (relay, Tailscale, your own relay)
• docs/ARCHITECTURE.md: how agent, relay and phone fit together
• docs/SECURITY.md: threat model, scopes and approval policies
• docs/TOOLS.md: every tool agents can call
• docs/PROTOCOL.md: MCP endpoint, relay tunnel and token format
• relay/: self-host the relay (Node.js or Docker)"""

@Composable
internal fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}
