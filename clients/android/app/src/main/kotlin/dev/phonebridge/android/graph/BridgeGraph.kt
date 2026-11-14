package dev.phonebridge.android.graph

import android.app.Application
import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import dev.phonebridge.android.BuildConfig
import dev.phonebridge.android.access.BridgeAccessibilityService
import dev.phonebridge.android.confirm.NotificationConfirmationProvider
import dev.phonebridge.android.notify.BridgeNotificationListener
import dev.phonebridge.android.pairing.PairingCoordinator
import dev.phonebridge.android.pairing.PairingNotifications
import dev.phonebridge.android.plugins.BundledPlugins
import dev.phonebridge.android.plugins.LocalPlugins
import dev.phonebridge.android.plugins.PluginRepository
import dev.phonebridge.android.service.BridgeService
import dev.phonebridge.android.service.CoreTransports
import dev.phonebridge.android.service.NetworkAddresses
import dev.phonebridge.android.service.StatusText
import dev.phonebridge.android.service.TailscaleMonitor
import dev.phonebridge.android.service.TransportConfig
import dev.phonebridge.android.service.TransportController
import dev.phonebridge.android.service.WakeLockedHandler
import dev.phonebridge.android.settings.SecurityDefaults
import dev.phonebridge.android.settings.SensitiveApps
import dev.phonebridge.android.settings.SettingsRepository
import dev.phonebridge.android.tools.AndroidToolCatalog
import dev.phonebridge.android.tools.actions.AndroidAppActions
import dev.phonebridge.android.tools.support.ApprovalGuard
import dev.phonebridge.android.tools.support.DeviceState
import dev.phonebridge.android.ui.apps.AppDirectory
import dev.phonebridge.android.ui.apps.AppListings
import dev.phonebridge.android.update.UpdateChecker
import dev.phonebridge.core.audit.AuditLog
import dev.phonebridge.core.audit.FileAuditLog
import dev.phonebridge.core.audit.TrackingAuditLog
import dev.phonebridge.core.audit.UsageTracker
import dev.phonebridge.core.auth.DeviceIdentity
import dev.phonebridge.core.auth.FileTokenStore
import dev.phonebridge.core.auth.TokenManager
import dev.phonebridge.core.mcp.McpServer
import dev.phonebridge.core.mcp.ServerInfo
import dev.phonebridge.core.mcp.capabilitiesTool
import dev.phonebridge.core.plugins.AppActionCatalog
import dev.phonebridge.core.plugins.PluginSource
import dev.phonebridge.core.policy.GlobalSecurity
import dev.phonebridge.core.policy.PolicyEngine
import dev.phonebridge.core.tools.ToolRegistry
import dev.phonebridge.core.transport.RelayStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.time.ZoneId

sealed interface LocalServerStatus {
    data object Stopped : LocalServerStatus

    /** [urls] e.g. ["http://127.0.0.1:8765/mcp", "http://192.168.1.20:8765/mcp"]. */
    data class Running(val urls: List<String>) : LocalServerStatus

    data class Error(val message: String) : LocalServerStatus

    /** Set to listen on the tailnet only, but the phone isn't on Tailscale right now. */
    data object WaitingForTailscale : LocalServerStatus
}

/**
 * App-wide manual DI container. Created once in [dev.phonebridge.android.PhoneBridgeApp.onCreate].
 *
 * Disk-backed components (settings, identity, tokens, audit log) are created lazily and warmed up
 * on a background thread at startup, so the main thread normally finds them ready. Every property
 * is thread-safe to read from any thread.
 */
class BridgeGraph private constructor(val app: Application) {
    /** Application-lifetime scope for background work that must outlive any one component. */
    internal val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val settings: SettingsRepository by lazy { SettingsRepository(app) }

    val identity: DeviceIdentity by lazy { IdentityStore.loadOrCreate(app) }

    val tokenManager: TokenManager by lazy {
        TokenManager(FileTokenStore(File(app.filesDir, TOKENS_FILE)), identity.deviceId)
    }

    private val usageLazy = lazy { UsageTracker(File(app.filesDir, USAGE_FILE)) }

    /** Per-agent usage counters (calls per tool and per day), fed by [audit]; kept beyond the audit ring buffer. */
    val usage: UsageTracker by usageLazy

    /** The persisted audit log; every entry also updates [usage]. */
    val audit: AuditLog by lazy { TrackingAuditLog(FileAuditLog(File(app.filesDir, AUDIT_FILE)), usage) }

    val registry: ToolRegistry by lazy {
        ToolRegistry().also { registry ->
            // First, so agents see it at the top of tools/list.
            registry.register(capabilitiesTool(registry, ::extraStatus))
            registry.registerAll(AndroidToolCatalog.build(app, appActions))
        }
    }

    /**
     * App plugins (docs/PLUGINS.md): the reviewed ones shipped in the app, plus the local ones imported on this
     * phone while Settings > Developer > Local plugins is on (read per call, so the switch applies at once).
     */
    val plugins: PluginRepository by lazy {
        PluginRepository(
            bundledFiles = { BundledPlugins.files(app.assets) },
            local = LocalPlugins(File(app.filesDir, LocalPlugins.DIR)),
            localEnabled = { settings.state.value.localPluginsEnabled },
            log = { Log.w(TAG, it) },
        )
    }

    private val actionsPlatform by lazy { AndroidAppActions(app) }

    /** Every installed app's actions (plugins plus what apps declare): behind apps_list, app_actions_list and app_action_run. */
    val appActions: AppActionCatalog by lazy {
        AppActionCatalog(plugins::catalog, actionsPlatform, builtInTool = { name -> registry.get(name) })
    }

    /**
     * The installed apps and their actions as the UI shows them (Apps tab, per-action rules, activity names).
     * Loaded in the background on demand; nothing here is reachable by agents.
     */
    val appDirectory: AppDirectory by lazy {
        AppDirectory(load = { AppListings.load(appActions) }, scope = appScope, log = { message, error -> Log.w(TAG, message, error) })
    }

    val confirmations: NotificationConfirmationProvider by lazy { NotificationConfirmationProvider(app) }

    /** Daily "is there a newer release?" check against the build's GitHub repository (if it names one). */
    val updates: UpdateChecker by lazy { UpdateChecker(app, BuildConfig.GITHUB_REPO, BuildConfig.VERSION_NAME) }

    /**
     * One-tap agent pairing (docs/PROTOCOL.md §5): answers the relay's pair requests, shows the
     * prompt, mints the agent's token. Its [PairingCoordinator.pendingPairings] feed the Home card.
     */
    val pairing: PairingCoordinator by lazy {
        PairingCoordinator(
            tokens = { tokenManager },
            prompts = PairingNotifications(app),
            isLocked = { DeviceState.isLocked(app) },
            scope = appScope,
            log = { message, error -> Log.w(TAG, message, error) },
            // Screen control stays off while a pairing could be approved (its screen may be showing).
            onPendingChanged = { ApprovalGuard.set(PairingCoordinator.GUARD_SOURCE, it) },
        )
    }

    private val mcpServerLazy = lazy {
        McpServer(
            registry = registry,
            tokens = tokenManager,
            policy = PolicyEngine(zone = { ZoneId.systemDefault() }),
            confirmations = confirmations,
            audit = audit,
            serverInfo = ServerInfo("phonebridge-android", BuildConfig.VERSION_NAME, "PhoneBridge (Android)"),
            instructions = AgentInstructions.TEXT,
            isAccessEnabled = { settings.state.value.accessEnabled },
            confirmationTimeoutMs = NotificationConfirmationProvider.DEFAULT_TIMEOUT_MS,
            globalSecurity = ::globalSecurity,
        )
    }

    val mcpServer: McpServer by mcpServerLazy

    /**
     * Device-wide security settings for the current call: the user's never-allow list plus
     * PhoneBridge itself (agents may never operate it), and one-time-code masking. Read per call, so
     * changes apply to the next tool call without a restart.
     */
    fun globalSecurity(): GlobalSecurity {
        val current = settings.state.value
        return GlobalSecurity(neverAllowApps = current.neverAllowApps + app.packageName, redactOneTimeCodes = current.redactOneTimeCodes)
    }

    private val _relayStatus = MutableStateFlow<RelayStatus>(RelayStatus.Stopped)
    private val _localServerStatus = MutableStateFlow<LocalServerStatus>(LocalServerStatus.Stopped)
    private val _serviceRunning = MutableStateFlow(false)

    /** Live relay connection status (Stopped when relay is disabled or service not running). */
    val relayStatus: StateFlow<RelayStatus> = _relayStatus.asStateFlow()

    val localServerStatus: StateFlow<LocalServerStatus> = _localServerStatus.asStateFlow()

    /** True while BridgeService is created and in the foreground. */
    val serviceRunning: StateFlow<Boolean> = _serviceRunning.asStateFlow()

    /** The MCP URL to show users for the relay (see [RelayUrls.publicMcpUrl]); null if not configured. */
    val publicRelayMcpUrl: String?
        get() = RelayUrls.publicMcpUrl(relayStatus.value, settings.state.value.relayUrl)

    /**
     * Tailscale on this phone (app installed, tailnet address, MagicDNS name) for the Tailscale route.
     * Refreshed by the transports and by the UI; a newly found MagicDNS name re-publishes the local URLs.
     */
    val tailscale: TailscaleMonitor by lazy { TailscaleMonitor(app, appScope, onNameFound = { transports.refreshLocalUrls() }) }

    /** MCP handler shared by both transports: per-request wake lock + internal-error safety net. */
    private val requestHandler: WakeLockedHandler by lazy { WakeLockedHandler(app) { mcpServer.handle(it) } }

    /** Runs the relay client and local server; driven by BridgeService. */
    internal val transports: TransportController by lazy {
        TransportController(
            scope = appScope,
            settings = { settings.state.value },
            relayFactory = CoreTransports.relayFactory(app, { identity }, requestHandler::handle, pairingHandler = { pairing }),
            localFactory = CoreTransports.localFactory(requestHandler::handle),
            lanAddresses = NetworkAddresses::nonLoopbackIpv4,
            tailnet = { tailscale.refresh() },
            onRelayStatus = ::updateRelayStatus,
            onLocalStatus = ::updateLocalServerStatus,
            log = { message, error -> Log.w(TAG, message, error) },
        )
    }

    init {
        try {
            Channels.createAll(app)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not create notification channels", e)
        }
        app.registerComponentCallbacks(FlushOnTrim(::flushUsage))
        appScope.launch {
            warmUp()
            warmUpAppActions()
        }
    }

    /**
     * Saves a `phonebridge://configure` link's relay settings (relay on, its URL and access key) and
     * (re)connects. The caller must have asked the user first ("Connect to relay <host>?"): the link
     * comes from outside the app.
     */
    fun applyConfigureLink(link: ConfigureLink) {
        settings.update {
            it.copy(relayEnabled = true, relayUrl = link.relayUrl, relayAccessKey = link.accessKey, relayFromDefault = false, defaultRelayDecided = true)
        }
        applySettings()
    }

    /**
     * First run of a build with a default relay (`BuildConfig.DEFAULT_RELAY_URL`): when nothing is
     * set up yet, turns the relay on with it and connects, so the phone is online without a relay
     * step. Decided once per install and never replaces a relay the user chose ([DefaultRelay]).
     * Call from the UI while the activity is visible (Android only lets a visible app start the
     * foreground service). Blocking (settings I/O).
     */
    fun applyDefaultRelayIfNeeded() {
        if (settings.state.value.defaultRelayDecided) return
        settings.update { DefaultRelay.applyOnFirstRun(it, BuildConfig.DEFAULT_RELAY_URL) }
        if (DefaultRelay.inUse(settings.state.value)) {
            Log.i(TAG, "First run: connecting to the default relay")
            applySettings()
        }
    }

    /** The user asked to go online with the build's default relay. Blocking, like [applyDefaultRelayIfNeeded]. */
    fun useDefaultRelay() {
        settings.update { DefaultRelay.use(it, BuildConfig.DEFAULT_RELAY_URL) }
        applySettings()
    }

    /** Persists pending usage counters in the background (service stop, memory trim). No-op if never loaded. */
    fun flushUsage() {
        if (!usageLazy.isInitialized()) return
        appScope.launch {
            try {
                usage.flush()
            } catch (e: Exception) {
                Log.w(TAG, "Could not save usage counters", e)
            }
        }
    }

    /**
     * First run (or first run after an update that added it): blocks installed banking, payment,
     * crypto, password-manager and authenticator apps for every agent, once. If the scan fails the
     * flag stays unset and it is retried on the next start. Blocking; runs during [warmUp].
     */
    private fun applySecurityDefaultsIfNeeded() {
        if (settings.state.value.securityDefaultsApplied) return
        val detected = SensitiveApps.suggest(app).map { it.packageName }
        settings.update { SecurityDefaults.apply(it, detected) }
        Log.i(TAG, "Security defaults applied: ${detected.size} sensitive apps blocked for agents")
    }

    /** Starts/stops/restarts BridgeService so that it matches current settings. Call after settings changes. */
    fun applySettings() {
        val current = settings.state.value
        if (TransportConfig.shouldRun(current)) {
            try {
                ContextCompat.startForegroundService(app, BridgeService.intent(app, BridgeService.ACTION_APPLY))
            } catch (e: IllegalStateException) {
                reportStartFailure(e)
            } catch (e: SecurityException) {
                reportStartFailure(e)
            }
        } else {
            if (_serviceRunning.value) sendToRunningService(BridgeService.ACTION_STOP, stopIfUndeliverable = true)
            _relayStatus.value = TransportConfig.idleRelayStatus(current)
            _localServerStatus.value = LocalServerStatus.Stopped
        }
    }

    /**
     * Global kill switch (persisted in settings, reflected in the service notification). Pausing
     * also stops every tool call still running (including ones waiting for an approval, an unlock or
     * the screen) and denies every confirmation prompt that is still open. Transports keep running
     * so agents get a clear "paused" error instead of a connection failure.
     */
    fun setAccessEnabled(enabled: Boolean) {
        settings.update { it.copy(accessEnabled = enabled) }
        // Nothing runs while paused: new calls see the setting, calls already running are stopped.
        if (!enabled) {
            cancelCalls(tokenId = null)
            confirmations.denyAll()
        }
        if (_serviceRunning.value) sendToRunningService(BridgeService.ACTION_REFRESH, stopIfUndeliverable = false)
    }

    /**
     * Revokes agent [id] and stops its tool calls still running; their approval prompts are denied.
     * Blocking (token store I/O): call from a background dispatcher. Throws if the store can't be
     * saved (the agent's running calls are stopped anyway).
     */
    fun revokeAgent(id: String) {
        try {
            tokenManager.revoke(id)
        } finally {
            cancelCalls(id)
        }
    }

    /** Deletes agent [id] (and its usage counters) and stops its tool calls still running. Blocking, like [revokeAgent]. */
    fun deleteAgent(id: String) {
        try {
            tokenManager.delete(id)
        } finally {
            cancelCalls(id)
        }
        usage.forget(id)
    }

    /**
     * Revokes every agent and stops every tool call still running; open approval prompts are denied.
     * Returns how many agents were newly revoked. Blocking, like [revokeAgent].
     */
    fun revokeAllAgents(): Int {
        try {
            return tokenManager.revokeAll()
        } finally {
            cancelCalls(tokenId = null)
            confirmations.denyAll()
        }
    }

    /** Stops [tokenId]'s running tool calls (all calls when null). No server yet means no calls to stop. */
    private fun cancelCalls(tokenId: String?) {
        if (!mcpServerLazy.isInitialized()) return
        val cancelled = mcpServer.cancelCalls(tokenId)
        if (cancelled > 0) Log.i(TAG, "Stopped $cancelled running tool call(s)")
    }

    internal fun updateRelayStatus(status: RelayStatus) {
        _relayStatus.value = status
    }

    internal fun updateLocalServerStatus(status: LocalServerStatus) {
        _localServerStatus.value = status
    }

    internal fun setServiceRunning(running: Boolean) {
        _serviceRunning.value = running
    }

    /** Platform status merged into `bridge_capabilities`. Never throws. */
    private fun extraStatus(): Map<String, Any?> {
        val current = settings.state.value
        return linkedMapOf(
            "accessibility_enabled" to (BridgeAccessibilityService.instance != null),
            "accessibility_problem" to BridgeAccessibilityService.healthCached(),
            "notification_access" to try {
                BridgeNotificationListener.isEnabled(app)
            } catch (e: Exception) {
                null
            },
            "phone_locked" to DeviceState.isLocked(app),
            "screen_on" to DeviceState.isScreenOn(app),
            "access_paused" to !current.accessEnabled,
            "relay" to StatusText.relayState(current.relayEnabled, relayStatus.value),
            "local_server" to StatusText.localState(current.localServerEnabled, localServerStatus.value),
            "android_version" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "app_version" to BuildConfig.VERSION_NAME,
            "app_plugins" to appPluginsStatus(),
        )
    }

    /** How many app plugins are in use (app_actions_list / app_action_run); local ones are unreviewed. */
    private fun appPluginsStatus(): Map<String, Any?> = try {
        val all = plugins.catalog().plugins
        linkedMapOf("plugins" to all.size, "local_unreviewed" to all.count { it.source == PluginSource.LOCAL })
    } catch (e: Exception) {
        mapOf("error" to "Could not load app plugins (${e.message ?: e.javaClass.simpleName})")
    }

    // The service is in the foreground, so plain startService() is allowed even from the background.
    private fun sendToRunningService(action: String, stopIfUndeliverable: Boolean) {
        try {
            app.startService(BridgeService.intent(app, action))
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Could not deliver $action to BridgeService", e)
            if (stopIfUndeliverable) app.stopService(BridgeService.intent(app, action))
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not deliver $action to BridgeService", e)
        }
    }

    private fun reportStartFailure(e: RuntimeException) {
        val message = if (Build.VERSION.SDK_INT >= 31 && e is ForegroundServiceStartNotAllowedException) {
            "Android blocked starting PhoneBridge in the background. Open the PhoneBridge app to start it."
        } else {
            "Could not start the PhoneBridge service: ${e.message ?: e.javaClass.simpleName}"
        }
        Log.w(TAG, message, e)
        reportServiceError(message)
    }

    /** Surfaces a service-level failure in the status of every enabled transport. */
    internal fun reportServiceError(message: String) {
        val current = settings.state.value
        if (TransportConfig.relay(current) != null) _relayStatus.value = RelayStatus.Error(message, retryInMs = null, fatal = true)
        if (TransportConfig.local(current) != null) _localServerStatus.value = LocalServerStatus.Error(message)
    }

    private fun warmUp() {
        // Each step is independent: a failure is logged and retried on first real use.
        listOf<Pair<String, () -> Any>>(
            "settings" to { settings },
            "security defaults" to ::applySecurityDefaultsIfNeeded,
            "identity" to { identity },
            "tokens" to { tokenManager },
            "usage" to { usage },
            "audit" to { audit },
            "app plugins" to { plugins.catalog() },
        ).forEach { (name, init) ->
            try {
                init()
            } catch (e: Exception) {
                Log.e(TAG, "Initializing $name failed", e)
            }
        }
    }

    /** Scans installed apps for their discovered actions (docs/PLUGINS.md §8), so agents' first calls find them ready. */
    private suspend fun warmUpAppActions() {
        try {
            actionsPlatform.warmUp { message, error -> Log.w(TAG, message, error) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Scanning installed apps failed", e)
        }
    }

    companion object {
        private const val TAG = "PhoneBridge/Graph"
        private const val TOKENS_FILE = "tokens.json"
        private const val AUDIT_FILE = "audit.jsonl"
        private const val USAGE_FILE = "usage.json"

        @Volatile
        private var _instance: BridgeGraph? = null

        val instance: BridgeGraph get() = _instance ?: error("BridgeGraph not initialized")

        fun init(app: Application) {
            if (_instance == null) synchronized(this) { if (_instance == null) _instance = BridgeGraph(app) }
        }

        fun get(context: Context): BridgeGraph {
            init(context.applicationContext as Application)
            return instance
        }
    }
}
