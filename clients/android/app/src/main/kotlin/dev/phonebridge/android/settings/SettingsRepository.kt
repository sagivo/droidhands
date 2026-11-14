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
    /**
     * Where the local server listens: 127.0.0.1 only (`adb forward`), only the phone's Tailscale
     * address, or all interfaces (LAN). Stored before this existed as a bind-all switch (migrated).
     */
    val localServerBind: LocalBind = LocalBind.LOOPBACK,
    /** Global kill switch. */
    val accessEnabled: Boolean = true,
    val startOnBoot: Boolean = true,
    /**
     * Global never-allow list: packages no agent may see or operate, whatever its own app access
     * (docs/SECURITY.md "Advanced controls"). PhoneBridge itself is added at runtime, not stored here.
     */
    val neverAllowApps: Set<String> = emptySet(),
    /** Mask one-time / 2FA codes in SMS and notification text returned to agents. */
    val redactOneTimeCodes: Boolean = true,
    /**
     * True once the first-run security defaults (installed banking, payment, password-manager and
     * authenticator apps added to [neverAllowApps]) were applied, so they are applied only once and
     * the user's later choices are kept.
     */
    val securityDefaultsApplied: Boolean = false,
    /**
     * Settings > Developer > Local plugins: use app plugins imported on the phone (unreviewed; every action
     * of theirs asks for approval). Off by default (docs/PLUGINS.md §10).
     */
    val localPluginsEnabled: Boolean = false,
    /**
     * True once the first-run default relay step ran ([dev.phonebridge.android.graph.DefaultRelay]):
     * it either connected to the build's default relay or found a connection already set up. Stored
     * settings from before this field existed count as decided, so an app update never adds a relay.
     */
    val defaultRelayDecided: Boolean = false,
    /**
     * The relay in [relayUrl] is the build's default relay, not one the user entered or opened a
     * setup link for. Cleared as soon as the user picks a relay themselves.
     */
    val relayFromDefault: Boolean = false,
    /** Ask GitHub once a day whether a newer release exists (only in builds that name a repository). */
    val checkForUpdates: Boolean = true,
) {
    /** Never print the access key (e.g. in crash logs or debug output). */
    override fun toString(): String =
        "BridgeSettings(relayEnabled=$relayEnabled, relayUrl=$relayUrl, relayAccessKey=${if (relayAccessKey.isEmpty()) "" else "***"}, " +
            "localServerEnabled=$localServerEnabled, localServerPort=$localServerPort, localServerBind=$localServerBind, " +
            "accessEnabled=$accessEnabled, startOnBoot=$startOnBoot, neverAllowApps=${neverAllowApps.size} apps, " +
            "redactOneTimeCodes=$redactOneTimeCodes, securityDefaultsApplied=$securityDefaultsApplied, " +
            "localPluginsEnabled=$localPluginsEnabled, defaultRelayDecided=$defaultRelayDecided, relayFromDefault=$relayFromDefault, checkForUpdates=$checkForUpdates)"
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
                        // Always a fresh copy: SharedPreferences must not keep a reference to a set we own.
                        is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toHashSet())
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
