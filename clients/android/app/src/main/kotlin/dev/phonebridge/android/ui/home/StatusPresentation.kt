package dev.phonebridge.android.ui.home

import dev.phonebridge.android.graph.LocalServerStatus
import dev.phonebridge.android.settings.BridgeSettings
import dev.phonebridge.android.ui.settings.RelayUrls
import dev.phonebridge.core.transport.RelayStatus

/** Indicator color class for a status line. */
enum class StatusLevel { OK, PENDING, WARNING, ERROR, OFF }

/** One row of the Home status card. [urls] are shown copyable. */
data class StatusLine(val level: StatusLevel, val title: String, val detail: String? = null, val urls: List<String> = emptyList())

/** One step of the "Get started" card. */
data class GettingStartedStep(val title: String, val description: String, val done: Boolean)

/** Pure mapping from bridge state to Home screen text (unit-tested). */
object StatusPresentation {
    fun relay(status: RelayStatus, relayEnabled: Boolean): StatusLine = when (status) {
        is RelayStatus.Connected -> StatusLine(StatusLevel.OK, "Relay connected", "Agents can reach this phone at:", listOf(status.mcpUrl))
        RelayStatus.Connecting -> StatusLine(StatusLevel.PENDING, "Connecting to relay…")
        is RelayStatus.Error -> if (status.fatal) {
            StatusLine(
                StatusLevel.ERROR,
                "Relay refused the connection",
                "${status.message.trimEnd('.')}. Not retrying: check the relay URL and access key in Settings.",
            )
        } else {
            val retry = status.retryInMs?.let { " Retrying in ${secondsText(it)}." } ?: ""
            StatusLine(StatusLevel.WARNING, "Relay unreachable", "${status.message.trimEnd('.')}.$retry")
        }
        RelayStatus.Stopped -> if (relayEnabled) {
            StatusLine(StatusLevel.WARNING, "Relay not running", "The bridge service isn't running. Tap Restart.")
        } else {
            StatusLine(StatusLevel.OFF, "Relay off", "Turn it on in Settings to reach this phone from anywhere.")
        }
    }

    fun local(status: LocalServerStatus, enabled: Boolean): StatusLine = when (status) {
        is LocalServerStatus.Running -> StatusLine(StatusLevel.OK, "Local server running", null, status.urls)
        is LocalServerStatus.Error -> StatusLine(StatusLevel.ERROR, "Local server failed", status.message)
        LocalServerStatus.Stopped -> if (enabled) {
            StatusLine(StatusLevel.WARNING, "Local server not running", "The bridge service isn't running. Tap Restart.")
        } else {
            StatusLine(StatusLevel.OFF, "Local server off", "Optional: for USB (adb forward) or same-network use.")
        }
    }

    /** "mmg42k…s3j4": enough to recognize a device without showing the whole id. */
    fun shortDeviceId(deviceId: String): String =
        if (deviceId.length <= 12) deviceId else deviceId.take(6) + "…" + deviceId.takeLast(4)

    /** Three onboarding steps: grant access, connect, create a token. */
    fun gettingStarted(setupComplete: Boolean, transportConfigured: Boolean, hasActiveToken: Boolean) = listOf(
        GettingStartedStep(
            "Grant access",
            "Allow notifications, contacts, accessibility and notification access so agents can do useful things.",
            setupComplete,
        ),
        GettingStartedStep(
            "Connect",
            "Turn on the relay (reach the phone from anywhere) or the local server (USB or same network).",
            transportConfigured,
        ),
        GettingStartedStep(
            "Create a token",
            "One per agent. Paste the generated config into Claude Code, Claude Desktop, Cursor or any MCP client.",
            hasActiveToken,
        ),
    )

    /** True if at least one transport is enabled with usable settings. */
    fun transportConfigured(settings: BridgeSettings): Boolean =
        (settings.relayEnabled && RelayUrls.mcpUrl(settings.relayUrl) != null) || settings.localServerEnabled

    /** Offer "Restart" when an enabled transport is down for a reason a restart can fix. */
    fun showRestart(relay: RelayStatus, relayEnabled: Boolean, local: LocalServerStatus, localEnabled: Boolean): Boolean {
        val relayDown = relay == RelayStatus.Stopped || (relay is RelayStatus.Error && relay.fatal)
        val localDown = local == LocalServerStatus.Stopped || local is LocalServerStatus.Error
        return (relayEnabled && relayDown) || (localEnabled && localDown)
    }

    /** The card is shown until there is a way in (a transport) and a key (an active token). */
    fun showGettingStarted(transportConfigured: Boolean, hasActiveToken: Boolean): Boolean =
        !transportConfigured || !hasActiveToken

    private fun secondsText(ms: Long): String {
        val s = (ms + 999) / 1000
        return if (s < 60) "$s s" else "${s / 60} min"
    }
}
