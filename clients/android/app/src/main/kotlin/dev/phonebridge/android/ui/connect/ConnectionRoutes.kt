package dev.phonebridge.android.ui.connect

import dev.phonebridge.android.graph.LocalServerStatus
import dev.phonebridge.android.service.TailscaleState
import dev.phonebridge.android.settings.BridgeSettings
import dev.phonebridge.android.settings.LocalBind
import dev.phonebridge.android.ui.deeplink.ConfigureLinks
import dev.phonebridge.android.ui.format.Hosts
import dev.phonebridge.android.ui.home.StatusPresentation
import dev.phonebridge.core.transport.RelayStatus
import java.net.URI

/** A way agents reach this phone. */
enum class RouteKind { RELAY, TAILSCALE, LAN, USB }

/**
 * One route that is switched on, for the "How agents reach this phone" list: its connection URL once
 * it works, otherwise why not yet ([status]).
 */
data class ActiveRoute(
    val kind: RouteKind,
    val title: String,
    /** The URL to paste into an agent; null while the route isn't ready. */
    val url: String?,
    /** Who can use it, or what it is waiting for. */
    val status: String,
)

/**
 * Connection routes, modelled on T3 Code's remote-access routes (a hosted relay, your own relay,
 * Tailscale): the user-facing text, which routes are on and their URLs, and the settings changes
 * behind the Tailscale choice. Pure, unit-tested. Routes combine: e.g. the relay for claude.ai and
 * Tailscale for Claude Code at the same time.
 */
object ConnectionRoutes {
    const val RELAY_TITLE = "PhoneBridge relay"
    const val RELAY_SUMMARY =
        "Works with every agent, including claude.ai and ChatGPT, on Wi-Fi or mobile data. Nothing to set up."

    const val TAILSCALE_TITLE = "Tailscale (private, no relay)"
    const val TAILSCALE_SUMMARY =
        "Your own devices on your tailnet reach this phone directly: nothing passes through a relay, and Tailscale " +
            "encrypts the traffic. Works with Claude Code, Codex and Claude Desktop on your computers. Not with " +
            "claude.ai or ChatGPT: they run in the cloud and can't reach your tailnet."

    const val OWN_RELAY_TITLE = "Your own relay"
    const val OWN_RELAY_SUMMARY =
        "Run the relay yourself, on a server or on your computer. Works with every agent, and nobody else handles " +
            "your agents' requests."
    const val OWN_RELAY_SCRIPT =
        "On a computer with Tailscale, scripts/relay-tailscale.sh in the PhoneBridge repository runs one for you and " +
            "prints its setup link (add --public for claude.ai and ChatGPT)."

    /** Shown with every connection URL: one-tap pairing works the same on each route. */
    const val PASTE_HINT = "Paste the URL into your AI agent, then tap Allow on this phone."

    /** The Tailscale route is chosen: the local server listens on the tailnet only. */
    fun tailscaleOn(settings: BridgeSettings): Boolean =
        settings.localServerEnabled && settings.localServerBind == LocalBind.TAILSCALE

    /**
     * [settings] with the Tailscale route on or off. On: the local server listens on the tailnet
     * only; a relay stays as it is (routes combine) and the default relay won't be added later. Off:
     * the local server stops (it served only Tailscale).
     */
    fun withTailscale(settings: BridgeSettings, on: Boolean): BridgeSettings = when {
        on -> settings.copy(localServerEnabled = true, localServerBind = LocalBind.TAILSCALE, defaultRelayDecided = true)
        tailscaleOn(settings) -> settings.copy(localServerEnabled = false)
        else -> settings
    }

    /** Tailscale on this phone, in one sentence. */
    fun tailscaleStatus(state: TailscaleState): String {
        val tailnet = state.tailnet
        return when {
            tailnet != null -> "This phone is on your tailnet as ${tailnet.host}."
            state.installed -> "Tailscale isn't connected. Open it and connect. If it's already on, check that PhoneBridge " +
                "isn't excluded in Tailscale's app settings."
            else -> "Tailscale isn't installed on this phone yet. Get it, sign in with the same account as your computer, " +
                "and come back."
        }
    }

    /** The relay's name for people: "PhoneBridge relay" for the build's own, else "Your relay (host)". */
    fun relayTitle(settings: BridgeSettings): String =
        if (settings.relayFromDefault) RELAY_TITLE else "Your relay (${ConfigureLinks.hostOf(settings.relayUrl) ?: settings.relayUrl})"

    /** Every route that is switched on, in the order agents should prefer them. */
    fun active(settings: BridgeSettings, relay: RelayStatus, local: LocalServerStatus): List<ActiveRoute> = buildList {
        if (settings.relayEnabled) add(relayRoute(settings, relay))
        if (settings.localServerEnabled) addAll(localRoutes(settings, local))
    }

    private fun relayRoute(settings: BridgeSettings, relay: RelayStatus): ActiveRoute {
        val url = (relay as? RelayStatus.Connected)?.let(StatusPresentation::connectionUrl)
        val status = if (url != null) "Every agent, including claude.ai and ChatGPT." else StatusPresentation.relay(relay, true).title
        return ActiveRoute(RouteKind.RELAY, relayTitle(settings), url, status)
    }

    private fun localRoutes(settings: BridgeSettings, local: LocalServerStatus): List<ActiveRoute> {
        val bind = settings.localServerBind
        val urls = (local as? LocalServerStatus.Running)?.urls.orEmpty()
        val notReady = when (local) {
            is LocalServerStatus.Error -> local.message
            LocalServerStatus.WaitingForTailscale -> "Waiting for Tailscale. Turn it on on this phone."
            else -> "Starting…"
        }
        if (bind == LocalBind.TAILSCALE) {
            val url = urls.firstOrNull()
            return listOf(ActiveRoute(RouteKind.TAILSCALE, "Tailscale", url, if (url != null) "Your devices on your tailnet." else notReady))
        }
        if (urls.isEmpty()) return listOf(ActiveRoute(RouteKind.USB, "Local server", null, notReady))
        return urls.map { url ->
            val host = hostOf(url)
            when {
                host == null || Hosts.isLoopback(host) ->
                    ActiveRoute(RouteKind.USB, "USB", url, "On the computer, run: adb forward tcp:${settings.localServerPort} tcp:${settings.localServerPort}")
                Hosts.isTailscale(host) -> ActiveRoute(RouteKind.TAILSCALE, "Tailscale", url, "Your devices on your tailnet.")
                else -> ActiveRoute(RouteKind.LAN, "Same network", url, "Devices on the same Wi-Fi. Unencrypted http://.")
            }
        }
    }

    private fun hostOf(url: String): String? = runCatching { URI(url).host }.getOrNull()
}
