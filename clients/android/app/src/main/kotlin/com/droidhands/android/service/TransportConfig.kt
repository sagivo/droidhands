package com.droidhands.android.service

import com.droidhands.android.graph.RelayUrls
import com.droidhands.android.settings.BridgeSettings
import com.droidhands.android.settings.LocalBind
import com.droidhands.android.settings.SettingsCodec
import com.droidhands.core.transport.RelayStatus

/** What a running relay client was configured with; a change in any field requires a reconnect. */
data class RelayConfig(
    /** Normalized base URL (no trailing slash). */
    val url: String,
    /** Null when the relay is open (no RELAY_ACCESS_KEY). */
    val accessKey: String?,
    /** The relay needs a DroidHands account (the build's default relay, when the build has accounts). */
    val needsAccount: Boolean = false,
    /** [com.droidhands.android.account.AccountManager.sessionTag] while [needsAccount]: a new sign-in reconnects. */
    val accountSession: String? = null,
) {
    override fun toString(): String =
        "RelayConfig(url=$url, accessKey=${if (accessKey == null) "none" else "***"}, needsAccount=$needsAccount, " +
            "signedIn=${accountSession != null})"
}

/**
 * The relay that needs a DroidHands account ([relayUrl], normalized) and the current session's tag (null when
 * signed out). Only that relay is ever sent the account's token.
 */
data class AccountRelay(val relayUrl: String, val sessionTag: String?)

/**
 * What a running local server was bound with; a change in any field requires a rebind. With
 * [LocalBind.TAILSCALE], [tailnet] is the phone's tailnet address right now (null: Tailscale is
 * off, so the server waits instead of listening).
 */
data class LocalConfig(val port: Int, val bind: LocalBind, val tailnet: String? = null) {
    /** The address to bind, or null while [LocalBind.TAILSCALE] has no tailnet address. */
    val bindAddress: String?
        get() = when (bind) {
            LocalBind.LOOPBACK -> LOOPBACK
            LocalBind.ALL_INTERFACES -> ALL_INTERFACES
            LocalBind.TAILSCALE -> tailnet
        }

    companion object {
        const val LOOPBACK = "127.0.0.1"
        const val ALL_INTERFACES = "0.0.0.0"
    }
}

/** Pure mapping from [BridgeSettings] to the transports that should be running. */
object TransportConfig {
    /**
     * The relay to run, or null when disabled or the URL is invalid. With [account], the relay at its URL gets the
     * account (and only that one: a relay of the user's own never sees the account's token).
     */
    fun relay(settings: BridgeSettings, account: AccountRelay? = null): RelayConfig? {
        if (!settings.relayEnabled || !RelayUrls.isValid(settings.relayUrl)) return null
        val url = RelayUrls.normalize(settings.relayUrl)
        val needsAccount = account != null && url == account.relayUrl
        return RelayConfig(
            url,
            settings.relayAccessKey.trim().ifEmpty { null },
            needsAccount = needsAccount,
            accountSession = if (needsAccount) account?.sessionTag else null,
        )
    }

    /**
     * The local server to run, or null when disabled. [tailnet] (the phone's tailnet address, or
     * null) only matters for [LocalBind.TAILSCALE].
     */
    fun local(settings: BridgeSettings, tailnet: String? = null): LocalConfig? {
        if (!settings.localServerEnabled) return null
        val bind = settings.localServerBind
        return LocalConfig(SettingsCodec.clampPort(settings.localServerPort), bind, tailnet.takeIf { bind == LocalBind.TAILSCALE })
    }

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
     * MCP URLs at which the local server can be reached: loopback (via `adb forward`) unless it
     * listens on the tailnet only, each address in [lanAddresses] (non-loopback IPv4) when bound to
     * all interfaces, and for [LocalBind.TAILSCALE] just [tailnetHost] (MagicDNS name or address).
     */
    fun localUrls(port: Int, bind: LocalBind, lanAddresses: List<String>, tailnetHost: String? = null): List<String> {
        if (bind == LocalBind.TAILSCALE) return listOfNotNull(tailnetHost).map { Tailnet.mcpUrl(it, port) }
        val hosts = buildList {
            add(LocalConfig.LOOPBACK)
            if (bind == LocalBind.ALL_INTERFACES) lanAddresses.filterNot { it.startsWith("127.") || it == LocalConfig.ALL_INTERFACES }.forEach(::add)
        }
        return hosts.distinct().map { "http://$it:$port/mcp" }
    }
}
