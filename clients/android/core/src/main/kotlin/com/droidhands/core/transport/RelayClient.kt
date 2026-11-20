package com.droidhands.core.transport

import com.droidhands.core.auth.DeviceIdentity
import com.droidhands.core.mcp.TransportRequest
import com.droidhands.core.mcp.TransportResponse
import com.droidhands.core.transport.RelayProtocol.long
import com.droidhands.core.transport.RelayProtocol.string
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
    /** [connectUrl]: device connector URL `{relay}/d/<deviceId>/mcp` from welcome (null for old relays). */
    data class Connected(val mcpUrl: String, val connectUrl: String? = null) : RelayStatus

    /**
     * [fatal] = will not retry (bad access key / unsupported protocol / signed out) until settings change.
     * [code] = the relay's error code (docs/PROTOCOL.md §3.1), e.g. [ACCOUNT_REQUIRED], when there is one.
     */
    data class Error(val message: String, val retryInMs: Long?, val fatal: Boolean = false, val code: String? = null) : RelayStatus

    companion object {
        /** The relay needs a signed-in DroidHands account (or this one is signed out). */
        const val ACCOUNT_REQUIRED = "account_required"
    }
}

/** What the app's account gave for one connection attempt (docs/PROTOCOL.md §3.1, `hello.accountToken`). */
sealed interface RelayAccountToken {
    data class Token(val value: String) : RelayAccountToken {
        override fun toString(): String = "Token(***)"
    }

    /** Nobody is signed in (or the session ended): don't connect until they sign in. */
    data object SignedOut : RelayAccountToken

    /** The API server can't be reached right now: retry with backoff. */
    data class Unavailable(val message: String) : RelayAccountToken
}

data class DeviceHello(
    val platform: String,
    val appVersion: String,
    val deviceName: String,
    val accessKey: String? = null,
    /** The signed-in account's short-lived relay token (`hello.accountToken`, docs/PROTOCOL.md §3.1). */
    val accountToken: String? = null,
) {
    // Never print the access key or the account token.
    override fun toString(): String =
        "DeviceHello(platform=$platform, appVersion=$appVersion, deviceName=$deviceName, " +
            "accessKey=${if (accessKey == null) "absent" else "<redacted>"}, accountToken=${if (accountToken == null) "absent" else "<redacted>"})"
}

/**
 * Outbound WebSocket to the relay (docs/PROTOCOL.md §3). [relayBaseUrl] is http(s)://host[:port][/prefix];
 * the client connects to ws(s)://…/v1/device?deviceId=<id> (the parameter lets relays such as the
 * Cloudflare one route before the hello). Sends hello, then the `{"type":"hb"}` heartbeat every
 * [heartbeatIntervalMs] once welcomed (the `hb_ack` answer is optional), handles tunneled requests concurrently via
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
 *   [stop] cancels in-flight requests, so their responses are dropped too. Cancelling a request does
 *   not stop a tool call that already began: [com.droidhands.core.mcp.McpServer] runs each in a job
 *   of its own and lets it finish (and audits it).
 * * Responses that would exceed the relay's 16 MiB frame limit are replaced by a 500 error.
 * * One-tap pairing (docs/PROTOCOL.md §5.3): `pair_request` frames go to [pairingHandler] and its
 *   decision is sent back as `pair_response`; prompts are cancelled (and nothing is sent) on
 *   `pair_cancel`, at the request's `expiresAt`, or when the connection drops; `pair_complete` and
 *   `pair_abandoned` reach [PairingHandler.onPairFinished]. Without a handler every request is
 *   answered `busy`. See [RelayPairings]. The frame's `expiresAt` is relay time: the clock offset is
 *   taken from the welcome's `serverTime` (relay clock minus [clock]), and the [PairingRequest] the
 *   handler gets carries `expiresAt` converted to [clock]'s time base.
 * * The welcome's `connectUrl` (one-tap connector URL) is reported in [RelayStatus.Connected].
 * * With [accountToken] (a relay that needs a DroidHands account), it is asked for a token before every
 *   connection attempt and the token goes into the hello. [RelayAccountToken.SignedOut], or the relay's
 *   `account_required`, gives a fatal `Error(code = account_required)`; [RelayAccountToken.Unavailable]
 *   retries with backoff like a network failure.
 *
 * Lifecycle: [start] while running (connecting, connected or waiting to retry) is a no-op; [stop]
 * closes with 1000, cancels reconnects and open pairing prompts and sets Stopped; [start] after
 * [stop] or after a fatal error connects again. All methods are thread-safe.
 */
class RelayClient(
    private val relayBaseUrl: String,
    private val identity: DeviceIdentity,
    private val hello: DeviceHello,
    private val handler: suspend (TransportRequest) -> TransportResponse,
    private val scope: CoroutineScope,
    private val httpClient: OkHttpClient = OkHttpClient(),
    /** Handles pair_request / pair_complete / pair_abandoned / pair_cancel frames; null answers every request "busy". */
    private val pairingHandler: PairingHandler? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    /** How often a welcomed connection sends the `hb` heartbeat (docs/PROTOCOL.md §3.4). */
    private val heartbeatIntervalMs: Long = RelayProtocol.HEARTBEAT_INTERVAL_MS,
    /** The account token for each connection attempt; null for relays that need no account. */
    private val accountToken: (suspend () -> RelayAccountToken)? = null,
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
                    "Invalid relay URL. Enter an http:// or https:// address in the DroidHands connection settings.",
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
        val gen = ++generation
        state.value = RelayStatus.Connecting
        val provider = accountToken
        if (provider == null) {
            openLocked(gen, hello)
            return
        }
        launchLocked {
            val result = try {
                provider()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RelayAccountToken.Unavailable("Couldn't get your account ready: ${e.message ?: e.javaClass.simpleName}")
            }
            synchronized(lock) {
                if (!running || gen != generation) return@launchLocked
                when (result) {
                    is RelayAccountToken.Token -> openLocked(gen, hello.copy(accountToken = result.value))
                    RelayAccountToken.SignedOut -> failFatalLocked(RelayStatus.Error(SIGN_IN_MESSAGE, null, fatal = true, code = RelayStatus.ACCOUNT_REQUIRED))
                    is RelayAccountToken.Unavailable -> retryLaterLocked(result.message)
                }
            }
        }
    }

    /** Must hold [lock]. Opens the socket for attempt [gen], which sends [helloNow] once open. */
    private fun openLocked(gen: Long, helloNow: DeviceHello) {
        val url = RelayProtocol.deviceUrl(relayBaseUrl, identity.deviceId) ?: return
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "DroidHands/${hello.appVersion} (${hello.platform})")
            .build()
        socket = wsClient.newWebSocket(request, Listener(gen, helloNow))
    }

    /** Must hold [lock]. Stops for good (until [start]) with [error]. */
    private fun failFatalLocked(error: RelayStatus.Error) {
        running = false
        generation++
        socket = null
        jobs?.cancel()
        jobs = null
        state.value = error
    }

    /** Must hold [lock]. Reports [message] and connects again after the backoff delay. */
    private fun retryLaterLocked(message: String) {
        socket = null
        val delayMs = RelayProtocol.backoffDelayMs(attempt, random)
        attempt = (attempt + 1).coerceAtMost(MAX_ATTEMPT_EXPONENT)
        state.value = RelayStatus.Error(message, retryInMs = delayMs)
        val expected = ++generation // Invalidate this attempt; the retry checks nothing else ran.
        launchLocked {
            delay(delayMs)
            synchronized(lock) {
                if (running && generation == expected) connectLocked()
            }
        }
    }

    /** Must hold [lock]. */
    private fun launchLocked(start: CoroutineStart = CoroutineStart.DEFAULT, block: suspend CoroutineScope.() -> Unit): Job? {
        val parent = jobs ?: return null
        return scope.launch(parent + Dispatchers.IO + CoroutineName("droidhands-relay"), start, block)
    }

    /** Launches for connection [gen] only while it is current. */
    private fun launchFor(gen: Long, start: CoroutineStart, block: suspend CoroutineScope.() -> Unit): Job? =
        synchronized(lock) { if (running && gen == generation) launchLocked(start, block) else null }

    private fun isCurrent(gen: Long): Boolean = synchronized(lock) { running && gen == generation }

    private inner class Listener(private val gen: Long, private val helloNow: DeviceHello) : WebSocketListener() {
        private val ended = AtomicBoolean(false)
        private val welcomed = AtomicBoolean(false)

        @Volatile
        private var relayError: String? = null

        /** The `code` of the relay's error frame, if it sent one. */
        @Volatile
        private var relayErrorCode: String? = null
        private var welcomeTimeout: Job? = null
        private var heartbeat: Job? = null

        /** Relay clock minus local clock, from the welcome's `serverTime` (0 until then). */
        @Volatile
        private var clockOffsetMs = 0L

        @Volatile
        private var webSocket: WebSocket? = null

        private val pairings = RelayPairings(
            handler = pairingHandler,
            send = { text -> webSocket?.send(text) ?: false },
            launch = { start, block -> launchFor(gen, start, block) },
            relayNow = { clock() + clockOffsetMs },
            localNow = clock,
        )

        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(lock) {
                if (!running || gen != generation) {
                    webSocket.close(RelayProtocol.CLOSE_NORMAL, "Stale connection")
                    return
                }
                this.webSocket = webSocket
                webSocket.send(RelayProtocol.hello(identity, helloNow).toString())
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
                "error" -> {
                    relayErrorCode = frame.string("code")
                    relayError = listOfNotNull(frame.string("message"), frame.string("code")?.let { "($it)" })
                        .joinToString(" ").ifEmpty { null }
                }
                "request" -> onRequest(webSocket, frame)
                "ping" -> webSocket.send(RelayProtocol.pong(frame["t"]).toString())
                "pair_request" -> pairings.onRequest(frame)
                "pair_cancel" -> pairings.onCancel(frame)
                "pair_complete" -> pairings.onFinished(frame, completed = true)
                "pair_abandoned" -> pairings.onFinished(frame, completed = false)
                // hb_ack is optional (older relays never send it); liveness comes from WebSocket pings.
                "hb_ack" -> Unit
                else -> Unit // pong and unknown types are ignored (forward compatibility).
            }
        }

        private fun onWelcome(frame: JsonObject) {
            synchronized(lock) {
                if (!running || gen != generation) return
                welcomed.set(true)
                welcomeTimeout?.cancel()
                attempt = 0
                val socket = webSocket
                // Only after the welcome: before it, a relay would take the frame for the hello.
                if (heartbeat == null) heartbeat = launchLocked {
                    while (true) {
                        delay(heartbeatIntervalMs)
                        if (!isCurrent(gen) || socket?.send(RelayProtocol.HEARTBEAT_FRAME) != true) break
                    }
                }
                frame.long("serverTime")?.let { clockOffsetMs = it - clock() }
                val mcpUrl = frame.string("mcpUrl") ?: RelayProtocol.mcpUrl(relayBaseUrl).orEmpty()
                val connectUrl = frame.string("connectUrl")?.takeIf { it.isNotBlank() }
                state.value = RelayStatus.Connected(mcpUrl, connectUrl)
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
            pairings.cancelAll()
            synchronized(lock) {
                welcomeTimeout?.cancel()
                heartbeat?.cancel()
                if (!running || gen != generation) return
                socket = null
                val errorCode = relayErrorCode
                if (errorCode == RelayStatus.ACCOUNT_REQUIRED && code == RelayProtocol.CLOSE_UNAUTHORIZED) {
                    failFatalLocked(RelayStatus.Error(SIGN_IN_MESSAGE, retryInMs = null, fatal = true, code = errorCode))
                    return
                }
                if (code == RelayProtocol.CLOSE_UNAUTHORIZED || code == RelayProtocol.CLOSE_PROTOCOL_VERSION) {
                    failFatalLocked(
                        RelayStatus.Error(fatalMessage(code, relayError ?: reason?.ifBlank { null }), retryInMs = null, fatal = true, code = errorCode),
                    )
                    return
                }
                retryLaterLocked(failure ?: relayError ?: "The relay closed the connection (code $code${reason?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""})")
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
        const val SIGN_IN_MESSAGE = "Sign in to your DroidHands account to use this relay"

        fun fatalMessage(code: Int, detail: String?): String {
            val base = if (code == RelayProtocol.CLOSE_UNAUTHORIZED) {
                "The relay rejected this device (check the relay access key in the DroidHands connection settings)"
            } else {
                "The relay does not support this app's protocol version (update the app or the relay)"
            }
            return if (detail != null) "$base: $detail" else base
        }

        val json = Json { ignoreUnknownKeys = true }
    }
}
