package dev.phonebridge.android.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import dev.phonebridge.android.ui.components.Accents
import dev.phonebridge.android.ui.components.BottomActionBar
import dev.phonebridge.android.ui.components.CardPadding
import dev.phonebridge.android.ui.components.ConfirmDialog
import dev.phonebridge.android.ui.components.IconRowInset
import dev.phonebridge.android.ui.components.IconTile
import dev.phonebridge.android.ui.components.ListDivider
import dev.phonebridge.android.ui.components.ListRow
import dev.phonebridge.android.ui.components.ListSection
import dev.phonebridge.android.ui.components.Notice
import dev.phonebridge.android.ui.components.NoticeKind
import dev.phonebridge.android.ui.components.PrimaryButton
import dev.phonebridge.android.ui.components.RowValue
import dev.phonebridge.android.ui.components.ScreenScaffold
import dev.phonebridge.android.ui.components.SectionHeader
import dev.phonebridge.android.ui.components.SwitchRow
import dev.phonebridge.android.ui.components.TertiaryButton
import dev.phonebridge.android.ui.components.padded
import dev.phonebridge.android.ui.components.rememberCopier
import dev.phonebridge.android.ui.connect.ActiveRoutesCard
import dev.phonebridge.android.ui.connect.ConnectionRoutes
import dev.phonebridge.android.ui.connect.ScanSetupQrButton
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
 * Settings, as inset grouped lists (the iOS Settings pattern): how agents reach this phone (the
 * connection URLs of the routes that are on, then the relay and Tailscale), General (permissions,
 * boot, the activity notification), Security (never-allow apps, hidden one-time codes), Advanced
 * (the local server), Developer (local plugins), About, and Revoke all last. Connection changes are
 * saved together from the bottom bar; switches outside the form apply at once.
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
        large = true,
        bottomBar = {
            if (dirty || vm.saving) {
                BottomActionBar {
                    TertiaryButton("Discard", onClick = vm::discard, enabled = !vm.saving)
                    PrimaryButton(
                        if (vm.saving) "Saving…" else "Save",
                        onClick = vm::save,
                        enabled = validation.isValid && !vm.saving,
                    )
                }
            }
        },
    ) { padding ->
        LazyColumn(contentPadding = padding.padded(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item(key = "routes") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionHeader("How agents reach this phone")
                    val routes = remember(current, relayStatus, localStatus) { ConnectionRoutes.active(current, relayStatus, localStatus) }
                    if (routes.isEmpty()) {
                        Notice("Agents can't reach this phone yet. Turn on the relay or Tailscale below, then tap Save.", kind = NoticeKind.WARNING)
                    } else {
                        ActiveRoutesCard(routes)
                    }
                }
            }
            item(key = "relay") {
                val usingDefault = current.relayFromDefault && RelayUrls.normalize(form.relayUrl) == current.relayUrl
                RelaySection(form, validation, usingDefault, vm::edit)
            }
            item(key = "tailscale") { TailscaleSection(form, tailscale, vm::edit) }
            item(key = "general") {
                ListSection(
                    title = "General",
                    footer = "While an agent's action runs, a notification says which agent and what it is doing, with Stop " +
                        "and Pause all. On Android 16 it also shows in the status bar. Message text and codes are never " +
                        "shown in it.",
                ) {
                    PermissionsRow(setup, actions.onOpenPermissions)
                    ListDivider(IconRowInset)
                    SwitchRow(
                        title = "Start on boot",
                        subtitle = "Reconnect automatically after the phone restarts.",
                        checked = form.startOnBoot,
                        onCheckedChange = { v -> vm.edit { it.copy(startOnBoot = v) } },
                        leading = { IconTile(Icons.Filled.PlayArrow, Accents.Gray) },
                    )
                    ListDivider(IconRowInset)
                    SwitchRow(
                        title = "Show when agents are working",
                        subtitle = "A notification with Stop and Pause all.",
                        checked = current.showAgentActivity,
                        onCheckedChange = vm::setShowAgentActivity,
                        leading = { IconTile(Icons.Filled.Notifications, Accents.Indigo) },
                    )
                }
            }
            item(key = "security") {
                SecuritySection(
                    neverAllowCount = neverAllow.size,
                    redactOneTimeCodes = redact,
                    onOpenNeverAllow = actions.onOpenNeverAllow,
                    onRedactChange = vm::setRedactOneTimeCodes,
                )
            }
            item(key = "local") { LocalServerSection(form, validation, vm::edit) }
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
            item(key = "about") {
                AboutSection(
                    deviceId = vm.deviceId,
                    onRunSetupAgain = actions.onRunSetupAgain,
                    // Only builds that name a GitHub repository (release builds) can check for updates.
                    checkForUpdates = if (vm.updatesSupported) current.checkForUpdates else null,
                    onCheckForUpdatesChange = vm::setCheckForUpdates,
                )
            }
            item(key = "revoke-all") { RevokeAllSection(activeAgents, actions.onRevokeAll) }
        }
    }
}

/** The relay switch, its URL and access key, and "Your own relay" with Scan QR code. */
@Composable
private fun RelaySection(
    form: SettingsForm,
    validation: SettingsForm.Validation,
    usingDefault: Boolean,
    edit: ((SettingsForm) -> SettingsForm) -> Unit,
) {
    var showKey by rememberSaveable { mutableStateOf(false) }
    ListSection(
        footer = "The relay works with every agent, including claude.ai and ChatGPT, from anywhere. The phone connects out " +
            "to it, so it works behind NAT and on mobile data. " + ConnectionRoutes.OWN_RELAY_SCRIPT,
    ) {
        SwitchRow(
            title = "Relay",
            subtitle = "Every agent, from anywhere.",
            checked = form.relayEnabled,
            onCheckedChange = { v -> edit { it.copy(relayEnabled = v) } },
            leading = { IconTile(Icons.Filled.Share, Accents.Indigo) },
        )
        ListDivider(IconRowInset)
        SectionBlock {
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
                shape = MaterialTheme.shapes.medium,
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
                shape = MaterialTheme.shapes.medium,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                trailingIcon = { TertiaryButton(if (showKey) "Hide" else "Show", onClick = { showKey = !showKey }) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        ListDivider()
        ListRow(
            title = ConnectionRoutes.OWN_RELAY_TITLE,
            subtitle = "Scan the QR code it prints, open its setup link on this phone, or enter its URL above.",
            leading = { IconTile(Icons.Filled.Home, Accents.Indigo) },
            trailing = { ScanSetupQrButton() },
        )
    }
}

/** The Tailscale route: the local server on the tailnet only (the same switch as "Tailscale only" below). */
@Composable
private fun TailscaleSection(form: SettingsForm, tailscale: TailscaleState, edit: ((SettingsForm) -> SettingsForm) -> Unit) {
    ListSection(footer = ConnectionRoutes.TAILSCALE_SUMMARY) {
        SwitchRow(
            title = ConnectionRoutes.TAILSCALE_TITLE,
            subtitle = "Your own devices, directly.",
            checked = form.tailscaleOn,
            onCheckedChange = { v -> edit { it.withTailscale(v) } },
            leading = { IconTile(Icons.Filled.Lock, Accents.Teal) },
        )
        ListDivider(IconRowInset)
        TailscaleStatus(
            tailscale,
            Modifier
                .fillMaxWidth()
                .padding(start = IconRowInset, end = CardPadding, top = 12.dp, bottom = 12.dp),
        )
    }
}

/** Advanced: the local server switch and port, then where it listens. */
@Composable
private fun LocalServerSection(form: SettingsForm, validation: SettingsForm.Validation, edit: ((SettingsForm) -> SettingsForm) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        ListSection(
            title = "Advanced",
            footer = "Serve agents from this phone without a relay: over USB (adb forward), Tailscale or your own network.",
        ) {
            SwitchRow(
                title = "Local server",
                subtitle = "Without a relay.",
                checked = form.localServerEnabled,
                onCheckedChange = { v -> edit { it.copy(localServerEnabled = v) } },
                leading = { IconTile(Icons.Filled.Phone, Accents.Gray) },
            )
            ListDivider(IconRowInset)
            SectionBlock {
                OutlinedTextField(
                    value = form.localServerPort,
                    onValueChange = { v -> edit { it.copy(localServerPort = v.filter { c -> c in '0'..'9' }.take(5)) } },
                    label = { Text("Port") },
                    isError = validation.portError != null,
                    supportingText = { Text(validation.portError ?: "Default ${SettingsForm.DEFAULT_PORT}.") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        ListSection(title = "Listen on") {
            Column(Modifier.selectableGroup()) {
                BIND_CHOICES.forEachIndexed { index, (bind, title, subtitle) ->
                    if (index > 0) ListDivider()
                    val selected = form.localServerBind == bind
                    ListRow(
                        title = title,
                        subtitle = subtitle,
                        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton) {
                            edit { it.copy(localServerBind = bind) }
                        },
                        trailing = if (selected) {
                            { Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                        } else {
                            null
                        },
                    )
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
    ListSection(
        title = "About",
        footer = "The device ID is part of every token; the relay routes requests by it. It isn't a secret on its own.\n\n$DOCS",
    ) {
        ListRow(
            title = "Version",
            leading = { IconTile(Icons.Filled.Info, Accents.Gray) },
            trailing = { RowValue(BuildConfig.VERSION_NAME) },
        )
        if (checkForUpdates != null) {
            ListDivider(IconRowInset)
            SwitchRow(
                title = "Check for updates",
                subtitle = "Once a day, ask GitHub and say so on Home. Sends no account or phone data; nothing installs " +
                    "without you.",
                checked = checkForUpdates,
                onCheckedChange = onCheckForUpdatesChange,
                leading = { IconTile(Icons.Filled.Refresh, Accents.Gray) },
            )
        }
        ListDivider(IconRowInset)
        ListRow(
            title = "Device ID",
            subtitle = deviceId,
            leading = { IconTile(Icons.Filled.AccountBox, Accents.Gray) },
            trailing = { TertiaryButton("Copy", onClick = { copy("Device ID", deviceId, false) }) },
        )
        ListDivider(IconRowInset)
        ListRow(
            title = "Run setup again",
            leading = { IconTile(Icons.Filled.Settings, Accents.Indigo) },
            onClick = onRunSetupAgain,
        )
    }
}

/** The local server's "Listen on" choices: mode, title, who can reach it. */
private val BIND_CHOICES = listOf(
    Triple(LocalBind.LOOPBACK, "This phone only", "Over USB, with adb forward on your computer."),
    Triple(LocalBind.TAILSCALE, "Tailscale only", "Your own devices on your tailnet."),
    Triple(LocalBind.ALL_INTERFACES, "All networks", "Anyone on the same Wi-Fi, hotspot or VPN. Unencrypted http://."),
)

private const val DOCS = """Documentation, in the PhoneBridge repository:
• docs/CONNECTING.md: the ways agents reach this phone (relay, Tailscale, your own relay)
• docs/ARCHITECTURE.md: how agent, relay and phone fit together
• docs/SECURITY.md: threat model, scopes and approval policies
• docs/TOOLS.md: every tool agents can call
• docs/PROTOCOL.md: MCP endpoint, relay tunnel and token format
• relay/: self-host the relay (Node.js or Docker)"""

/** Free content (fields, notices) inside a [ListSection], padded like a row. */
@Composable
internal fun SectionBlock(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = CardPadding, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}
