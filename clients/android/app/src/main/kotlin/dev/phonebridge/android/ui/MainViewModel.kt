package dev.phonebridge.android.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.phonebridge.android.graph.BridgeGraph
import dev.phonebridge.android.graph.LocalServerStatus
import dev.phonebridge.android.settings.BridgeSettings
import dev.phonebridge.core.audit.AuditEntry
import dev.phonebridge.core.auth.TokenRecord
import dev.phonebridge.core.confirm.ConfirmationRequest
import dev.phonebridge.core.transport.RelayStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Activity-scoped access to the bridge state shared by Home, Activity and the token screens.
 * Must be created after [GraphWarmup] completed. Mutations run on [Dispatchers.IO] (they persist
 * settings, start/stop the service or cancel notifications).
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = BridgeGraph.get(app)
    private val _messages = Channel<String>(Channel.BUFFERED)

    val settings: StateFlow<BridgeSettings> = graph.settings.state
    val tokens: StateFlow<List<TokenRecord>> = graph.tokenManager.tokens
    val relayStatus: StateFlow<RelayStatus> = graph.relayStatus
    val localServerStatus: StateFlow<LocalServerStatus> = graph.localServerStatus
    val pendingApprovals: StateFlow<List<ConfirmationRequest>> = graph.confirmations.pendingRequests
    val auditEntries: StateFlow<List<AuditEntry>> = graph.audit.entries
    val deviceId: String = graph.identity.deviceId

    /** One-off user-facing messages (snackbar). */
    val messages: Flow<String> = _messages.receiveAsFlow()

    fun setAccessEnabled(enabled: Boolean) = io("Couldn't change access") { graph.setAccessEnabled(enabled) }

    /** Allow/Deny from the in-app list; same effect as the notification buttons. */
    fun resolveApproval(id: String, approved: Boolean) = io("Couldn't answer the request") {
        graph.confirmations.resolve(id, approved)
    }

    /** Re-applies settings, (re)starting the bridge service. */
    fun restartBridge() = io("Couldn't start the bridge service") {
        graph.applySettings()
        _messages.send("Restarting the bridge…")
    }

    fun clearAudit() = io("Couldn't clear the activity log") {
        graph.audit.clear()
        _messages.send("Activity log cleared")
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
