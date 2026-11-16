package com.droidhands.android.settings

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
    internal const val KEY_LOCAL_BIND = "local_server_bind"
    internal const val KEY_ACCESS_ENABLED = "access_enabled"
    internal const val KEY_START_ON_BOOT = "start_on_boot"
    internal const val KEY_NEVER_ALLOW_APPS = "never_allow_apps"
    internal const val KEY_REDACT_ONE_TIME_CODES = "redact_one_time_codes"
    internal const val KEY_SECURITY_DEFAULTS_APPLIED = "security_defaults_applied"
    internal const val KEY_LOCAL_PLUGINS_ENABLED = "local_plugins_enabled"
    internal const val KEY_DEFAULT_RELAY_DECIDED = "default_relay_decided"
    internal const val KEY_RELAY_FROM_DEFAULT = "relay_from_default"
    internal const val KEY_CHECK_FOR_UPDATES = "check_for_updates"
    internal const val KEY_SHOW_AGENT_ACTIVITY = "show_agent_activity"

    /** Java/Android package name: at least two dot-separated segments, each starting with a letter. */
    private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    /** Clamps to the unprivileged TCP port range [MIN_PORT]..[MAX_PORT]. */
    fun clampPort(port: Int): Int = port.coerceIn(MIN_PORT, MAX_PORT)

    /** True if [name] is a syntactically valid Android package name. */
    fun isPackageName(name: String): Boolean = name.length <= MAX_PACKAGE_LENGTH && PACKAGE_NAME.matches(name)

    /** Trimmed, valid package names only, sorted (stable display and storage order). */
    fun sanitizePackages(packages: Collection<String>): Set<String> =
        packages.mapNotNullTo(sortedSetOf()) { raw -> raw.trim().takeIf(::isPackageName) }

    /**
     * Normalizes user input: trims URL/key whitespace (common with pasted values), clamps the port
     * and drops malformed package names from the never-allow list. Without a relay URL there is no
     * default relay either.
     */
    fun sanitize(settings: BridgeSettings): BridgeSettings {
        val relayUrl = settings.relayUrl.trim()
        return settings.copy(
            relayUrl = relayUrl,
            relayAccessKey = settings.relayAccessKey.trim(),
            localServerPort = clampPort(settings.localServerPort),
            neverAllowApps = sanitizePackages(settings.neverAllowApps),
            relayFromDefault = settings.relayFromDefault && relayUrl.isNotEmpty(),
        )
    }

    /**
     * Values of the wrong type (or missing) fall back to the defaults instead of crashing.
     *
     * Migration: settings saved before [BridgeSettings.defaultRelayDecided] existed (a non-empty
     * store without that key) decode it as true. Only a fresh install (empty store) may get the
     * build's default relay, so an update never changes how an existing user is connected.
     * Likewise [BridgeSettings.localServerBind] falls back to the older bind-all switch (on: all
     * interfaces, off: loopback), so the local server keeps listening where it did.
     */
    fun decode(values: Map<String, *>): BridgeSettings {
        val defaults = BridgeSettings()
        fun bool(key: String, default: Boolean) = values[key] as? Boolean ?: default
        fun string(key: String, default: String) = values[key] as? String ?: default
        fun int(key: String, default: Int) = (values[key] as? Number)?.toInt() ?: default
        fun strings(key: String, default: Set<String>) = (values[key] as? Set<*>)?.filterIsInstance<String>()?.toSet() ?: default
        return sanitize(
            BridgeSettings(
                relayEnabled = bool(KEY_RELAY_ENABLED, defaults.relayEnabled),
                relayUrl = string(KEY_RELAY_URL, defaults.relayUrl),
                relayAccessKey = string(KEY_RELAY_ACCESS_KEY, defaults.relayAccessKey),
                localServerEnabled = bool(KEY_LOCAL_ENABLED, defaults.localServerEnabled),
                localServerPort = int(KEY_LOCAL_PORT, defaults.localServerPort),
                localServerBind = LocalBind.fromKey(values[KEY_LOCAL_BIND] as? String)
                    ?: if (bool(KEY_LOCAL_BIND_ALL, false)) LocalBind.ALL_INTERFACES else defaults.localServerBind,
                accessEnabled = bool(KEY_ACCESS_ENABLED, defaults.accessEnabled),
                startOnBoot = bool(KEY_START_ON_BOOT, defaults.startOnBoot),
                neverAllowApps = strings(KEY_NEVER_ALLOW_APPS, defaults.neverAllowApps),
                redactOneTimeCodes = bool(KEY_REDACT_ONE_TIME_CODES, defaults.redactOneTimeCodes),
                securityDefaultsApplied = bool(KEY_SECURITY_DEFAULTS_APPLIED, defaults.securityDefaultsApplied),
                localPluginsEnabled = bool(KEY_LOCAL_PLUGINS_ENABLED, defaults.localPluginsEnabled),
                defaultRelayDecided = bool(KEY_DEFAULT_RELAY_DECIDED, values.isNotEmpty()),
                relayFromDefault = bool(KEY_RELAY_FROM_DEFAULT, defaults.relayFromDefault),
                checkForUpdates = bool(KEY_CHECK_FOR_UPDATES, defaults.checkForUpdates),
                showAgentActivity = bool(KEY_SHOW_AGENT_ACTIVITY, defaults.showAgentActivity),
            ),
        )
    }

    /**
     * Every key is written so the stored form is complete and self-describing. The older bind-all
     * switch is still written, so a downgraded app keeps the LAN choice (Tailscale-only reads as off).
     */
    fun encode(settings: BridgeSettings): Map<String, Any> = linkedMapOf(
        KEY_RELAY_ENABLED to settings.relayEnabled,
        KEY_RELAY_URL to settings.relayUrl,
        KEY_RELAY_ACCESS_KEY to settings.relayAccessKey,
        KEY_LOCAL_ENABLED to settings.localServerEnabled,
        KEY_LOCAL_PORT to settings.localServerPort,
        KEY_LOCAL_BIND_ALL to (settings.localServerBind == LocalBind.ALL_INTERFACES),
        KEY_LOCAL_BIND to settings.localServerBind.key,
        KEY_ACCESS_ENABLED to settings.accessEnabled,
        KEY_START_ON_BOOT to settings.startOnBoot,
        KEY_NEVER_ALLOW_APPS to settings.neverAllowApps.toSet(),
        KEY_REDACT_ONE_TIME_CODES to settings.redactOneTimeCodes,
        KEY_SECURITY_DEFAULTS_APPLIED to settings.securityDefaultsApplied,
        KEY_LOCAL_PLUGINS_ENABLED to settings.localPluginsEnabled,
        KEY_DEFAULT_RELAY_DECIDED to settings.defaultRelayDecided,
        KEY_RELAY_FROM_DEFAULT to settings.relayFromDefault,
        KEY_CHECK_FOR_UPDATES to settings.checkForUpdates,
        KEY_SHOW_AGENT_ACTIVITY to settings.showAgentActivity,
    )

    /** Android's limit is far lower in practice; this only bounds pathological stored values. */
    private const val MAX_PACKAGE_LENGTH = 255
}
