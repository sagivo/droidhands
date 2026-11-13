package dev.phonebridge.android.settings

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

data class BridgeSettings(
    /** Connect outbound to a relay (recommended for remote agents). */
    val relayEnabled: Boolean = false,
    /** e.g. https://relay.example.com (http:// allowed for dev/LAN/Tailscale). */
    val relayUrl: String = "",
    /** Optional, must match the relay's RELAY_ACCESS_KEY. */
    val relayAccessKey: String = "",
    /** Serve MCP directly from the phone. */
    val localServerEnabled: Boolean = false,
    val localServerPort: Int = 8765,
    /** false = 127.0.0.1 only (use `adb forward`); true = all interfaces (LAN/Tailscale). */
    val localServerBindAll: Boolean = false,
    /** Global kill switch. */
    val accessEnabled: Boolean = true,
    val startOnBoot: Boolean = true,
)

/**
 * SharedPreferences-backed settings with a reactive snapshot.
 *
 * STUB — implemented by the services agent.
 */
class SettingsRepository(context: Context) {
    val state: StateFlow<BridgeSettings> get() = TODO()

    fun update(transform: (BridgeSettings) -> BridgeSettings): Unit = TODO()
}
