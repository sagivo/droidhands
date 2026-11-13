package dev.phonebridge.android.graph

import android.app.Application
import android.content.Context
import dev.phonebridge.android.confirm.NotificationConfirmationProvider
import dev.phonebridge.android.settings.SettingsRepository
import dev.phonebridge.core.audit.AuditLog
import dev.phonebridge.core.auth.DeviceIdentity
import dev.phonebridge.core.auth.TokenManager
import dev.phonebridge.core.mcp.McpServer
import dev.phonebridge.core.tools.ToolRegistry
import dev.phonebridge.core.transport.RelayStatus
import kotlinx.coroutines.flow.StateFlow

sealed interface LocalServerStatus {
    data object Stopped : LocalServerStatus

    /** [urls] e.g. ["http://127.0.0.1:8765/mcp", "http://192.168.1.20:8765/mcp"]. */
    data class Running(val urls: List<String>) : LocalServerStatus

    data class Error(val message: String) : LocalServerStatus
}

/**
 * App-wide manual DI container. Created once in [dev.phonebridge.android.PhoneBridgeApp.onCreate].
 *
 * STUB — implemented by the services agent. Public API below is the contract used by the UI.
 */
class BridgeGraph private constructor(val app: Application) {
    val settings: SettingsRepository get() = TODO()
    val identity: DeviceIdentity get() = TODO()
    val tokenManager: TokenManager get() = TODO()
    val audit: AuditLog get() = TODO()
    val registry: ToolRegistry get() = TODO()
    val confirmations: NotificationConfirmationProvider get() = TODO()
    val mcpServer: McpServer get() = TODO()

    /** Live relay connection status (Stopped when relay is disabled or service not running). */
    val relayStatus: StateFlow<RelayStatus> get() = TODO()

    val localServerStatus: StateFlow<LocalServerStatus> get() = TODO()

    /** Starts/stops/restarts BridgeService so that it matches current settings. Call after settings changes. */
    fun applySettings(): Unit = TODO()

    /** Global kill switch (persisted in settings, reflected in the service notification). */
    fun setAccessEnabled(enabled: Boolean): Unit = TODO()

    companion object {
        @Volatile
        private var _instance: BridgeGraph? = null

        val instance: BridgeGraph get() = _instance ?: error("BridgeGraph not initialized")

        fun init(app: Application) {
            if (_instance == null) synchronized(this) { if (_instance == null) _instance = BridgeGraph(app) }
        }

        fun get(context: Context): BridgeGraph {
            init(context.applicationContext as Application)
            return instance
        }
    }
}
