package com.droidhands.android.ui

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.annotation.MainThread
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.droidhands.android.BuildConfig
import com.droidhands.android.graph.BridgeGraph
import com.droidhands.android.graph.DefaultRelay
import com.droidhands.android.graph.LocalServerStatus
import com.droidhands.android.service.TailscaleState
import com.droidhands.android.settings.BridgeSettings
import com.droidhands.android.settings.LocalBind
import com.droidhands.android.ui.activity.ActivityQuery
import com.droidhands.android.ui.agents.AgentPresentation
import com.droidhands.android.ui.apps.ActionNames
import com.droidhands.android.ui.connect.ConnectionRoutes
import com.droidhands.android.ui.deeplink.ConfigureRequest
import com.droidhands.android.ui.home.StatusPresentation
import com.droidhands.android.ui.onboarding.OnboardingFlow
import com.droidhands.android.ui.settings.RelayUrls
import com.droidhands.core.audit.AuditEntry
import com.droidhands.core.audit.TokenUsage
import com.droidhands.core.auth.TokenRecord
import com.droidhands.core.confirm.ConfirmationRequest
import com.droidhands.core.transport.PairingRequest
import com.droidhands.core.transport.RelayStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Activity-scoped access to the bridge state shared by Home, Activity, onboarding and the connect
 * page. Must be created after [GraphWarmup] completed. Mutations run on [Dispatchers.IO] (they
 * persist settings or tokens, start/stop the service or cancel notifications).
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = BridgeGraph.get(app)
    private val prefs = UiPrefs(app)
    private val _messages = Channel<String>(Channel.BUFFERED)
    private val _showOnboarding = MutableStateFlow<Boolean?>(null)

    val settings: StateFlow<BridgeSettings> = graph.settings.state
    val tokens: StateFlow<List<TokenRecord>> = graph.tokenManager.tokens
    val relayStatus: StateFlow<RelayStatus> = graph.relayStatus
    val localServerStatus: StateFlow<LocalServerStatus> = graph.localServerStatus

    /** Tailscale on this phone, for the Tailscale route; [refreshTailscale] updates it. */
    val tailscale: StateFlow<TailscaleState> = graph.tailscale.state
    val pendingApprovals: StateFlow<List<ConfirmationRequest>> = graph.confirmations.pendingRequests
    val pendingPairings: StateFlow<List<PairingRequest>> = GraphAdapters.pendingPairings(graph)
    val auditEntries: StateFlow<List<AuditEntry>> = graph.audit.entries
    val usage: StateFlow<Map<String, TokenUsage>> = GraphAdapters.usage(graph)
    val deviceId: String = graph.identity.deviceId

    /** Readable names of app actions ("Spotify – Play music") for the Activity tab; see [loadAppActions]. */
    val actionNames: StateFlow<ActionNames.Index> = graph.appDirectory.apps
        .map { ActionNames.Index(it.orEmpty()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ActionNames.Index(graph.appDirectory.apps.value.orEmpty()))

    /** One-off user-facing messages (snackbar). */
    val messages: Flow<String> = _messages.receiveAsFlow()

    /** Whether the onboarding wizard covers the app; null until the saved flag is read. */
    val showOnboarding: StateFlow<Boolean?> = _showOnboarding.asStateFlow()

    /** Activity tab filters; the agent detail page sets them for "See all activity". Main thread. */
    var activityQuery by mutableStateOf(ActivityQuery())

    /** The build's default relay (normalized), or null when this build has none. */
    val defaultRelayUrl: String? = DefaultRelay.url(BuildConfig.DEFAULT_RELAY_URL)

    /** A newer release to offer on Home (e.g. "1.3.0"), while "Check for updates" is on. */
    val availableUpdate: StateFlow<String?> = combine(graph.updates.available, settings) { version, current ->
        version.takeIf { current.checkForUpdates }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** Where the update card's Download button goes (the latest release's APK), or null. */
    val updateDownloadUrl: String? = graph.updates.downloadUrl

    init {
        viewModelScope.launch {
            val completed = try {
                prefs.onboardingCompleted()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Reading the onboarding flag failed: ${e.javaClass.name}")
                true
            }
            _showOnboarding.value = OnboardingFlow.shouldShow(
                completed = completed,
                hasTokens = tokens.value.isNotEmpty(),
                // Being online through the build's default relay doesn't mean the user set anything up.
                transportConfigured = StatusPresentation.userTransportConfigured(settings.value),
            )
        }
    }

    /**
     * Asks for a newer release if "Check for updates" is on (at most once a day: [com.droidhands.android.update.UpdateChecker.refresh] skips
     * the network otherwise). The UI calls it each time the app comes to the foreground, so a phone that keeps
     * the app alive for days still learns about releases.
     */
    fun checkForUpdates() {
        if (settings.value.checkForUpdates) viewModelScope.launch(Dispatchers.IO) { graph.updates.refresh() }
    }

    /**
     * First run of a build with a default relay: go online through it unless something is set up
     * already ([DefaultRelay]). The UI calls this once the app is visible, and skips it while a
     * setup link waits for an answer (that link's relay would replace it right away).
     */
    fun applyDefaultRelayIfNeeded() = io("Couldn't connect to the relay") { graph.applyDefaultRelayIfNeeded() }

    /** "Go online with <host>": use the build's default relay. */
    fun useDefaultRelay() = io("Couldn't connect to the relay") {
        graph.useDefaultRelay()
        _messages.send("Connecting…")
    }

    /** Loads the installed apps' actions in the background, for [actionNames] (a no-op when loaded moments ago). */
    fun loadAppActions() = graph.appDirectory.refresh()

    /** Hides the update card for [version] (a later release shows it again). */
    fun dismissUpdate(version: String) = io("Couldn't save that") { graph.updates.dismiss(version) }

    /** Finishes or skips the wizard for good. */
    @MainThread
    fun finishOnboarding() {
        _showOnboarding.value = false
        viewModelScope.launch { runCatching { prefs.setOnboardingCompleted(true) } }
    }

    /** Settings > About > "Run setup again". */
    @MainThread
    fun reopenOnboarding() {
        _showOnboarding.value = true
    }

    fun setAccessEnabled(enabled: Boolean) = io("Couldn't change access") { graph.setAccessEnabled(enabled) }

    /** Allow/Deny from the in-app list; same effect as the notification buttons. */
    fun resolveApproval(id: String, approved: Boolean) = io("Couldn't answer the request") {
        graph.confirmations.resolve(id, approved)
    }

    /** Epoch millis when an approval stops waiting, for the countdown on its card. */
    fun approvalExpiresAt(id: String): Long? = graph.confirmations.expiresAt(id)

    /** Opens the full Allow/Deny pairing screen (it requires an unlocked phone). */
    @MainThread
    fun openPairing(context: Context, id: String) {
        try {
            GraphAdapters.openPairingApproval(graph, context, id)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Opening the pairing prompt failed: ${e.javaClass.name}")
            _messages.trySend("Couldn't open the connection request. Use the DroidHands notification instead.")
        }
    }

    fun denyPairing(id: String) = io("Couldn't deny the connection request") { GraphAdapters.denyPairing(graph, id) }

    /** Re-applies settings, (re)starting the bridge service. */
    fun restartBridge() = io("Couldn't start the bridge service") {
        graph.applySettings()
        _messages.send("Restarting the bridge…")
    }

    fun clearAudit() = io("Couldn't clear the activity log") {
        graph.audit.clear()
        _messages.send("Activity log cleared")
    }

    /** Locks every agent out at once and stops their running calls (pairing an agent again creates a new one). */
    fun revokeAll() = io("Couldn't revoke the agents") {
        val count = GraphAdapters.revokeAllAgents(graph)
        _messages.send(AgentPresentation.revokedAllText(count))
    }

    /** Onboarding / connect page: saves a relay URL (and optional key), turns the relay on and connects. */
    fun connectRelay(url: String, accessKey: String) = io("Couldn't save the relay") {
        val normalized = RelayUrls.normalize(url)
        graph.settings.update {
            it.copy(relayEnabled = true, relayUrl = normalized, relayAccessKey = accessKey.trim(), relayFromDefault = false, defaultRelayDecided = true)
        }
        graph.applySettings()
        _messages.send("Connecting to the relay…")
    }

    /**
     * Onboarding "Local only": serves MCP on this phone (127.0.0.1, reached with adb forward). The
     * build's default relay, if it is on, is turned off: local only means no relay. A relay the
     * user set up stays as it is.
     */
    fun enableLocalServer() = io("Couldn't start the local server") {
        graph.settings.update {
            // Off the tailnet-only mode (that is the Tailscale route); a LAN choice made in Settings stays.
            val bind = if (it.localServerBind == LocalBind.TAILSCALE) LocalBind.LOOPBACK else it.localServerBind
            it.copy(
                localServerEnabled = true,
                localServerBind = bind,
                relayEnabled = it.relayEnabled && !it.relayFromDefault,
                defaultRelayDecided = true,
            )
        }
        graph.applySettings()
        _messages.send("Local server starting on port ${settings.value.localServerPort}…")
    }

    /**
     * The Tailscale route on or off ([ConnectionRoutes.withTailscale]): on, the local server listens on
     * the phone's tailnet address only. A relay stays on, so both routes work at once.
     */
    fun setTailscale(on: Boolean) = io("Couldn't change Tailscale") {
        graph.settings.update { ConnectionRoutes.withTailscale(it, on) }
        graph.applySettings()
        graph.tailscale.refresh()
        _messages.send(if (on) "Listening on your tailnet…" else "Tailscale route off.")
    }

    /** Looks for Tailscale again (the user may have just installed or connected it). */
    fun refreshTailscale() = io("Couldn't check Tailscale") { graph.tailscale.refresh() }

    /** Accepts a confirmed `droidhands://configure` link. */
    fun applyConfigureLink(request: ConfigureRequest) = io("Couldn't apply the setup link") {
        GraphAdapters.applyConfigureLink(graph, request)
        _messages.send("Connecting to ${request.host}…")
    }

    private fun io(failure: String, block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "$failure: ${e.javaClass.name}")
                _messages.send("$failure: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private companion object {
        const val TAG = "DroidHandsUi"
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
