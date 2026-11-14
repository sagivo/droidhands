package dev.phonebridge.android.ui.settings

import dev.phonebridge.android.settings.BridgeSettings

/**
 * Editable copy of the connection settings shown on the Settings screen. Pure: validation and
 * the mapping back to [BridgeSettings] are unit-tested.
 */
data class SettingsForm(
    val relayEnabled: Boolean,
    val relayUrl: String,
    val relayAccessKey: String,
    val localServerEnabled: Boolean,
    /** Raw text of the port field. */
    val localServerPort: String,
    val localServerBindAll: Boolean,
    val startOnBoot: Boolean,
) {
    /** Field-level problems; the form can be saved only when [isValid]. */
    data class Validation(
        val relayUrl: RelayUrlCheck,
        val relayUrlError: String?,
        val portError: String?,
    ) {
        val isValid: Boolean get() = relayUrlError == null && portError == null

        /** Non-blocking warning for the URL field (plain http:// to a public host). */
        val relayUrlWarning: String? get() = (relayUrl as? RelayUrlCheck.Insecure)?.message
    }

    fun validate(): Validation {
        val urlCheck = RelayUrls.check(relayUrl)
        val urlError = when (urlCheck) {
            is RelayUrlCheck.Invalid -> urlCheck.message
            RelayUrlCheck.Empty -> if (relayEnabled) "Enter your relay's URL, or turn the relay off." else null
            else -> null
        }
        val port = localServerPort.trim().toIntOrNull()
        val portError = when {
            port == null -> "Enter a port number, for example $DEFAULT_PORT."
            port !in MIN_PORT..MAX_PORT -> "Use a port between $MIN_PORT and $MAX_PORT."
            else -> null
        }
        return Validation(urlCheck, urlError, portError)
    }

    /**
     * Applies the edited fields to [current], keeping everything this form doesn't own (for example
     * the global access switch). A different relay URL is the user's own choice, so it no longer
     * counts as the build's default relay, and any change to how the phone connects records the
     * default-relay decision ([BridgeSettings.defaultRelayDecided]). Call only when [validate] is valid.
     */
    fun applyTo(current: BridgeSettings): BridgeSettings {
        val url = RelayUrls.normalize(relayUrl)
        val saved = current.copy(
            relayEnabled = relayEnabled,
            relayUrl = url,
            relayAccessKey = relayAccessKey.trim(),
            localServerEnabled = localServerEnabled,
            localServerPort = localServerPort.trim().toIntOrNull() ?: current.localServerPort,
            localServerBindAll = localServerBindAll,
            startOnBoot = startOnBoot,
            relayFromDefault = current.relayFromDefault && url == RelayUrls.normalize(current.relayUrl),
        )
        // Choosing how to connect is the user's decision: the default relay must not switch itself on later.
        val connectionChanged = saved.relayEnabled != current.relayEnabled || saved.relayUrl != current.relayUrl ||
            saved.relayAccessKey != current.relayAccessKey || saved.localServerEnabled != current.localServerEnabled
        return if (connectionChanged) saved.copy(defaultRelayDecided = true) else saved
    }

    /** True if saving would change [current]. */
    fun differsFrom(current: BridgeSettings): Boolean = applyTo(current) != current

    companion object {
        const val DEFAULT_PORT = 8765

        /** Android apps can't bind privileged ports (< 1024). */
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535

        const val BIND_ALL_WARNING =
            "Anyone on the same Wi-Fi, hotspot or VPN can reach the endpoint. A token is still required, but " +
                "traffic is unencrypted http://. Prefer the relay, adb forward or Tailscale."

        fun from(settings: BridgeSettings) = SettingsForm(
            relayEnabled = settings.relayEnabled,
            relayUrl = settings.relayUrl,
            relayAccessKey = settings.relayAccessKey,
            localServerEnabled = settings.localServerEnabled,
            localServerPort = settings.localServerPort.toString(),
            localServerBindAll = settings.localServerBindAll,
            startOnBoot = settings.startOnBoot,
        )
    }
}
