package dev.phonebridge.android.ui

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.annotation.MainThread
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.phonebridge.android.graph.BridgeGraph
import dev.phonebridge.android.graph.LocalServerStatus
import dev.phonebridge.android.settings.BridgeSettings
import dev.phonebridge.android.ui.activity.ActivityQuery
import dev.phonebridge.android.ui.agents.AgentPresentation
import dev.phonebridge.android.ui.deeplink.ConfigureRequest
import dev.phonebridge.android.ui.home.StatusPresentation
import dev.phonebridge.android.ui.onboarding.OnboardingFlow
import dev.phonebridge.android.ui.settings.RelayUrls
import dev.phonebridge.core.audit.AuditEntry
import dev.phonebridge.core.audit.TokenUsage
import dev.phonebridge.core.auth.TokenRecord
import dev.phonebridge.core.confirm.ConfirmationRequest
import dev.phonebridge.core.transport.PairingRequest
import dev.phonebridge.core.transport.RelayStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Activity-scoped access to the bridge state shared by Home, Activity, onboarding and the connect
 * page. Must be created after [GraphWarmup] completed. Mutations run on [Dispatchers.IO] (they
 * persist settings or tokens, start/stop the service or cancel notifications).
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = BridgeGraph.get(app)
    private val prefs = UiPrefs(app)
    private val _messages = Channel<String>(Channel.BUFFERED)
    private val _showOnboarding = MutableStateFlow<Boolean?>(null)

    val settings: StateFlow<BridgeSettings> = graph.settings.state
    val tokens: StateFlow<List<TokenRecord>> = graph.tokenManager.tokens
    val relayStatus: StateFlow<RelayStatus> = graph.relayStatus
    val localServerStatus: StateFlow<LocalServerStatus> = graph.localServerStatus
    val pendingApprovals: StateFlow<List<ConfirmationRequest>> = graph.confirmations.pendingRequests
    val pendingPairings: StateFlow<List<PairingRequest>> = GraphAdapters.pendingPairings(graph)
    val auditEntries: StateFlow<List<AuditEntry>> = graph.audit.entries
    val usage: StateFlow<Map<String, TokenUsage>> = GraphAdapters.usage(graph)
    val deviceId: String = graph.identity.deviceId

    /** One-off user-facing messages (snackbar). */
    val messages: Flow<String> = _messages.receiveAsFlow()

    /** Whether the onboarding wizard covers the app; null until the saved flag is read. */
    val showOnboarding: StateFlow<Boolean?> = _showOnboarding.asStateFlow()

    /** Activity tab filters; the agent detail page sets them for "See all activity". Main thread. */
    var activityQuery by mutableStateOf(ActivityQuery())

    init {
        viewModelScope.launch {
            val completed = try {
                prefs.onboardingCompleted()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Reading the onboarding flag failed: ${e.javaClass.name}")
                true
            }
            _showOnboarding.value = OnboardingFlow.shouldShow(
                completed = completed,
                hasTokens = tokens.value.isNotEmpty(),
                transportConfigured = StatusPresentation.transportConfigured(settings.value),
            )
        }
    }

    /** Finishes or skips the wizard for good. */
    @MainThread
    fun finishOnboarding() {
        _showOnboarding.value = false
        viewModelScope.launch { runCatching { prefs.setOnboardingCompleted(true) } }
    }

    /** Settings > About > "Run setup again". */
    @MainThread
    fun reopenOnboarding() {
        _showOnboarding.value = true
    }

    fun setAccessEnabled(enabled: Boolean) = io("Couldn't change access") { graph.setAccessEnabled(enabled) }

    /** Allow/Deny from the in-app list; same effect as the notification buttons. */
    fun resolveApproval(id: String, approved: Boolean) = io("Couldn't answer the request") {
        graph.confirmations.resolve(id, approved)
    }

    /** Epoch millis when an approval stops waiting, for the countdown on its card. */
    fun approvalExpiresAt(id: String): Long? = graph.confirmations.expiresAt(id)

    /** Opens the full Allow/Deny pairing screen (it requires an unlocked phone). */
    @MainThread
    fun openPairing(context: Context, id: String) {
        try {
            GraphAdapters.openPairingApproval(graph, context, id)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Opening the pairing prompt failed: ${e.javaClass.name}")
            _messages.trySend("Couldn't open the connection request. Use the PhoneBridge notification instead.")
        }
    }

    fun denyPairing(id: String) = io("Couldn't deny the connection request") { GraphAdapters.denyPairing(graph, id) }

    /** Re-applies settings, (re)starting the bridge service. */
    fun restartBridge() = io("Couldn't start the bridge service") {
        graph.applySettings()
        _messages.send("Restarting the bridge…")
    }

    fun clearAudit() = io("Couldn't clear the activity log") {
        graph.audit.clear()
        _messages.send("Activity log cleared")
    }

    /** Locks every agent out at once (pairing an agent again creates a new one). */
    fun revokeAll() = io("Couldn't revoke the agents") {
        val count = graph.tokenManager.revokeAll()
        _messages.send(AgentPresentation.revokedAllText(count))
    }

    /** Onboarding / connect page: saves a relay URL (and optional key), turns the relay on and connects. */
    fun connectRelay(url: String, accessKey: String) = io("Couldn't save the relay") {
        val normalized = RelayUrls.normalize(url)
        graph.settings.update { it.copy(relayEnabled = true, relayUrl = normalized, relayAccessKey = accessKey.trim()) }
        graph.applySettings()
        _messages.send("Connecting to the relay…")
    }

    /** Onboarding "Local only": serves MCP on this phone (127.0.0.1, reached with adb forward). */
    fun enableLocalServer() = io("Couldn't start the local server") {
        graph.settings.update { it.copy(localServerEnabled = true) }
        graph.applySettings()
        _messages.send("Local server starting on port ${settings.value.localServerPort}…")
    }

    /** Accepts a confirmed `phonebridge://configure` link. */
    fun applyConfigureLink(request: ConfigureRequest) = io("Couldn't apply the setup link") {
        GraphAdapters.applyConfigureLink(graph, request)
        _messages.send("Connecting to ${request.host}…")
    }

    private fun io(failure: String, block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "$failure: ${e.javaClass.name}")
                _messages.send("$failure: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private companion object {
        const val TAG = "PhoneBridgeUi"
    }
}
