package com.droidhands.android.ui

import android.content.Context
import android.content.Intent
import android.util.Log
import com.droidhands.android.pairing.PairingActivity
import com.droidhands.android.settings.SensitiveApps
import com.droidhands.android.graph.BridgeGraph
import com.droidhands.android.settings.BridgeSettings
import com.droidhands.android.ui.deeplink.ConfigureLinks
import com.droidhands.android.ui.apps.ActionNames
import com.droidhands.android.ui.deeplink.ConfigureRequest
import com.droidhands.core.audit.TokenUsage
import com.droidhands.core.tools.Tool
import com.droidhands.core.transport.PairingRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/** The one place the UI touches the graph's newer APIs (usage, agent removal, pairing, security settings). */
internal object GraphAdapters {
    // ---- Usage (graph.usage: UsageTracker) ----

    fun usage(graph: BridgeGraph): StateFlow<Map<String, TokenUsage>> = graph.usage.usage

    // ---- Tool names (graph.registry, graph.appDirectory) ----

    /** Every registered tool; empty if building the registry fails. Runs on IO: building it instantiates every tool. */
    suspend fun registryTools(graph: BridgeGraph): List<Tool> = try {
        withContext(Dispatchers.IO) { graph.registry.all() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Loading tools failed: ${e.javaClass.name}")
        emptyList()
    }

    /** Readable tool names for activity and usage rows: the installed apps' actions and the built-in [tools]. */
    fun actionNames(graph: BridgeGraph, tools: StateFlow<List<Tool>>, scope: CoroutineScope): StateFlow<ActionNames.Index> =
        combine(graph.appDirectory.apps, tools) { apps, builtIns -> ActionNames.Index(apps.orEmpty(), builtIns) }
            .stateIn(scope, SharingStarted.Eagerly, ActionNames.Index(graph.appDirectory.apps.value.orEmpty(), tools.value))

    // ---- Removing agents ----
    // Never TokenManager directly: the graph also stops the agent's calls that are still running (and denies
    // their open prompts), so nothing it started before can act afterwards. Blocking (disk): call from IO.

    suspend fun revokeAgent(graph: BridgeGraph, tokenId: String) {
        graph.revokeAgent(tokenId)
    }

    /** Also forgets the agent's usage counters. */
    suspend fun deleteAgent(graph: BridgeGraph, tokenId: String) {
        graph.deleteAgent(tokenId)
    }

    /** Returns how many agents were newly revoked. */
    suspend fun revokeAllAgents(graph: BridgeGraph): Int = graph.revokeAllAgents()

    // ---- One-tap pairing (graph.pairing: PairingCoordinator) ----

    fun pendingPairings(graph: BridgeGraph): StateFlow<List<PairingRequest>> = graph.pairing.pendingPairings

    /** Opens the Allow/Deny pairing screen (the agent's access is chosen there). */
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

    private const val TAG = "DroidHandsUi"
}
