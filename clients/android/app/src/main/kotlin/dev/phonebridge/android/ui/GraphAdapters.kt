package dev.phonebridge.android.ui

import android.content.Context
import android.content.Intent
import dev.phonebridge.android.pairing.PairingActivity
import dev.phonebridge.android.settings.SensitiveApps
import dev.phonebridge.android.graph.BridgeGraph
import dev.phonebridge.android.settings.BridgeSettings
import dev.phonebridge.android.ui.deeplink.ConfigureLinks
import dev.phonebridge.android.ui.deeplink.ConfigureRequest
import dev.phonebridge.core.audit.TokenUsage
import dev.phonebridge.core.transport.PairingRequest
import kotlinx.coroutines.flow.StateFlow

/** The one place the UI touches the graph's wave-2 APIs (usage, pairing, security settings). */
internal object GraphAdapters {
    // ---- Usage (graph.usage: UsageTracker) ----

    fun usage(graph: BridgeGraph): StateFlow<Map<String, TokenUsage>> = graph.usage.usage

    fun forgetUsage(graph: BridgeGraph, tokenId: String) = graph.usage.forget(tokenId)

    // ---- One-tap pairing (graph.pairing: PairingCoordinator) ----

    fun pendingPairings(graph: BridgeGraph): StateFlow<List<PairingRequest>> = graph.pairing.pendingPairings

    /** Opens the Allow/Deny pairing screen (access level is chosen there). */
    fun openPairingApproval(graph: BridgeGraph, context: Context, id: String) {
        context.startActivity(PairingActivity.intent(context, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun denyPairing(graph: BridgeGraph, id: String) {
        graph.pairing.deny(id)
    }

    // ---- Setup link (docs/PROTOCOL.md §5.5) ----

    /**
     * Saves the relay URL and key, turns the relay on, and (re)starts the bridge — the documented
     * effect of `graph.applyConfigureLink`. Blocking: call from a background dispatcher.
     */
    fun applyConfigureLink(graph: BridgeGraph, request: ConfigureRequest) {
        graph.settings.update { ConfigureLinks.applyTo(it, request) }
        graph.applySettings()
    }

    // ---- Sensitive-app suggestions ----

    /** Installed banking, payment, password-manager and authenticator packages. Blocking (PackageManager). */
    fun suggestSensitiveApps(context: Context): Set<String> =
        SensitiveApps.suggest(context).mapTo(LinkedHashSet()) { it.packageName }

    // ---- Security settings ----

    fun neverAllowApps(settings: BridgeSettings): Set<String> = settings.neverAllowApps

    fun withNeverAllowApps(settings: BridgeSettings, apps: Set<String>): BridgeSettings = settings.copy(neverAllowApps = apps)

    fun redactOneTimeCodes(settings: BridgeSettings): Boolean = settings.redactOneTimeCodes

    fun withRedactOneTimeCodes(settings: BridgeSettings, on: Boolean): BridgeSettings = settings.copy(redactOneTimeCodes = on)

    fun securityDefaultsApplied(settings: BridgeSettings): Boolean = settings.securityDefaultsApplied
}
