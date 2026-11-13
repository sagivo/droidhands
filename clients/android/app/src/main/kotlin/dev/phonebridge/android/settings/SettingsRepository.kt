package dev.phonebridge.android.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
) {
    /** Never print the access key (e.g. in crash logs or debug output). */
    override fun toString(): String =
        "BridgeSettings(relayEnabled=$relayEnabled, relayUrl=$relayUrl, relayAccessKey=${if (relayAccessKey.isEmpty()) "" else "***"}, " +
            "localServerEnabled=$localServerEnabled, localServerPort=$localServerPort, localServerBindAll=$localServerBindAll, " +
            "accessEnabled=$accessEnabled, startOnBoot=$startOnBoot)"
}

/**
 * SharedPreferences-backed settings (`bridge_settings`, app-private) with a reactive snapshot.
 *
 * [update] is atomic: concurrent callers are serialized, each transform sees the result of the
 * previous one, the result is sanitized ([SettingsCodec.sanitize], e.g. the port is clamped to
 * 1024..65535) and written with `apply()` (asynchronous disk write, the in-memory state is updated
 * immediately). Reading [state] never touches the disk.
 */
class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()
    private val _state = MutableStateFlow(SettingsCodec.decode(prefs.all))

    val state: StateFlow<BridgeSettings> = _state.asStateFlow()

    fun update(transform: (BridgeSettings) -> BridgeSettings) {
        synchronized(lock) {
            val current = _state.value
            val next = SettingsCodec.sanitize(transform(current))
            if (next == current) return
            prefs.edit().apply {
                SettingsCodec.encode(next).forEach { (key, value) ->
                    when (value) {
                        is Boolean -> putBoolean(key, value)
                        is Int -> putInt(key, value)
                        is String -> putString(key, value)
                        else -> error("Unsupported settings value type for '$key'")
                    }
                }
            }.apply()
            _state.value = next
        }
    }

    private companion object {
        const val PREFS_NAME = "bridge_settings"
    }
}
