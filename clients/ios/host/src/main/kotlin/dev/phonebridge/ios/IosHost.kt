package dev.phonebridge.ios

import dev.phonebridge.core.audit.FileAuditLog
import dev.phonebridge.core.auth.FileTokenStore
import dev.phonebridge.core.auth.GrantDraft
import dev.phonebridge.core.auth.TokenManager
import dev.phonebridge.core.mcp.McpServer
import dev.phonebridge.core.mcp.ServerInfo
import dev.phonebridge.core.mcp.capabilitiesTool
import dev.phonebridge.core.oauth.LocalOAuth
import dev.phonebridge.core.plugins.ActionHealth
import dev.phonebridge.core.plugins.AppActionCatalog
import dev.phonebridge.core.plugins.FileActionHealthStore
import dev.phonebridge.core.plugins.PluginSource
import dev.phonebridge.core.plugins.appActionTools
import dev.phonebridge.core.policy.GlobalSecurity
import dev.phonebridge.core.policy.PolicyEngine
import dev.phonebridge.core.screen.ScreenStepRunner
import dev.phonebridge.core.screen.phoneUseTools
import dev.phonebridge.core.tools.ToolRegistry
import dev.phonebridge.core.transport.DeviceHello
import dev.phonebridge.core.transport.LocalHttpServer
import dev.phonebridge.core.transport.RelayClient
import dev.phonebridge.core.transport.RelayStatus
import dev.phonebridge.ios.apps.IosAppActions
import dev.phonebridge.ios.apps.LinkOpener
import dev.phonebridge.ios.host.BundledPlugins
import dev.phonebridge.ios.host.HostState
import dev.phonebridge.ios.host.IosAgentInstructions
import dev.phonebridge.ios.host.MacConfirmations
import dev.phonebridge.ios.host.MacPairingHandler
import dev.phonebridge.ios.inventory.AppInventory
import dev.phonebridge.ios.inventory.CachedInventory
import dev.phonebridge.ios.inventory.DevicectlInventory
import dev.phonebridge.ios.inventory.ProcessCommandRunner
import dev.phonebridge.ios.inventory.Pymobiledevice3Inventory
import dev.phonebridge.ios.inventory.SimctlInventory
import dev.phonebridge.ios.runner.AwtScreenshotMarker
import dev.phonebridge.ios.runner.RunnerClient
import dev.phonebridge.ios.runner.RunnerMonitor
import dev.phonebridge.ios.runner.RunnerScreenDriver
import dev.phonebridge.ios.tools.iosTools
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.ZoneId
import kotlin.system.exitProcess

const val HOST_VERSION = "0.1.0"

private const val CONFIRM_TIMEOUT_SECONDS = 60L

private const val USAGE = """Usage: phonebridge-ios-host (--simulator <udid> | --device <udid>) [--runner-url URL] [--relay URL] [--local-port N]
                              [--new-token NAME]

  --simulator <udid>   a booted iOS simulator (xcrun simctl list devices booted)
  --device <udid>      a paired iPhone in Developer Mode (xcrun devicectl list devices)
  --runner-url URL     the on-phone runner (default http://127.0.0.1:8101)
  --relay URL          connect to a PhoneBridge relay (access key from PB_RELAY_ACCESS_KEY, if it needs one)
  --local-port N       serve MCP on http://127.0.0.1:N/mcp for agents on this Mac (0 = any free port)
  --new-token NAME     print a token with full access for an agent that can't pair, then run

Environment: PB_RUNNER_SECRET (required: the secret the runner was started with; scripts/ios-host.sh sets it).
Pairing and approvals appear as dialogs on this Mac. State lives in ~/.phonebridge/ios/<udid>/."""

/** Command-line options (pure parsing, for tests). */
data class HostOptions(
    val udid: String,
    val simulator: Boolean,
    val runnerUrl: String = "http://127.0.0.1:8101",
    val relayUrl: String? = null,
    val localPort: Int? = null,
    val newToken: String? = null,
) {
    companion object {
        /** @throws IllegalArgumentException with what is wrong. */
        fun parse(args: List<String>): HostOptions {
            var udid: String? = null
            var simulator: Boolean? = null
            var runnerUrl = "http://127.0.0.1:8101"
            var relay: String? = null
            var port: Int? = null
            var newToken: String? = null
            var i = 0
            fun value(flag: String): String = args.getOrNull(++i)?.takeIf { !it.startsWith("--") } ?: throw IllegalArgumentException("$flag needs a value")
            while (i < args.size) {
                when (val a = args[i]) {
                    "--simulator", "--device" -> {
                        if (simulator != null) throw IllegalArgumentException("Pass one of --simulator or --device")
                        simulator = a == "--simulator"
                        udid = value(a)
                    }
                    "--runner-url" -> runnerUrl = value(a)
                    "--relay" -> relay = value(a)
                    "--local-port" -> port = value(a).toIntOrNull()?.takeIf { it in 0..65535 } ?: throw IllegalArgumentException("--local-port needs a port number")
                    "--new-token" -> newToken = value(a)
                    else -> throw IllegalArgumentException("Unknown option '$a'")
                }
                i++
            }
            if (udid == null || simulator == null) throw IllegalArgumentException("Pass --simulator <udid> or --device <udid>")
            if (relay == null && port == null) throw IllegalArgumentException("Pass --relay <url>, --local-port <port>, or both, so agents can reach the iPhone")
            return HostOptions(udid, simulator, runnerUrl, relay, port, newToken)
        }
    }
}

/**
 * The PhoneBridge iOS host (docs/IOS.md): the same core as the Android app (MCP server, tokens, grant, policy, audit,
 * relay client, local server, plugins, app actions, phone use) for one iPhone or simulator, with the app inventory from
 * Apple's tools and the screen through the on-phone runner. Usually started by scripts/ios-host.sh.
 */
fun main(args: Array<String>) {
    if (System.getProperty("java.awt.headless") == null) System.setProperty("java.awt.headless", "true")
    val options = try {
        HostOptions.parse(args.toList())
    } catch (e: IllegalArgumentException) {
        System.err.println("${e.message}\n\n$USAGE")
        exitProcess(2)
    }
    val secret = System.getenv("PB_RUNNER_SECRET")?.trim().orEmpty()
    if (secret.isEmpty()) {
        System.err.println("PB_RUNNER_SECRET is not set: start the host with scripts/ios-host.sh, which starts the runner with a new secret.")
        exitProcess(2)
    }
    val log: (String) -> Unit = System.err::println

    val state = HostState.forDevice(options.udid)
    val identity = state.identity()
    val tokens = TokenManager(FileTokenStore(state.tokensFile), identity.deviceId)
    val audit = FileAuditLog(state.auditFile)

    val client = RunnerClient(options.runnerUrl, secret)
    val baseInventory: AppInventory = when {
        options.simulator -> SimctlInventory(options.udid)
        ProcessCommandRunner.onPath("pymobiledevice3") -> Pymobiledevice3Inventory(options.udid)
        else -> DevicectlInventory(options.udid)
    }
    val inventory = CachedInventory(baseInventory)
    baseInventory.limits.infoPlist?.let { log("Note: $it") }

    lateinit var appActions: IosAppActions
    val driver = RunnerScreenDriver(client) { appActions.apps().map { it.bundleId } }
    val monitor = RunnerMonitor(driver)
    val links = LinkOpener(driver)
    val steps = ScreenStepRunner(driver)
    appActions = IosAppActions(inventory, links, monitor::unavailableReason, runSteps = { s, app, ctx -> steps.run(s, app, ctx) })

    val plugins = BundledPlugins.load(log)
    val registry = ToolRegistry()
    val catalog = AppActionCatalog(
        plugins = { plugins },
        platform = appActions,
        builtInTool = registry::get,
        health = ActionHealth(FileActionHealthStore(state.healthFile)),
    )
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    var relay: RelayClient? = null
    var local: LocalHttpServer? = null
    val deviceName = { monitor.status?.name ?: if (options.simulator) "the iOS simulator" else "your iPhone" }

    registry.register(
        capabilitiesTool(registry) {
            val status = monitor.status
            linkedMapOf(
                "platform" to "ios",
                "runner" to (monitor.unavailableReason ?: "connected"),
                "phone_locked" to status?.locked,
                "ios_version" to status?.osVersion,
                "device" to if (options.simulator) "simulator" else "iphone",
                "app_inventory" to inventory.source,
                "app_inventory_limits" to listOfNotNull(inventory.limits.infoPlist, inventory.limits.bundleFiles).distinct().ifEmpty { null },
                "relay" to relay?.status?.value?.let(::relayState),
                "local_server" to local?.let { if (it.isRunning) "running on port ${it.boundPort}" else "stopped" },
                "host_version" to HOST_VERSION,
                "app_plugins" to linkedMapOf("plugins" to plugins.plugins.size, "local_unreviewed" to plugins.plugins.count { it.source == PluginSource.LOCAL }),
            )
        },
    )
    registry.registerAll(iosTools(appActions, driver, links, catalog, inventory.source))
    registry.registerAll(appActionTools(catalog))
    try {
        registry.registerAll(phoneUseTools(driver, { id -> appActions.app(id)?.name }, AwtScreenshotMarker()))
    } catch (e: NotImplementedError) {
        log("Phone use (screen_observe, ui_tap…) isn't in this build of the core yet; the other tools work.")
    }

    val server = McpServer(
        registry = registry,
        tokens = tokens,
        policy = PolicyEngine(zone = { ZoneId.systemDefault() }),
        confirmations = MacConfirmations(CONFIRM_TIMEOUT_SECONDS),
        audit = audit,
        serverInfo = ServerInfo("phonebridge-ios", HOST_VERSION, "PhoneBridge (iPhone)"),
        instructions = IosAgentInstructions.TEXT,
        confirmationTimeoutMs = CONFIRM_TIMEOUT_SECONDS * 1000,
        // PhoneBridge's own runner is never an app agents may operate.
        globalSecurity = { GlobalSecurity(neverAllowApps = appActions.ownBundlesSeen(), redactOneTimeCodes = true) },
        appLabel = { id -> inventory.cached?.firstOrNull { it.bundleId == id }?.name },
    )
    val pairing = MacPairingHandler(tokens, deviceName, log = log)

    runBlocking {
        when (val s = monitor.refresh()) {
            is RunnerMonitor.State.Up -> log("Runner: ${s.status.name ?: "iPhone"} (${s.status.model ?: "?"}, iOS ${s.status.osVersion ?: "?"}).")
            is RunnerMonitor.State.Down -> log("Runner: ${s.reason}")
            RunnerMonitor.State.Unknown -> Unit
        }
    }
    monitor.start(scope)
    // Fill the inventory cache in the background, so the first agent request doesn't wait for it.
    scope.launch { runCatching { appActions.apps() }.onFailure { log("App list: ${it.message}") } }

    options.newToken?.let { name ->
        val grant = GrantDraft.forNewAgent().settings()
        val issued = tokens.issue(name, grant.scopes, grant.confirmPolicy, ttlMillis = null, appPolicy = grant.appPolicy, maxRisk = grant.maxRisk)
        println("Token for $name (shown once, full access): ${issued.plaintext}")
    }

    options.localPort?.let { port ->
        val oauth = LocalOAuth(pairing, identity.deriveKey(LocalOAuth.CLIENT_KEY_PURPOSE), deviceName = { deviceName() }, log = log)
        val server0 = LocalHttpServer(port = port, handler = server::handle, oauth = oauth)
        server0.start()
        local = server0
        Runtime.getRuntime().addShutdownHook(Thread { server0.stop() })
        println("Local MCP URL (agents on this Mac): http://127.0.0.1:${server0.boundPort}/mcp")
    }

    options.relayUrl?.let { url ->
        val client0 = RelayClient(
            relayBaseUrl = url,
            identity = identity,
            hello = DeviceHello("ios", HOST_VERSION, deviceName(), accessKey = System.getenv("PB_RELAY_ACCESS_KEY")?.trim()?.ifEmpty { null }),
            handler = server::handle,
            scope = scope,
            pairingHandler = pairing,
        )
        relay = client0
        scope.launch {
            var printed: String? = null
            client0.status.collect { s ->
                when (s) {
                    is RelayStatus.Connected -> {
                        val shown = s.connectUrl ?: s.mcpUrl
                        if (shown != printed) println("Connection URL (add it to your agent as an MCP server): $shown")
                        printed = shown
                    }
                    is RelayStatus.Error -> log("Relay: ${s.message}" + (s.retryInMs?.let { " (retrying in ${it / 1000} s)" } ?: ""))
                    else -> Unit
                }
            }
        }
        client0.start()
        Runtime.getRuntime().addShutdownHook(Thread { client0.stop() })
    }
    System.out.flush()
    log("PhoneBridge iOS host running for ${if (options.simulator) "simulator" else "iPhone"} ${options.udid}. Ctrl-C stops it.")
    runBlocking { awaitCancellation() }
}

private fun relayState(s: RelayStatus): String = when (s) {
    RelayStatus.Stopped -> "stopped"
    RelayStatus.Connecting -> "connecting"
    is RelayStatus.Connected -> "connected"
    is RelayStatus.Error -> "error: ${s.message}"
}
