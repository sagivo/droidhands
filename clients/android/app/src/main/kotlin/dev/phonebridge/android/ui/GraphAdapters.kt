package dev.phonebridge.android.ui

import android.content.Context
import dev.phonebridge.android.graph.BridgeGraph
import dev.phonebridge.android.settings.BridgeSettings
import dev.phonebridge.android.ui.deeplink.ConfigureLinks
import dev.phonebridge.android.ui.deeplink.ConfigureRequest
import dev.phonebridge.core.audit.TokenUsage
import dev.phonebridge.core.transport.PairingRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The only place the UI touches wave-2 APIs implemented in parallel by other components
 * (services, core). Everything else in `ui/` programs against these functions, so integrating the
 * real implementations is a change to this file only.
 *
 * Each member documents the API it stands in for. Members marked `INTEGRATION` are placeholders
 * in this tree: they compile against what exists today and behave as "nothing there yet".
 * Members marked `TEMPORARY settings shim` read/write [BridgeSettings] fields that the services
 * component adds (`neverAllowApps`, `redactOneTimeCodes`, `securityDefaultsApplied`).
 */
internal object GraphAdapters {
    // ---- Usage (core: graph.usage: UsageTracker) ----

    private val noUsage = MutableStateFlow<Map<String, TokenUsage>>(emptyMap()).asStateFlow()

    /** INTEGRATION: `graph.usage.usage` (tokenId -> [TokenUsage]). */
    @Suppress("UNUSED_PARAMETER")
    fun usage(graph: BridgeGraph): StateFlow<Map<String, TokenUsage>> = noUsage

    /** INTEGRATION: `graph.usage.forget(tokenId)` after a token is deleted. */
    @Suppress("UNUSED_PARAMETER")
    fun forgetUsage(graph: BridgeGraph, tokenId: String) = Unit

    // ---- One-tap pairing (services: graph.pairing) ----

    private val noPairings = MutableStateFlow<List<PairingRequest>>(emptyList()).asStateFlow()

    /** INTEGRATION: `graph.pairing.pendingPairings` (oldest first). */
    @Suppress("UNUSED_PARAMETER")
    fun pendingPairings(graph: BridgeGraph): StateFlow<List<PairingRequest>> = noPairings

    /** INTEGRATION: `graph.pairing.openApproval(context, id)` (opens the Allow/Deny pairing screen). */
    @Suppress("UNUSED_PARAMETER")
    fun openPairingApproval(graph: BridgeGraph, context: Context, id: String) = Unit

    /** INTEGRATION: `graph.pairing.deny(id)`. */
    @Suppress("UNUSED_PARAMETER")
    fun denyPairing(graph: BridgeGraph, id: String) = Unit

    // ---- Setup link (services: ConfigureLink.parse(uri), graph.applyConfigureLink(link)) ----

    /**
     * INTEGRATION: `graph.applyConfigureLink(ConfigureLink(...))`. Until then this applies the link
     * with the same documented effect (docs/PROTOCOL.md §5.5): save the relay URL and key, turn the
     * relay on, and (re)start the bridge. Blocking: call from a background dispatcher.
     */
    fun applyConfigureLink(graph: BridgeGraph, request: ConfigureRequest) {
        graph.settings.update { ConfigureLinks.applyTo(it, request) }
        graph.applySettings()
    }

    // ---- Sensitive-app suggestions (services: SensitiveApps.suggest(context)) ----

    /**
     * INTEGRATION: `SensitiveApps.suggest(context)` — installed banking, payment, password-manager
     * and authenticator packages. Blocking (PackageManager): call from a background dispatcher.
     */
    @Suppress("UNUSED_PARAMETER")
    fun suggestSensitiveApps(context: Context): Set<String> = emptySet()

    // ---- TEMPORARY settings shim (services add these fields to BridgeSettings) ----

    /** TEMPORARY settings shim: `settings.neverAllowApps` (default empty). */
    @Suppress("UNUSED_PARAMETER")
    fun neverAllowApps(settings: BridgeSettings): Set<String> = emptySet()

    /** TEMPORARY settings shim: `settings.copy(neverAllowApps = apps)` (no-op until the field exists). */
    @Suppress("UNUSED_PARAMETER")
    fun withNeverAllowApps(settings: BridgeSettings, apps: Set<String>): BridgeSettings = settings

    /** TEMPORARY settings shim: `settings.redactOneTimeCodes` (default true). */
    @Suppress("UNUSED_PARAMETER")
    fun redactOneTimeCodes(settings: BridgeSettings): Boolean = true

    /** TEMPORARY settings shim: `settings.copy(redactOneTimeCodes = on)` (no-op until the field exists). */
    @Suppress("UNUSED_PARAMETER")
    fun withRedactOneTimeCodes(settings: BridgeSettings, on: Boolean): BridgeSettings = settings

    /** TEMPORARY settings shim: `settings.securityDefaultsApplied` (default false). */
    @Suppress("UNUSED_PARAMETER")
    fun securityDefaultsApplied(settings: BridgeSettings): Boolean = false
}
