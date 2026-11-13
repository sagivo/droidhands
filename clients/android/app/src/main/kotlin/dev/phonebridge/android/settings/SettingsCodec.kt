package dev.phonebridge.android.settings

/**
 * Pure mapping between [BridgeSettings] and the flat key/value form stored in SharedPreferences,
 * plus sanitizing rules. Android-free so it can be unit-tested on the JVM.
 */
object SettingsCodec {
    const val MIN_PORT = 1024
    const val MAX_PORT = 65535

    internal const val KEY_RELAY_ENABLED = "relay_enabled"
    internal const val KEY_RELAY_URL = "relay_url"
    internal const val KEY_RELAY_ACCESS_KEY = "relay_access_key"
    internal const val KEY_LOCAL_ENABLED = "local_server_enabled"
    internal const val KEY_LOCAL_PORT = "local_server_port"
    internal const val KEY_LOCAL_BIND_ALL = "local_server_bind_all"
    internal const val KEY_ACCESS_ENABLED = "access_enabled"
    internal const val KEY_START_ON_BOOT = "start_on_boot"

    /** Clamps to the unprivileged TCP port range [MIN_PORT]..[MAX_PORT]. */
    fun clampPort(port: Int): Int = port.coerceIn(MIN_PORT, MAX_PORT)

    /** Normalizes user input: trims URL/key whitespace (common with pasted values) and clamps the port. */
    fun sanitize(settings: BridgeSettings): BridgeSettings = settings.copy(
        relayUrl = settings.relayUrl.trim(),
        relayAccessKey = settings.relayAccessKey.trim(),
        localServerPort = clampPort(settings.localServerPort),
    )

    /** Values of the wrong type (or missing) fall back to the defaults instead of crashing. */
    fun decode(values: Map<String, *>): BridgeSettings {
        val defaults = BridgeSettings()
        fun bool(key: String, default: Boolean) = values[key] as? Boolean ?: default
        fun string(key: String, default: String) = values[key] as? String ?: default
        fun int(key: String, default: Int) = (values[key] as? Number)?.toInt() ?: default
        return sanitize(
            BridgeSettings(
                relayEnabled = bool(KEY_RELAY_ENABLED, defaults.relayEnabled),
                relayUrl = string(KEY_RELAY_URL, defaults.relayUrl),
                relayAccessKey = string(KEY_RELAY_ACCESS_KEY, defaults.relayAccessKey),
                localServerEnabled = bool(KEY_LOCAL_ENABLED, defaults.localServerEnabled),
                localServerPort = int(KEY_LOCAL_PORT, defaults.localServerPort),
                localServerBindAll = bool(KEY_LOCAL_BIND_ALL, defaults.localServerBindAll),
                accessEnabled = bool(KEY_ACCESS_ENABLED, defaults.accessEnabled),
                startOnBoot = bool(KEY_START_ON_BOOT, defaults.startOnBoot),
            ),
        )
    }

    /** Every key is written so the stored form is complete and self-describing. */
    fun encode(settings: BridgeSettings): Map<String, Any> = linkedMapOf(
        KEY_RELAY_ENABLED to settings.relayEnabled,
        KEY_RELAY_URL to settings.relayUrl,
        KEY_RELAY_ACCESS_KEY to settings.relayAccessKey,
        KEY_LOCAL_ENABLED to settings.localServerEnabled,
        KEY_LOCAL_PORT to settings.localServerPort,
        KEY_LOCAL_BIND_ALL to settings.localServerBindAll,
        KEY_ACCESS_ENABLED to settings.accessEnabled,
        KEY_START_ON_BOOT to settings.startOnBoot,
    )
}
