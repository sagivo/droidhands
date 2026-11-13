package dev.phonebridge.core.transport

import dev.phonebridge.core.auth.DeviceIdentity
import dev.phonebridge.core.mcp.TransportRequest
import dev.phonebridge.core.mcp.TransportResponse
import dev.phonebridge.core.transport.RelayProtocol.string
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random

sealed interface RelayStatus {
    data object Stopped : RelayStatus
    data object Connecting : RelayStatus
    data class Connected(val mcpUrl: String) : RelayStatus

    /** [fatal] = will not retry (bad access key / unsupported protocol) until settings change. */
    data class Error(val message: String, val retryInMs: Long?, val fatal: Boolean = false) : RelayStatus
}

data class DeviceHello(
    val platform: String,
    val appVersion: String,
    val deviceName: String,
    val accessKey: String? = null,
) {
    // Never print the access key.
    override fun toString(): String =
        "DeviceHello(platform=$platform, appVersion=$appVersion, deviceName=$deviceName, accessKey=${if (accessKey == null) "absent" else "<redacted>"})"
}

/**
 * Outbound WebSocket to the relay (docs/PROTOCOL.md §3). [relayBaseUrl] is http(s)://host[:port][/prefix];
 * the client connects to ws(s)://…/v1/device. Sends hello, handles tunneled requests concurrently via
 * [handler] (on [scope]), answers pings, reconnects with exponential backoff + jitter (1 s → 60 s),
 * stops retrying on close codes 4001/4002.
 *
 * Details:
 * * Status goes Connecting → Connected(mcpUrl) on the relay's `welcome`, which also resets the
 *   backoff. Without a welcome within 20 s the connection is dropped and retried.
 * * Close codes 4001/4002, and an unusable [relayBaseUrl], give `Error(fatal = true,
 *   retryInMs = null)`; no retry happens until [start] is called again (e.g. after the user changes
 *   settings). The relay's `error` frame message, if any, is used as the status message.
 * * Tunneled requests run on [scope] with [Dispatchers.IO], because [handler] may block briefly.
 *   Responses go back on the connection the request came from; requests still running when that
 *   connection drops finish, but their responses are dropped (the relay has already failed them).
 *   [stop] cancels in-flight requests.
 * * Responses that would exceed the relay's 16 MiB frame limit are replaced by a 500 error.
 *
 * Lifecycle: [start] while running (connecting, connected or waiting to retry) is a no-op; [stop]
 * closes with 1000, cancels reconnects and sets Stopped; [start] after [stop] or after a fatal
 * error connects again. All methods are thread-safe.
 */
class RelayClient(
    private val relayBaseUrl: String,
    private val identity: DeviceIdentity,
    private val hello: DeviceHello,
    private val handler: suspend (TransportRequest) -> TransportResponse,
    private val scope: CoroutineScope,
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    private val lock = Any()
    private val state = MutableStateFlow<RelayStatus>(RelayStatus.Stopped)
    private val random = Random.Default
    private val wsClient: OkHttpClient by lazy {
        httpClient.newBuilder()
            .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    // Guarded by lock.
    private var running = false
    private var generation = 0L
    private var socket: WebSocket? = null
    private var attempt = 0
    private var jobs: CompletableJob? = null

    val status: StateFlow<RelayStatus> get() = state.asStateFlow()

    fun start() {
        synchronized(lock) {
            if (running) return
            if (RelayProtocol.deviceUrl(relayBaseUrl) == null) {
                state.value = RelayStatus.Error(
                    "Invalid relay URL. Enter an http:// or https:// address in the PhoneBridge connection settings.",
                    retryInMs = null,
                    fatal = true,
                )
                return
            }
            running = true
            attempt = 0
            jobs = SupervisorJob(scope.coroutineContext[Job])
            connectLocked()
        }
    }

    fun stop() {
        synchronized(lock) {
            running = false
            generation++
            socket?.close(RelayProtocol.CLOSE_NORMAL, "Device stopped")
            socket = null
            jobs?.cancel()
            jobs = null
            state.value = RelayStatus.Stopped
        }
    }

    /** Must hold [lock]. */
    private fun connectLocked() {
        val url = RelayProtocol.deviceUrl(relayBaseUrl) ?: return
        val gen = ++generation
        state.value = RelayStatus.Connecting
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "PhoneBridge/${hello.appVersion} (${hello.platform})")
            .build()
        socket = wsClient.newWebSocket(request, Listener(gen))
    }

    /** Must hold [lock]. */
    private fun launchLocked(block: suspend CoroutineScope.() -> Unit): Job? {
        val parent = jobs ?: return null
        return scope.launch(parent + Dispatchers.IO + CoroutineName("phonebridge-relay"), block = block)
    }

    private fun isCurrent(gen: Long): Boolean = synchronized(lock) { running && gen == generation }

    private inner class Listener(private val gen: Long) : WebSocketListener() {
        private val ended = AtomicBoolean(false)
        private val welcomed = AtomicBoolean(false)

        @Volatile
        private var relayError: String? = null
        private var welcomeTimeout: Job? = null

        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(lock) {
                if (!running || gen != generation) {
                    webSocket.close(RelayProtocol.CLOSE_NORMAL, "Stale connection")
                    return
                }
                webSocket.send(RelayProtocol.hello(identity, hello).toString())
                welcomeTimeout = launchLocked {
                    delay(WELCOME_TIMEOUT_MS)
                    if (!welcomed.get() && isCurrent(gen)) {
                        relayError = "The relay did not answer the handshake in time"
                        webSocket.cancel()
                    }
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!isCurrent(gen)) return
            val frame = try {
                json.parseToJsonElement(text) as? JsonObject
            } catch (e: Exception) {
                null
            } ?: return
            when (frame.string("type")) {
                "welcome" -> onWelcome(frame)
                "error" -> relayError = listOfNotNull(frame.string("message"), frame.string("code")?.let { "($it)" })
                    .joinToString(" ").ifEmpty { null }
                "request" -> onRequest(webSocket, frame)
                "ping" -> webSocket.send(RelayProtocol.pong(frame["t"]).toString())
                else -> Unit // pong and unknown types are ignored (forward compatibility).
            }
        }

        private fun onWelcome(frame: JsonObject) {
            synchronized(lock) {
                if (!running || gen != generation) return
                welcomed.set(true)
                welcomeTimeout?.cancel()
                attempt = 0
                val mcpUrl = frame.string("mcpUrl") ?: RelayProtocol.mcpUrl(relayBaseUrl).orEmpty()
                state.value = RelayStatus.Connected(mcpUrl)
            }
        }

        private fun onRequest(webSocket: WebSocket, frame: JsonObject) {
            val id = frame.string("id") ?: return
            synchronized(lock) {
                if (!running || gen != generation) return
                launchLocked {
                    val response = respond(frame)
                    webSocket.send(encodeResponse(id, response))
                }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(RelayProtocol.CLOSE_NORMAL, null)
            ended(code, reason, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = ended(code, reason, null)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val message = when {
                relayError != null -> relayError
                response != null -> "The relay refused the connection (HTTP ${response.code})"
                else -> "Could not reach the relay: ${t.message ?: t.javaClass.simpleName}"
            }
            ended(null, null, message)
        }

        private fun ended(code: Int?, reason: String?, failure: String?) {
            if (!ended.compareAndSet(false, true)) return
            synchronized(lock) {
                welcomeTimeout?.cancel()
                if (!running || gen != generation) return
                socket = null
                if (code == RelayProtocol.CLOSE_UNAUTHORIZED || code == RelayProtocol.CLOSE_PROTOCOL_VERSION) {
                    running = false
                    generation++
                    jobs?.cancel()
                    jobs = null
                    state.value = RelayStatus.Error(fatalMessage(code, relayError ?: reason?.ifBlank { null }), retryInMs = null, fatal = true)
                    return
                }
                val message = failure ?: relayError ?: "The relay closed the connection (code $code${reason?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""})"
                val delayMs = RelayProtocol.backoffDelayMs(attempt, random)
                attempt = (attempt + 1).coerceAtMost(MAX_ATTEMPT_EXPONENT)
                state.value = RelayStatus.Error(message, retryInMs = delayMs)
                val expected = ++generation // Invalidate this connection; the retry checks nothing else ran.
                launchLocked {
                    delay(delayMs)
                    synchronized(lock) {
                        if (running && generation == expected) connectLocked()
                    }
                }
            }
        }
    }

    private suspend fun respond(frame: JsonObject): TransportResponse {
        val method = frame.string("method")?.uppercase() ?: "POST"
        if (method != "POST") {
            return RelayProtocol.errorResponse(405, -32600, "Use POST", mapOf("allow" to "POST"))
        }
        return try {
            handler(RelayProtocol.transportRequest(frame))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RelayProtocol.errorResponse(500, -32603, "Internal error")
        }
    }

    private fun encodeResponse(id: String, response: TransportResponse): String {
        val encoded = RelayProtocol.response(id, response).toString()
        if (RelayProtocol.utf8Length(encoded) <= RelayProtocol.MAX_FRAME_BYTES) return encoded
        val tooLarge = RelayProtocol.errorResponse(500, -32603, "Response too large for the relay (over 16 MiB). Request less data.")
        return RelayProtocol.response(id, tooLarge).toString()
    }

    private companion object {
        const val PING_INTERVAL_SECONDS = 20L
        const val WELCOME_TIMEOUT_MS = 20_000L
        const val MAX_ATTEMPT_EXPONENT = 10

        fun fatalMessage(code: Int, detail: String?): String {
            val base = if (code == RelayProtocol.CLOSE_UNAUTHORIZED) {
                "The relay rejected this device (check the relay access key in the PhoneBridge connection settings)"
            } else {
                "The relay does not support this app's protocol version (update the app or the relay)"
            }
            return if (detail != null) "$base: $detail" else base
        }

        val json = Json { ignoreUnknownKeys = true }
    }
}
