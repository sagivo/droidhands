package com.droidhands.android.service

import com.droidhands.android.graph.LocalServerStatus
import com.droidhands.android.settings.BridgeSettings
import com.droidhands.android.settings.LocalBind
import com.droidhands.core.transport.RelayStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.IOException

/** A relay connection (production: wraps [com.droidhands.core.transport.RelayClient]). */
interface RelayTransport {
    val status: StateFlow<RelayStatus>
    fun start()
    fun stop()
}

/** A local MCP server (production: wraps [com.droidhands.core.transport.LocalHttpServer]). */
interface LocalTransport {
    /** Binds and starts accepting; throws [IOException] if the port can't be bound. */
    fun start()
    fun stop()
    val boundPort: Int
}

/** Creates a relay transport whose request handling runs on [scope] (cancelled when it stops). */
fun interface RelayTransportFactory {
    fun create(config: RelayConfig, scope: CoroutineScope): RelayTransport
}

fun interface LocalTransportFactory {
    fun create(config: LocalConfig): LocalTransport
}

/**
 * Owns the running transports and reconciles them with [settings].
 *
 * Every operation is queued and executed one at a time, in submission order, on [scope]. That keeps
 * start/stop sequences from successive BridgeService instances, settings changes and network
 * callbacks from interleaving (e.g. a new local server binding the port before the old one closed).
 * The public methods never block and are safe to call from any thread.
 *
 * Transports only run while the controller is *active*: [apply] activates it, [stopAll]
 * deactivates it, and [restartRelay]/[retryRelay]/[refreshLocalUrls] are ignored while inactive.
 *
 * A local server that listens on the tailnet only ([LocalBind.TAILSCALE]) binds the phone's current
 * tailnet address: [refreshLocalUrls] rebinds it when that address appears or changes, and while
 * there is none the server waits ([LocalServerStatus.WaitingForTailscale]) instead of listening.
 */
class TransportController(
    private val scope: CoroutineScope,
    private val settings: () -> BridgeSettings,
    private val relayFactory: RelayTransportFactory,
    private val localFactory: LocalTransportFactory,
    /** Non-loopback IPv4 addresses of this device (only queried when the server binds all interfaces). */
    private val lanAddresses: () -> List<String>,
    /** The phone's tailnet address now, or null (only queried for [LocalBind.TAILSCALE]). Blocking is fine. */
    private val tailnet: () -> TailnetAddress? = { null },
    private val onRelayStatus: (RelayStatus) -> Unit,
    private val onLocalStatus: (LocalServerStatus) -> Unit,
    private val log: (message: String, error: Throwable?) -> Unit = { _, _ -> },
) {
    private enum class Command { APPLY, RESTART_RELAY, RETRY_RELAY, RETRY_STALLED_RELAY, REFRESH_LOCAL_URLS, STOP_ALL }

    private class RunningRelay(
        val config: RelayConfig,
        val transport: RelayTransport,
        val scope: CoroutineScope,
        val statusJob: Job,
    )

    private class RunningLocal(val config: LocalConfig, val transport: LocalTransport)

    private val commands = Channel<Command>(Channel.UNLIMITED)

    // Confined to the command loop; @Volatile only for the diagnostic getters below.
    @Volatile private var active = false
    @Volatile private var relay: RunningRelay? = null
    @Volatile private var local: RunningLocal? = null

    /** Config of the running relay client, or null. */
    val relayConfig: RelayConfig? get() = relay?.config

    /** Config of the running local server, or null. */
    val localConfig: LocalConfig? get() = local?.config

    init {
        scope.launch {
            for (command in commands) {
                try {
                    execute(command)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("Transport command $command failed", e)
                }
            }
        }
    }

    /** Activates the controller and starts/stops/restarts transports to match current settings. */
    fun apply() = submit(Command.APPLY)

    /** Reconnects the relay now (e.g. the default network came back), unless it stopped for a fatal reason. */
    fun restartRelay() = submit(Command.RESTART_RELAY)

    /**
     * Reconnects the relay now if it is waiting to retry after a failure (the screen turned on, the network
     * changed, a wake-up alarm fired) instead of waiting out the client's backoff, whose timer stalls while the
     * CPU sleeps. With [alsoWhileConnecting] a connection attempt still in progress is restarted too (the
     * wake-up alarm found it stuck). No-op while connected, stopped, or after a fatal error.
     */
    fun retryRelay(alsoWhileConnecting: Boolean = false) =
        submit(if (alsoWhileConnecting) Command.RETRY_STALLED_RELAY else Command.RETRY_RELAY)

    /**
     * Re-publishes the local server URLs (the device's IP addresses may have changed); a tailnet-only
     * server is rebound, started or stopped to follow the tailnet address.
     */
    fun refreshLocalUrls() = submit(Command.REFRESH_LOCAL_URLS)

    /** Stops every transport and deactivates the controller. */
    fun stopAll() = submit(Command.STOP_ALL)

    private fun submit(command: Command) {
        commands.trySend(command)
    }

    private suspend fun execute(command: Command) {
        when (command) {
            Command.APPLY -> {
                active = true
                reconcile()
            }
            Command.RESTART_RELAY -> if (active) restartRelayNow()
            Command.RETRY_RELAY -> if (active) retryRelayNow(alsoWhileConnecting = false)
            Command.RETRY_STALLED_RELAY -> if (active) retryRelayNow(alsoWhileConnecting = true)
            Command.REFRESH_LOCAL_URLS -> if (active) refreshLocal()
            Command.STOP_ALL -> {
                active = false
                stopRelay(publishIdle = true)
                stopLocal()
            }
        }
    }

    private suspend fun reconcile() {
        val current = settings()

        val wantedRelay = TransportConfig.relay(current)
        if (relay?.config != wantedRelay) {
            stopRelay(publishIdle = wantedRelay == null)
            if (wantedRelay != null) startRelay(wantedRelay)
        } else if (wantedRelay == null) {
            onRelayStatus(TransportConfig.idleRelayStatus(current))
        }

        reconcileLocal(current)
    }

    private fun reconcileLocal(current: BridgeSettings) {
        val tailnetNow = if (current.localServerEnabled && current.localServerBind == LocalBind.TAILSCALE) tailnet() else null
        val wanted = TransportConfig.local(current, tailnetNow?.address)
        if (local?.config == wanted) {
            if (wanted != null) publishLocalRunning(tailnetNow)
            return
        }
        val waiting = wanted != null && wanted.bindAddress == null
        stopLocal(publishStopped = !waiting)
        when {
            wanted == null -> Unit
            waiting -> onLocalStatus(LocalServerStatus.WaitingForTailscale)
            else -> startLocal(wanted, tailnetNow)
        }
    }

    private fun refreshLocal() {
        val current = settings()
        if (current.localServerBind == LocalBind.TAILSCALE) reconcileLocal(current) else publishLocalRunning(null)
    }

    private fun startRelay(config: RelayConfig) {
        val parent = scope.coroutineContext[Job]
        val relayScope = CoroutineScope(scope.coroutineContext + SupervisorJob(parent))
        var created: RelayTransport? = null
        val transport = try {
            relayFactory.create(config, relayScope).also {
                created = it
                it.start()
            }
        } catch (e: Exception) {
            try {
                created?.stop()
            } catch (stopError: Exception) {
                log("Relay client failed to stop after a failed start", stopError)
            }
            relayScope.cancel()
            log("Relay client failed to start", e)
            onRelayStatus(RelayStatus.Error("Could not start the relay connection: ${e.message ?: e.javaClass.simpleName}", null, fatal = true))
            return
        }
        val statusJob = relayScope.launch { transport.status.collect { onRelayStatus(it) } }
        relay = RunningRelay(config, transport, relayScope, statusJob)
    }

    private suspend fun stopRelay(publishIdle: Boolean) {
        val running = relay ?: run {
            if (publishIdle) onRelayStatus(TransportConfig.idleRelayStatus(settings()))
            return
        }
        relay = null
        // Stop forwarding status first so a late emission can't overwrite the idle status below.
        running.statusJob.cancelAndJoin()
        try {
            running.transport.stop()
        } catch (e: Exception) {
            log("Relay client failed to stop cleanly", e)
        }
        running.scope.cancel()
        if (publishIdle) onRelayStatus(TransportConfig.idleRelayStatus(settings()))
    }

    private suspend fun restartRelayNow() {
        val running = relay ?: return
        val status = running.transport.status.value
        if (status is RelayStatus.Error && status.fatal) return
        stopRelay(publishIdle = false)
        startRelay(running.config)
    }

    private suspend fun retryRelayNow(alsoWhileConnecting: Boolean) {
        val running = relay ?: return
        val waiting = when (val status = running.transport.status.value) {
            is RelayStatus.Error -> !status.fatal
            RelayStatus.Connecting -> alsoWhileConnecting
            else -> false
        }
        if (!waiting) return
        stopRelay(publishIdle = false)
        startRelay(running.config)
    }

    private fun startLocal(config: LocalConfig, tailnetNow: TailnetAddress?) {
        val transport = try {
            localFactory.create(config).also { it.start() }
        } catch (e: IOException) {
            log("Local server failed to bind", e)
            val reason = e.message ?: "bind failed"
            onLocalStatus(
                LocalServerStatus.Error(
                    if (config.bind == LocalBind.TAILSCALE) {
                        "Couldn't listen on your Tailscale address, port ${config.port} ($reason). DroidHands tries again when " +
                            "Tailscale reconnects, or choose another port in DroidHands settings."
                    } else {
                        "Port ${config.port} is unavailable ($reason). Choose another port in DroidHands settings."
                    },
                ),
            )
            return
        } catch (e: Exception) {
            log("Local server failed to start", e)
            onLocalStatus(LocalServerStatus.Error("Could not start the local server: ${e.message ?: e.javaClass.simpleName}"))
            return
        }
        local = RunningLocal(config, transport)
        publishLocalRunning(tailnetNow)
    }

    private fun stopLocal(publishStopped: Boolean = true) {
        val running = local
        local = null
        if (running != null) {
            try {
                running.transport.stop()
            } catch (e: Exception) {
                log("Local server failed to stop cleanly", e)
            }
        }
        if (publishStopped) onLocalStatus(LocalServerStatus.Stopped)
    }

    /** [tailnetNow]: the tailnet address just looked up, if any (its MagicDNS name goes into the URL). */
    private fun publishLocalRunning(tailnetNow: TailnetAddress?) {
        val running = local ?: return
        val port = try {
            running.transport.boundPort.takeIf { it > 0 }
        } catch (e: Exception) {
            null
        } ?: running.config.port
        val bind = running.config.bind
        val addresses = if (bind == LocalBind.ALL_INTERFACES) lanAddresses() else emptyList()
        // The name only counts for the address the server is bound to.
        val tailnetHost = tailnetNow?.takeIf { it.address == running.config.tailnet }?.host ?: running.config.tailnet
        onLocalStatus(LocalServerStatus.Running(TransportConfig.localUrls(port, bind, addresses, tailnetHost)))
    }
}
