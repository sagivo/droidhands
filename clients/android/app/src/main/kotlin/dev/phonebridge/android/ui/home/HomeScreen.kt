package dev.phonebridge.android.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonebridge.android.ui.MainViewModel
import dev.phonebridge.android.ui.activity.ActivityQuery
import dev.phonebridge.android.ui.activity.AuditRow
import dev.phonebridge.android.ui.agents.AgentPresentation
import dev.phonebridge.android.ui.components.MenuAction
import dev.phonebridge.android.ui.components.OverflowMenu
import dev.phonebridge.android.ui.components.ScreenScaffold
import dev.phonebridge.android.ui.components.SecureWindowEffect
import dev.phonebridge.android.ui.components.SectionHeader
import dev.phonebridge.android.ui.components.padded
import dev.phonebridge.android.ui.components.rememberCopier
import dev.phonebridge.android.ui.components.rememberNow
import dev.phonebridge.android.ui.components.rememberTimestampFormatter
import dev.phonebridge.android.ui.connect.PendingPairingCard
import dev.phonebridge.android.ui.setup.SetupUiState
import dev.phonebridge.android.ui.theme.statusColors
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
    val usage by vm.usage.collectAsStateWithLifecycle()
    val now by rememberNow(intervalMs = if (pending.isNotEmpty() || allPairings.isNotEmpty()) 1_000 else 30_000)
    val formatTime = rememberTimestampFormatter()
    val context = LocalContext.current
    val copy = rememberCopier()

    // Approvals show exactly what an agent is about to do: keep them out of screenshots and Recents.
    SecureWindowEffect(enabled = pending.isNotEmpty())

    val pairings = remember(allPairings, now) { allPairings.filter { it.expiresAt > now } }
    val activeAgents = remember(tokens, now) { AgentPresentation.activeCount(tokens, now) }
    val today = remember(entries, usage, now / MINUTE_MS) { TodaySummary.compute(entries, usage, now, ZoneId.systemDefault()) }
    val recent = remember(entries) { entries.take(RECENT_ROWS) }
    val deviceIdShort = remember(vm.deviceId) { StatusPresentation.shortDeviceId(vm.deviceId) }
    val colors = statusColors()

    ScreenScaffold(
        title = "PhoneBridge",
        actions = {
            OverflowMenu(
                listOf(MenuAction("Revoke all agents", enabled = activeAgents > 0, destructive = true, onClick = actions.onRevokeAll)),
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = padding.padded(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
            item(key = "status") {
                AccessStatusCard(
                    accessEnabled = settings.accessEnabled,
                    relay = StatusPresentation.relay(relay, settings.relayEnabled),
                    local = StatusPresentation.local(local, settings.localServerEnabled),
                    deviceIdShort = deviceIdShort,
                    showRestart = StatusPresentation.showRestart(relay, settings.relayEnabled, local, settings.localServerEnabled),
                    onAccessChange = vm::setAccessEnabled,
                    onRestart = vm::restartBridge,
                    onCopyUrl = { copy("Connection URL", it, false) },
                )
            }
            if (setup.loaded && !setup.progress.essentialComplete) {
                item(key = "setup-hint") { SetupHintCard(setup.progress, onOpenSetup = actions.onOpenPermissions) }
            }
            item(key = "connect") { ConnectAgentCard(hasActiveAgents = activeAgents > 0, onConnect = actions.onConnectAgent) }
            item(key = "today") { TodayCard(today, onOpenAgents = actions.onOpenAgents) }
            item(key = "recent") {
                RecentActivity(
                    empty = recent.isEmpty(),
                    onSeeAll = {
                        vm.activityQuery = ActivityQuery()
                        actions.onOpenActivity()
                    },
                ) {
                    recent.forEachIndexed { index, entry ->
                        if (index > 0) HorizontalDivider()
                        AuditRow(
                            entry = entry,
                            time = formatTime(entry.timestamp, now),
                            colors = colors,
                            onClick = {
                                vm.activityQuery = ActivityQuery()
                                actions.onOpenActivity()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentActivity(empty: Boolean, onSeeAll: () -> Unit, rows: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionHeader("Recent activity", Modifier.weight(1f))
            if (!empty) TextButton(onClick = onSeeAll) { Text("See all") }
        }
        if (empty) {
            Text(
                "Nothing yet. Every request from an agent will show up here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            OutlinedCard(Modifier.fillMaxWidth()) { Column { rows() } }
        }
    }
}

private const val RECENT_ROWS = 5
private const val MINUTE_MS = 60_000L
