package dev.phonebridge.android.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonebridge.android.ui.Destination
import dev.phonebridge.android.ui.MainViewModel
import dev.phonebridge.android.ui.components.ScreenScaffold
import dev.phonebridge.android.ui.components.SectionHeader
import dev.phonebridge.android.ui.components.padded
import dev.phonebridge.android.ui.components.rememberCopier
import dev.phonebridge.android.ui.components.rememberNow
import dev.phonebridge.android.ui.setup.SetupUiState
import dev.phonebridge.android.ui.tokens.TokenPresentation
import dev.phonebridge.android.ui.tokens.TokenState

/**
 * Home: pending approvals first (they time out), then the access/status card, a setup hint while
 * essentials are missing, and the "Get started" steps until a transport and a token exist.
 */
@Composable
fun HomeScreen(vm: MainViewModel, setup: SetupUiState, onNavigate: (Destination) -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val relay by vm.relayStatus.collectAsStateWithLifecycle()
    val local by vm.localServerStatus.collectAsStateWithLifecycle()
    val tokens by vm.tokens.collectAsStateWithLifecycle()
    val pending by vm.pendingApprovals.collectAsStateWithLifecycle()
    val now by rememberNow()
    val copy = rememberCopier()

    val hasActiveToken = tokens.any { TokenPresentation.state(it, now) == TokenState.ACTIVE }
    val transportConfigured = StatusPresentation.transportConfigured(settings)
    val deviceIdShort = remember(vm.deviceId) { StatusPresentation.shortDeviceId(vm.deviceId) }

    ScreenScaffold(title = "PhoneBridge") { padding ->
        LazyColumn(contentPadding = padding.padded(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (pending.isNotEmpty()) {
                item(key = "pending-header") { SectionHeader("Waiting for your approval (${pending.size})") }
                items(pending, key = { "pending-${it.id}" }) { request ->
                    PendingApprovalCard(
                        request = request,
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
                    onCopyUrl = { copy("MCP URL", it, false) },
                )
            }
            if (setup.loaded && !setup.progress.essentialComplete) {
                item(key = "setup-hint") { SetupHintCard(setup.progress, onOpenSetup = { onNavigate(Destination.SETUP) }) }
            }
            if (StatusPresentation.showGettingStarted(transportConfigured, hasActiveToken)) {
                item(key = "get-started") {
                    GetStartedCard(
                        steps = StatusPresentation.gettingStarted(
                            setupComplete = setup.loaded && setup.progress.essentialComplete,
                            transportConfigured = transportConfigured,
                            hasActiveToken = hasActiveToken,
                        ),
                        actions = listOf(
                            "Open Setup" to { onNavigate(Destination.SETUP) },
                            "Open Settings" to { onNavigate(Destination.SETTINGS) },
                            "Create a token" to { onNavigate(Destination.TOKENS) },
                        ),
                    )
                }
            }
        }
    }
}
