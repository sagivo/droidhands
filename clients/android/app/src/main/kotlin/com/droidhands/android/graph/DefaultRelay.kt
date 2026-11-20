package com.droidhands.android.graph

import com.droidhands.android.settings.BridgeSettings
import com.droidhands.android.ui.settings.RelayUrlCheck
import com.droidhands.android.ui.settings.RelayUrls

/**
 * The build's default relay (`BuildConfig.DEFAULT_RELAY_URL`, set by the release workflow; see
 * docs/RELEASING.md). A fresh install connects to it on first run, so the phone is reachable from
 * the internet without any relay setup. Pure, unit-tested.
 *
 * Rules: it is applied once per install, only when nothing is set up yet (no relay URL and no
 * local server), and it never replaces a relay the user chose. Choosing a relay (typing one,
 * opening a setup link, editing it in Settings) clears [BridgeSettings.relayFromDefault]. Any explicit
 * connection choice (those, or the local server) also sets [BridgeSettings.defaultRelayDecided], so
 * the default relay can't switch itself on later. When the default relay needs a DroidHands account and nobody
 * is signed in, the first run leaves it off and undecided: the user signs in from the Connect step, which turns
 * it on ([use]).
 */
object DefaultRelay {
    /**
     * The usable, normalized default relay URL, or null when the build has none. Plain http:// to
     * a public host is refused (it would expose every agent request); http:// to a private
     * network or the emulator host is allowed for testing.
     */
    fun url(raw: String): String? = (RelayUrls.check(raw) as? RelayUrlCheck.Ok)?.normalized

    /** Something to connect through is already set up: a relay URL (on or off) or the local server. */
    fun somethingConfigured(settings: BridgeSettings): Boolean = settings.relayUrl.isNotBlank() || settings.localServerEnabled

    /**
     * First run: [settings] with the default relay turned on when nothing is set up yet, and the
     * decision recorded either way so it is made once. Unchanged once decided.
     */
    fun applyOnFirstRun(settings: BridgeSettings, rawDefaultUrl: String, signInNeeded: Boolean = false): BridgeSettings {
        if (settings.defaultRelayDecided || signInNeeded) return settings
        val url = url(rawDefaultUrl)
        if (url == null || somethingConfigured(settings)) return settings.copy(defaultRelayDecided = true)
        return use(settings, url)
    }

    /** The default relay is on and is the relay in use (the user didn't pick another). */
    fun inUse(settings: BridgeSettings): Boolean = settings.relayEnabled && settings.relayFromDefault

    /**
     * Offer "Go online with <host>": the build has a default relay and no relay of the user's is
     * set up (none at all, or only the default one, switched off).
     */
    fun canOffer(settings: BridgeSettings, rawDefaultUrl: String): Boolean =
        url(rawDefaultUrl) != null && !inUse(settings) && (settings.relayUrl.isBlank() || settings.relayFromDefault)

    /** [settings] using the default relay: on, its URL, no access key. [rawDefaultUrl] must be usable. */
    fun use(settings: BridgeSettings, rawDefaultUrl: String): BridgeSettings {
        val url = requireNotNull(url(rawDefaultUrl)) { "No usable default relay" }
        return settings.copy(
            relayEnabled = true,
            relayUrl = url,
            relayAccessKey = "",
            relayFromDefault = true,
            defaultRelayDecided = true,
        )
    }
}
