package com.droidhands.android.ui.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import com.droidhands.android.ui.components.Accents
import com.droidhands.android.ui.components.AppCard
import com.droidhands.android.ui.components.CardPadding
import com.droidhands.android.ui.components.ConfirmDialog
import com.droidhands.android.ui.components.EmptyState
import com.droidhands.android.ui.components.IconRowInset
import com.droidhands.android.ui.components.IconTile
import com.droidhands.android.ui.components.ListDivider
import com.droidhands.android.ui.components.ListRow
import com.droidhands.android.ui.components.ListSection
import com.droidhands.android.ui.components.Notice
import com.droidhands.android.ui.components.NoticeKind
import com.droidhands.android.ui.components.ScreenScaffold
import com.droidhands.android.ui.components.StatusPill
import com.droidhands.android.ui.components.SwitchRow
import com.droidhands.android.ui.components.SystemIntents
import com.droidhands.android.ui.components.padded
import com.droidhands.android.ui.theme.statusColors
import com.droidhands.core.plugins.Integration
import com.droidhands.core.tools.Risk

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

    ScreenScaffold(title = app?.label ?: "App", onBack = onBack) { padding ->
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
    LazyColumn(contentPadding = contentPadding.padded(), verticalArrangement = Arrangement.spacedBy(SECTION_SPACING)) {
        item(key = "header") { AppHeroCard(app) }
        item(key = "note") {
            Notice(
                AppsPresentation.integrationNote(app),
                kind = if (app.integration == Integration.LOCAL) NoticeKind.WARNING else NoticeKind.INFO,
            )
        }
        item(key = "block") {
            ListSection(
                title = "Access",
                footer = if (blocked) {
                    "No agent can see, open or operate ${app.label}, or run its actions (Settings > Security > Never-allow apps)."
                } else {
                    "Agents may use ${app.label} if their own app access allows it. Turn on to block it for every agent."
                },
            ) {
                SwitchRow(
                    title = "Block for all agents",
                    checked = blocked,
                    onCheckedChange = onBlockedChange,
                    leading = { IconTile(Icons.Filled.Lock, if (blocked) Accents.Red else Accents.Gray) },
                )
            }
        }
        item(key = "actions") {
            ListSection(title = "What agents can do (${AppsPresentation.actionCount(app.actions.size)})") {
                if (app.actions.isEmpty()) {
                    ListRow(
                        title = "No ready-made actions",
                        subtitle = AppsPresentation.noActionsLine(app.label),
                        leading = { IconTile(Icons.Filled.Info, Accents.Gray) },
                    )
                }
                app.actions.forEachIndexed { index, action ->
                    if (index > 0) ListDivider()
                    ActionRow(action, blocked)
                }
            }
        }
        item(key = "improve") {
            ListSection(
                title = "Make ${app.label} work better",
                footer = "Integrations are community plugins: one small file per app, no code. Ask for one, or start one from " +
                    "what DroidHands found in this app and share it to your computer.",
            ) {
                if (requestUrl != null) {
                    ListRow(
                        title = "Request a better integration",
                        subtitle = "Opens a prefilled request on GitHub",
                        leading = { IconTile(Icons.AutoMirrored.Filled.Send, Accents.Blue) },
                        onClick = { SystemIntents.launch(context, SystemIntents.webPage(requestUrl)) },
                    )
                    ListDivider(IconRowInset)
                }
                ListRow(
                    title = "Create plugin draft",
                    subtitle = "A plugin.yaml made from what ${app.label} declares, to share",
                    leading = { IconTile(Icons.Filled.Share, Accents.Teal) },
                    trailing = if (drafting) {
                        { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
                    } else {
                        null
                    },
                    showChevron = !drafting,
                    enabled = !drafting,
                    onClick = {
                        vm.createDraft(app.packageName) { intent ->
                            try {
                                context.startActivity(intent)
                            } catch (_: RuntimeException) {
                                // No app can receive text, or the screen is gone: nothing to share to.
                            }
                        }
                    },
                )
            }
        }
    }
}

/** The page's hero: the app's icon, name, package and kind of support. */
@Composable
private fun AppHeroCard(app: AppListing) {
    val badge = AppsPresentation.badge(app)
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(app.packageName, label = app.label, size = 56.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(app.label, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    app.packageName,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusPill(kindLabel(badge.kind), badgeColor(badge.kind))
                    badge.status?.let { StatusPill(it, statusColors().warning) }
                }
            }
        }
    }
}

private fun kindLabel(kind: BadgeKind): String = when (kind) {
    BadgeKind.INTEGRATION -> "Integration"
    BadgeKind.LOCAL -> "Local plugin"
    BadgeKind.BASIC -> "Basic"
}

/**
 * One action: title and risk pill, what it does, how it is approved, its parameters and the tool name
 * rules and the log use. Why it can't run right now, if so, as a warning.
 */
@Composable
private fun ActionRow(action: ActionInfo, appBlocked: Boolean) {
    val colors = statusColors()
    val riskColor = when (action.risk) {
        Risk.READ -> colors.ok
        Risk.ACTION -> MaterialTheme.colorScheme.primary
        Risk.SENSITIVE -> colors.warning
    }
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = CardPadding, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(action.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            StatusPill(AppsPresentation.riskLabel(action.risk), riskColor)
        }
        Text(action.description, style = MaterialTheme.typography.bodyMedium, color = secondary)
        val needsApproval = AppsPresentation.needsApproval(action)
        val flags = listOfNotNull(
            "Blocked for all agents".takeIf { appBlocked },
            "Always asks".takeIf { needsApproval },
            "Discovered".takeIf { action.discovered },
        )
        if (flags.isNotEmpty()) {
            Text(
                flags.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    appBlocked -> colors.error
                    needsApproval -> colors.warning
                    else -> secondary
                },
            )
        }
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
        notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = secondary) }
        action.unavailableReason?.let { Notice(it, kind = NoticeKind.WARNING, modifier = Modifier.padding(top = 4.dp)) }
        Text(
            action.toolName,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelSmall,
            color = secondary,
        )
    }
}
