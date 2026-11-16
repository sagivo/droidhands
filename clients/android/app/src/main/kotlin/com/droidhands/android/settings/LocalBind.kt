package com.droidhands.android.settings

/** Where the phone's local MCP server listens ([BridgeSettings.localServerBind]). */
enum class LocalBind(
    /** Stored form (SharedPreferences); never rename. */
    val key: String,
) {
    /** 127.0.0.1 only: reached over USB with `adb forward`. */
    LOOPBACK("loopback"),

    /**
     * Only the phone's Tailscale address (100.64.0.0/10 or fd7a:115c:a1e0::/48): reachable from the
     * user's own tailnet devices, nowhere else. Not listening while Tailscale is off.
     */
    TAILSCALE("tailscale"),

    /** 0.0.0.0: every network the phone is on (Wi-Fi, hotspot, VPNs), and loopback. */
    ALL_INTERFACES("all"),
    ;

    /** `adb forward` (127.0.0.1 on the phone) reaches the server. */
    val reachesLoopback: Boolean get() = this != TAILSCALE

    companion object {
        fun fromKey(key: String?): LocalBind? = entries.firstOrNull { it.key == key }
    }
}
