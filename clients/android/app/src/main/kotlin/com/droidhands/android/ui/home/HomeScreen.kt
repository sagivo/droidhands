package com.droidhands.android.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidhands.android.ui.MainViewModel
import com.droidhands.android.ui.activity.ActivityQuery
import com.droidhands.android.ui.activity.AuditPresentation
import com.droidhands.android.ui.activity.AuditRow
import com.droidhands.android.ui.agents.AgentPresentation
import com.droidhands.android.ui.components.Accents
import com.droidhands.android.ui.components.IconTile
import com.droidhands.android.ui.components.ListDivider
import com.droidhands.android.ui.components.ListRow
import com.droidhands.android.ui.components.ListSection
import com.droidhands.android.ui.components.MenuAction
import com.droidhands.android.ui.components.OverflowMenu
import com.droidhands.android.ui.components.ScreenScaffold
import com.droidhands.android.ui.components.SecureWindowEffect
import com.droidhands.android.ui.components.SectionHeader
import com.droidhands.android.ui.components.SystemIntents
import com.droidhands.android.ui.components.TertiaryButton
import com.droidhands.android.ui.components.TouchShieldEffect
import com.droidhands.android.ui.components.padded
import com.droidhands.android.ui.components.rememberCopier
import com.droidhands.android.ui.components.rememberNow
import com.droidhands.android.ui.components.rememberTimestampFormatter
import com.droidhands.android.ui.connect.PendingPairingCard
import com.droidhands.android.ui.setup.SetupUiState
import com.droidhands.android.ui.theme.statusColors
import java.time.ZoneId

/** Navigation callbacks of the Home tab. */
class HomeActions(
    val onConnectAgent: () -> Unit,
    val onOpenPermissions: () -> Unit,
    val onOpenActivity: () -> Unit,
    val onOpenAgents: () -> Unit,
    val onRevokeAll: () -> Unit,
)

/**
 * Home: things waiting for the user first (connection requests and approvals time out), then the
 * access switch and status, a setup hint while essentials are missing, "Connect an AI agent",
 * today's summary and the latest activity.
 */
@Composable
fun HomeScreen(vm: MainViewModel, setup: SetupUiState, actions: HomeActions) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val relay by vm.relayStatus.collectAsStateWithLifecycle()
    val local by vm.localServerStatus.collectAsStateWithLifecycle()
    val tokens by vm.tokens.collectAsStateWithLifecycle()
    val pending by vm.pendingApprovals.collectAsStateWithLifecycle()
    val allPairings by vm.pendingPairings.collectAsStateWithLifecycle()
    val entries by vm.auditEntries.collectAsStateWithLifecycle()
    val names by vm.actionNames.collectAsStateWithLifecycle()
    val usage by vm.usage.collectAsStateWithLifecycle()
    val update by vm.availableUpdate.collectAsStateWithLifecycle()
    val now by rememberNow(intervalMs = if (pending.isNotEmpty() || allPairings.isNotEmpty()) 1_000 else 30_000)
    val formatTime = rememberTimestampFormatter()
    val context = LocalContext.current
    val copy = rememberCopier()

    // Approvals show exactly what an agent is about to do: keep them out of screenshots and Recents, and
    // drop taps through other apps' overlays (tapjacking) while Allow is on screen.
    SecureWindowEffect(enabled = pending.isNotEmpty())
    TouchShieldEffect(enabled = pending.isNotEmpty())

    val pairings = remember(allPairings, now) { allPairings.filter { it.expiresAt > now } }
    val activeAgents = remember(tokens, now) { AgentPresentation.activeCount(tokens, now) }
    val today = remember(entries, usage, now / MINUTE_MS) { TodaySummary.compute(entries, usage, now, ZoneId.systemDefault()) }
    val recent = remember(entries) { entries.take(RECENT_ROWS) }
    // Readable app action names ("Spotify – Play music") need the app list; load it only when one is shown.
    val showsAppAction = remember(recent) { recent.any { AuditPresentation.displayTitle(it).isAppAction } }
    LaunchedEffect(showsAppAction) { if (showsAppAction) vm.loadAppActions() }
    val deviceIdShort = remember(vm.deviceId) { StatusPresentation.shortDeviceId(vm.deviceId) }
    val colors = statusColors()

    ScreenScaffold(
        title = "DroidHands",
        large = true,
        actions = {
            OverflowMenu(
                listOf(MenuAction("Revoke all agents", enabled = activeAgents > 0, destructive = true, onClick = actions.onRevokeAll)),
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = padding.padded(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            if (pairings.isNotEmpty()) {
                item(key = "pairing-header") { SectionHeader("Wants to connect (${pairings.size})") }
                items(pairings, key = { "pairing-${it.id}" }) { request ->
                    PendingPairingCard(
                        request = request,
                        now = now,
                        onReview = { vm.openPairing(context, request.id) },
                        onDeny = { vm.denyPairing(request.id) },
                    )
                }
            }
            if (pending.isNotEmpty()) {
                item(key = "pending-header") { SectionHeader("Waiting for your approval (${pending.size})") }
                items(pending, key = { "pending-${it.id}" }) { request ->
                    PendingApprovalCard(
                        request = request,
                        expiresAt = vm.approvalExpiresAt(request.id),
                        now = now,
                        onAllow = { vm.resolveApproval(request.id, approved = true) },
                        onDeny = { vm.resolveApproval(request.id, approved = false) },
                    )
                }
            }
            item(key = "status") { AccessHeroCard(settings.accessEnabled, onAccessChange = vm::setAccessEnabled) }
            if (setup.loaded && !setup.progress.essentialComplete) {
                item(key = "setup-hint") { SetupHintCard(setup.progress, onOpenSetup = actions.onOpenPermissions) }
            }
            val newVersion = update
            val downloadUrl = vm.updateDownloadUrl
            if (newVersion != null && downloadUrl != null) {
                item(key = "update") {
                    UpdateCard(
                        version = newVersion,
                        onDownload = {
                            // No browser: copy the link instead, so it can be opened elsewhere.
                            if (!SystemIntents.launch(context, SystemIntents.webPage(downloadUrl))) copy("Download link", downloadUrl, false)
                        },
                        onDismiss = { vm.dismissUpdate(newVersion) },
                    )
                }
            }
            item(key = "connect") { ConnectAgentCard(hasActiveAgents = activeAgents > 0, onConnect = actions.onConnectAgent) }
            if (activeAgents > 0 || !today.isEmpty) {
                item(key = "today") { TodayCard(today, onOpenAgents = actions.onOpenAgents) }
            }
            item(key = "recent") {
                RecentActivity(
                    empty = recent.isEmpty(),
                    onSeeAll = {
                        vm.activityQuery = ActivityQuery()
                        actions.onOpenActivity()
                    },
                ) {
                    recent.forEachIndexed { index, entry ->
                        if (index > 0) ListDivider()
                        AuditRow(
                            entry = entry,
                            time = formatTime(entry.timestamp, now),
                            colors = colors,
                            onClick = {
                                vm.activityQuery = ActivityQuery()
                                actions.onOpenActivity()
                            },
                            names = names,
                        )
                    }
                }
            }
            item(key = "connection") {
                ConnectionSection(
                    relay = StatusPresentation.relay(relay, settings.relayEnabled),
                    local = StatusPresentation.local(local, settings.localServerEnabled, settings.localServerBind),
                    deviceIdShort = deviceIdShort,
                    showRestart = StatusPresentation.showRestart(relay, settings.relayEnabled, local, settings.localServerEnabled),
                    onRestart = vm::restartBridge,
                    onCopyUrl = { copy("Connection URL", it, false) },
                )
            }
        }
    }
}

@Composable
private fun RecentActivity(empty: Boolean, onSeeAll: () -> Unit, rows: @Composable () -> Unit) {
    if (empty) {
        ListSection(title = "Recent activity") {
            ListRow(
                title = "Nothing yet",
                subtitle = "Every request from an agent will show up here.",
                leading = { IconTile(Icons.AutoMirrored.Filled.List, Accents.Gray) },
            )
        }
    } else {
        ListSection(title = "Recent activity", action = { TertiaryButton("See all", onClick = onSeeAll) }) { rows() }
    }
}

private const val RECENT_ROWS = 5
private const val MINUTE_MS = 60_000L
