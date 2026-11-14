package dev.phonebridge.android.ui.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonebridge.android.ui.components.ConfirmDialog
import dev.phonebridge.android.ui.components.EmptyState
import dev.phonebridge.android.ui.components.Notice
import dev.phonebridge.android.ui.components.NoticeKind
import dev.phonebridge.android.ui.components.ScreenScaffold
import dev.phonebridge.android.ui.components.SectionHeader
import dev.phonebridge.android.ui.components.StatusPill
import dev.phonebridge.android.ui.components.SwitchRow
import dev.phonebridge.android.ui.components.SystemIntents
import dev.phonebridge.android.ui.components.padded
import dev.phonebridge.android.ui.theme.statusColors
import dev.phonebridge.core.plugins.Integration
import dev.phonebridge.core.tools.Risk

/**
 * One app: what kind of support it has, "Block for all agents" (the never-allow list), each action with its
 * risk, approval and parameters, and ways to improve the integration (request one on GitHub, share a
 * plugin draft made from what the app declares).
 */
@Composable
fun AppDetailScreen(vm: AppsViewModel, packageName: String, onBack: () -> Unit) {
    LaunchedEffect(Unit) { vm.refresh() }
    val apps by vm.apps.collectAsStateWithLifecycle()
    val blocked by vm.neverAllowApps.collectAsStateWithLifecycle()
    val app = apps?.firstOrNull { it.packageName == packageName }
    var confirmUnblock by rememberSaveable { mutableStateOf(false) }

    ScreenScaffold(
        title = app?.label ?: "App",
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
    ) { padding ->
        when {
            apps == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            app == null -> Box(Modifier.padding(padding)) {
                EmptyState(title = "App not found", message = "It may have been uninstalled. Go back to see the apps on this phone.")
            }
            else -> {
                val isBlocked = app.packageName in blocked
                AppDetailContent(
                    vm = vm,
                    app = app,
                    blocked = isBlocked,
                    contentPadding = padding,
                    onBlockedChange = { on -> if (on) vm.setBlocked(app.packageName, app.label, true) else confirmUnblock = true },
                )
            }
        }
    }

    if (confirmUnblock && app != null) {
        ConfirmDialog(
            title = "Let agents use ${app.label}?",
            text = "Agents whose own app access allows ${app.label} will be able to see it, open it and run its actions again.",
            confirmLabel = "Allow",
            onConfirm = { vm.setBlocked(app.packageName, app.label, false) },
            onDismiss = { confirmUnblock = false },
        )
    }
}

@Composable
private fun AppDetailContent(
    vm: AppsViewModel,
    app: AppListing,
    blocked: Boolean,
    contentPadding: PaddingValues,
    onBlockedChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val requestUrl = AppsPresentation.integrationRequestUrl(vm.githubRepo, app.label, app.packageName)
    val drafting = vm.draftingFor == app.packageName
    LazyColumn(contentPadding = contentPadding.padded(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "header") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(app.packageName)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        app.packageName,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    BadgeRow(AppsPresentation.badge(app))
                }
            }
        }
        item(key = "note") {
            Notice(
                AppsPresentation.integrationNote(app),
                kind = if (app.integration == Integration.LOCAL) NoticeKind.WARNING else NoticeKind.INFO,
            )
        }
        item(key = "block") {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    SwitchRow(
                        title = "Block for all agents",
                        subtitle = if (blocked) {
                            "No agent can see, open or operate ${app.label}, or run its actions (Settings > Security > Never-allow apps)."
                        } else {
                            "Agents may use ${app.label} if their own app access allows it. Turn on to block it for every agent."
                        },
                        checked = blocked,
                        onCheckedChange = onBlockedChange,
                    )
                }
            }
        }
        item(key = "actions-header") { SectionHeader("What agents can do (${AppsPresentation.actionCount(app.actions.size)})") }
        if (app.actions.isEmpty()) {
            item(key = "no-actions") {
                Text(
                    "No ready-made actions. Agents can still use ${app.label} through the screen tools (read the screen, tap, type).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(app.actions, key = { "action-${it.id}" }) { action -> ActionCard(action, blocked) }
        item(key = "improve-header") { SectionHeader("Make ${app.label} work better") }
        item(key = "improve") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Integrations are community plugins: one small file per app, no code. Ask for one, or start one from what " +
                        "PhoneBridge found in this app and share it to your computer.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (requestUrl != null) {
                    OutlinedButton(onClick = { SystemIntents.launch(context, SystemIntents.webPage(requestUrl)) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Request a better integration")
                    }
                }
                OutlinedButton(
                    onClick = {
                        vm.createDraft(app.packageName) { intent ->
                            try {
                                context.startActivity(intent)
                            } catch (_: RuntimeException) {
                                // No app can receive text, or the screen is gone: nothing to share to.
                            }
                        }
                    },
                    enabled = !drafting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (drafting) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Create plugin draft")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionCard(action: ActionInfo, appBlocked: Boolean) {
    val colors = statusColors()
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(action.title, style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val riskColor = when (action.risk) {
                    Risk.READ -> colors.ok
                    Risk.ACTION -> MaterialTheme.colorScheme.primary
                    Risk.SENSITIVE -> colors.warning
                }
                StatusPill(AppsPresentation.riskLabel(action.risk), riskColor)
                if (AppsPresentation.needsApproval(action)) StatusPill("Always asks", colors.warning)
                if (action.discovered) StatusPill("Discovered", MaterialTheme.colorScheme.onSurfaceVariant)
                if (appBlocked) StatusPill("Blocked", colors.error)
            }
            Text(action.description, style = MaterialTheme.typography.bodyMedium)
            if (action.params.isNotEmpty()) {
                Text("Parameters", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
                action.params.forEach { p ->
                    Text("• " + AppsPresentation.paramText(p), style = MaterialTheme.typography.bodySmall)
                }
            }
            val notes = listOfNotNull(
                AppsPresentation.approvalNote(action),
                AppsPresentation.OPENS_SCREEN.takeIf { !action.completes },
            )
            notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            action.unavailableReason?.let { Notice(it, kind = NoticeKind.WARNING) }
            Text(
                action.toolName,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
