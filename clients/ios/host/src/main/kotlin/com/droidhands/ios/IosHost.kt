package com.droidhands.ios

import com.droidhands.core.audit.FileAuditLog
import com.droidhands.core.auth.FileTokenStore
import com.droidhands.core.auth.GrantDraft
import com.droidhands.core.auth.TokenManager
import com.droidhands.core.mcp.McpServer
import com.droidhands.core.mcp.ServerInfo
import com.droidhands.core.mcp.capabilitiesTool
import com.droidhands.core.oauth.LocalOAuth
import com.droidhands.core.plugins.ActionHealth
import com.droidhands.core.plugins.AppActionCatalog
import com.droidhands.core.plugins.FileActionHealthStore
import com.droidhands.core.plugins.PluginSource
import com.droidhands.core.plugins.appActionTools
import com.droidhands.core.policy.GlobalSecurity
import com.droidhands.core.policy.PolicyEngine
import com.droidhands.core.screen.ScreenStepRunner
import com.droidhands.core.screen.phoneUseTools
import com.droidhands.core.tools.ToolRegistry
import com.droidhands.core.transport.DeviceHello
import com.droidhands.core.transport.LocalHttpServer
import com.droidhands.core.transport.RelayClient
import com.droidhands.core.transport.RelayStatus
import com.droidhands.ios.apps.IosAppActions
import com.droidhands.ios.apps.LinkOpener
import com.droidhands.ios.host.BundledPlugins
import com.droidhands.ios.host.HostState
import com.droidhands.ios.host.IosAgentInstructions
import com.droidhands.ios.host.MacConfirmations
import com.droidhands.ios.host.MacPairingHandler
import com.droidhands.ios.inventory.AppInventory
import com.droidhands.ios.inventory.CachedInventory
import com.droidhands.ios.inventory.DevicectlInventory
import com.droidhands.ios.inventory.ProcessCommandRunner
import com.droidhands.ios.inventory.Pymobiledevice3Inventory
import com.droidhands.ios.inventory.SimctlInventory
import com.droidhands.ios.runner.AwtScreenshotMarker
import com.droidhands.ios.runner.RunnerClient
import com.droidhands.ios.runner.RunnerMonitor
import com.droidhands.ios.runner.RunnerScreenDriver
import com.droidhands.ios.tools.iosTools
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

private const val USAGE = """Usage: droidhands-ios-host (--simulator <udid> | --device <udid>) [--runner-url URL] [--relay URL] [--local-port N]
                              [--new-token NAME]

  --simulator <udid>   a booted iOS simulator (xcrun simctl list devices booted)
  --device <udid>      a paired iPhone in Developer Mode (xcrun devicectl list devices)
  --runner-url URL     the on-phone runner (default http://127.0.0.1:8101)
  --relay URL          connect to a DroidHands relay (access key from PB_RELAY_ACCESS_KEY, if it needs one)
  --local-port N       serve MCP on http://127.0.0.1:N/mcp for agents on this Mac (0 = any free port)
  --new-token NAME     print a token with full access for an agent that can't pair, then run

Environment: PB_RUNNER_SECRET (required: the secret the runner was started with; scripts/ios-host.sh sets it).
Pairing and approvals appear as dialogs on this Mac. State lives in ~/.droidhands/ios/<udid>/."""

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
 * The DroidHands iOS host (docs/IOS.md): the same core as the Android app (MCP server, tokens, grant, policy, audit,
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
    val appLabel: suspend (String) -> String? = { id -> appActions.app(id)?.name }
    val steps = ScreenStepRunner(driver, appLabel)
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
    registry.registerAll(phoneUseTools(driver, appLabel, AwtScreenshotMarker()))

    val server = McpServer(
        registry = registry,
        tokens = tokens,
        policy = PolicyEngine(zone = { ZoneId.systemDefault() }),
        confirmations = MacConfirmations(CONFIRM_TIMEOUT_SECONDS),
        audit = audit,
        serverInfo = ServerInfo("droidhands-ios", HOST_VERSION, "DroidHands (iPhone)"),
        instructions = IosAgentInstructions.TEXT,
        confirmationTimeoutMs = CONFIRM_TIMEOUT_SECONDS * 1000,
        // DroidHands's own runner is never an app agents may operate.
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
    log("DroidHands iOS host running for ${if (options.simulator) "simulator" else "iPhone"} ${options.udid}. Ctrl-C stops it.")
    runBlocking { awaitCancellation() }
}

private fun relayState(s: RelayStatus): String = when (s) {
    RelayStatus.Stopped -> "stopped"
    RelayStatus.Connecting -> "connecting"
    is RelayStatus.Connected -> "connected"
    is RelayStatus.Error -> "error: ${s.message}"
}
