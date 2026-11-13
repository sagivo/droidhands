package dev.phonebridge.android.service

import dev.phonebridge.android.graph.RelayUrls
import dev.phonebridge.android.settings.BridgeSettings
import dev.phonebridge.android.settings.SettingsCodec
import dev.phonebridge.core.transport.RelayStatus

/** What a running relay client was configured with; a change in any field requires a reconnect. */
data class RelayConfig(
    /** Normalized base URL (no trailing slash). */
    val url: String,
    /** Null when the relay is open (no RELAY_ACCESS_KEY). */
    val accessKey: String?,
) {
    override fun toString(): String = "RelayConfig(url=$url, accessKey=${if (accessKey == null) "none" else "***"})"
}

/** What a running local server was bound with; a change in any field requires a rebind. */
data class LocalConfig(val port: Int, val bindAll: Boolean) {
    val bindAddress: String get() = if (bindAll) ALL_INTERFACES else LOOPBACK

    companion object {
        const val LOOPBACK = "127.0.0.1"
        const val ALL_INTERFACES = "0.0.0.0"
    }
}

/** Pure mapping from [BridgeSettings] to the transports that should be running. */
object TransportConfig {
    /** The relay to run, or null when disabled or the URL is invalid. */
    fun relay(settings: BridgeSettings): RelayConfig? {
        if (!settings.relayEnabled || !RelayUrls.isValid(settings.relayUrl)) return null
        return RelayConfig(RelayUrls.normalize(settings.relayUrl), settings.relayAccessKey.trim().ifEmpty { null })
    }

    /** The local server to run, or null when disabled. */
    fun local(settings: BridgeSettings): LocalConfig? =
        if (settings.localServerEnabled) LocalConfig(SettingsCodec.clampPort(settings.localServerPort), settings.localServerBindAll) else null

    /** True if at least one transport should run, i.e. BridgeService should be in the foreground. */
    fun shouldRun(settings: BridgeSettings): Boolean = relay(settings) != null || local(settings) != null

    /**
     * Relay status while no relay client runs: an actionable, fatal error when the relay is enabled
     * with an unusable URL (so the UI and notification explain why nothing connects), else Stopped.
     */
    fun idleRelayStatus(settings: BridgeSettings): RelayStatus {
        if (!settings.relayEnabled) return RelayStatus.Stopped
        val problem = RelayUrls.validate(settings.relayUrl) ?: return RelayStatus.Stopped
        return RelayStatus.Error("Relay URL: $problem", retryInMs = null, fatal = true)
    }

    /**
     * MCP URLs at which the local server can be reached: always loopback (via `adb forward`), plus
     * each address in [lanAddresses] (non-loopback IPv4) when bound to all interfaces.
     */
    fun localUrls(port: Int, bindAll: Boolean, lanAddresses: List<String>): List<String> {
        val hosts = buildList {
            add(LocalConfig.LOOPBACK)
            if (bindAll) lanAddresses.filterNot { it.startsWith("127.") || it == LocalConfig.ALL_INTERFACES }.forEach(::add)
        }
        return hosts.distinct().map { "http://$it:$port/mcp" }
    }
}
