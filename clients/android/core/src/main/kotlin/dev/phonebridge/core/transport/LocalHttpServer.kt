package dev.phonebridge.core.transport

import dev.phonebridge.core.mcp.TransportRequest
import dev.phonebridge.core.mcp.TransportResponse
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

/**
 * Minimal HTTP/1.1 server for local mode (docs/PROTOCOL.md §4): `POST /mcp` → [handler],
 * `GET|DELETE /mcp` → 405, `GET /health` → 200 `{"status":"ok"}`, else 404. Rejects requests whose
 * Origin header is present and not in [allowedOrigins] with 403. Supports Content-Length and
 * chunked request bodies, enforces [maxBodyBytes] (413) and header limits, one request per
 * connection (`Connection: close`). Each connection is handled on its own coroutine.
 *
 * Details:
 * * Binds with `SO_REUSEADDR`, so a restart can reuse the port immediately. [bindAddress] should
 *   be an IP literal (a hostname would be resolved on the caller's thread).
 * * The accept loop runs on a dedicated daemon thread; connections are served on [Dispatchers.IO].
 *   At most [MAX_CONNECTIONS] connections are served at once; extra ones get 503.
 * * Reading a request (head and body) must finish within 30 s, else 408.
 * * `Expect: 100-continue` is answered before the body is read (curl sends it for bodies > 1 KiB),
 *   but only once the request has passed routing, Origin and size checks.
 * * Origin matching is exact after trimming a trailing slash, case-insensitive. The check applies
 *   to every path.
 * * Every response has Content-Length, `Connection: close` and `Cache-Control: no-store`.
 *   Handler exceptions become 500 with a JSON-RPC error body.
 *
 * Lifecycle: [start] on a running server is a no-op; [stop] is idempotent and closes the listening
 * socket and every open connection; the server can be started again after [stop].
 */
class LocalHttpServer(
    private val port: Int,
    private val bindAddress: String = "127.0.0.1",
    private val handler: suspend (TransportRequest) -> TransportResponse,
    private val allowedOrigins: Set<String> = emptySet(),
    private val maxBodyBytes: Int = 1_048_576,
) {
    private class Running(val socket: ServerSocket, val scope: CoroutineScope)

    private val lock = Any()

    @Volatile
    private var running: Running? = null
    private val connections: MutableSet<Socket> = ConcurrentHashMap.newKeySet()
    private val activeConnections = AtomicInteger()
    private val origins = allowedOrigins.mapTo(HashSet()) { normalizeOrigin(it) }

    init {
        require(port in 0..65535) { "port must be 0..65535" }
        require(maxBodyBytes > 0) { "maxBodyBytes must be positive" }
    }

    /** Binds and starts accepting. Throws IOException if the port can't be bound. No-op if running. */
    fun start() {
        synchronized(lock) {
            if (running != null) return
            val server = ServerSocket()
            try {
                server.reuseAddress = true
                server.bind(InetSocketAddress(InetAddress.getByName(bindAddress), port), BACKLOG)
            } catch (e: IOException) {
                server.close()
                throw e
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("phonebridge-http"))
            val state = Running(server, scope)
            running = state
            Thread({ acceptLoop(state) }, "phonebridge-http-accept").apply { isDaemon = true }.start()
        }
    }

    /**
     * Stops accepting, closes open connections and cancels in-flight handlers (their responses are lost;
     * [dev.phonebridge.core.mcp.McpServer] still lets tool calls that already began finish). Idempotent.
     */
    fun stop() {
        val state = synchronized(lock) {
            val current = running ?: return
            running = null
            current
        }
        closeQuietly(state.socket)
        for (socket in connections.toList()) closeQuietly(socket)
        connections.clear()
        state.scope.cancel()
    }

    val isRunning: Boolean get() = running?.socket?.isClosed == false

    /** Actual bound port (useful when constructed with port 0), or -1 when not running. */
    val boundPort: Int get() = running?.socket?.takeUnless { it.isClosed }?.localPort ?: -1

    private fun acceptLoop(state: Running) {
        val server = state.socket
        while (!server.isClosed) {
            val socket = try {
                server.accept()
            } catch (e: IOException) {
                if (server.isClosed) break
                // Transient (e.g. too many open files): back off briefly instead of spinning.
                Thread.sleep(ACCEPT_BACKOFF_MS)
                continue
            }
            connections += socket
            if (server.isClosed) { // stop() raced with accept(); it may have missed this socket.
                connections -= socket
                closeQuietly(socket)
                break
            }
            val overloaded = activeConnections.incrementAndGet() > MAX_CONNECTIONS
            val job = state.scope.launch {
                if (overloaded) reject(socket, 503, "Too many concurrent connections") else serve(socket)
            }
            // Runs even if the coroutine was cancelled before it started (stop() racing with accept).
            job.invokeOnCompletion {
                activeConnections.decrementAndGet()
                connections -= socket
                closeQuietly(socket)
            }
        }
    }

    private suspend fun serve(socket: Socket) {
        val output = try {
            socket.tcpNoDelay = true
            BufferedOutputStream(socket.getOutputStream())
        } catch (e: IOException) {
            return
        }
        val reply = try {
            val input = BufferedInputStream(DeadlineInputStream(socket, System.nanoTime() + READ_TIMEOUT_MS * 1_000_000L))
            process(socket, input, output)
        } catch (e: HttpError) {
            errorResponse(e.status, e.message ?: "Bad request", e.headers)
        } catch (e: SocketTimeoutException) {
            errorResponse(408, "Request not received in time")
        } catch (e: IOException) {
            return // Connection broken; nobody to answer.
        }
        try {
            write(socket, output, reply)
        } catch (e: IOException) {
            // Client went away.
        }
    }

    private suspend fun process(socket: Socket, input: InputStream, output: OutputStream): Reply {
        val head = HttpWire.readHead(input)

        val origin = head.single("origin")
        if (origin != null && normalizeOrigin(origin) !in origins) {
            return errorResponse(403, "Origin not allowed")
        }

        when (head.path) {
            "/mcp" -> if (head.method != "POST") return errorResponse(405, "Use POST", mapOf("Allow" to "POST"))
            "/health" -> return if (head.method == "GET") {
                Reply(200, JSON_HEADERS, """{"status":"ok"}""".toByteArray())
            } else {
                errorResponse(405, "Use GET", mapOf("Allow" to "GET"))
            }
            else -> return errorResponse(404, "Not found")
        }

        // POST /mcp: validate before inviting the body with 100 Continue.
        val declared = HttpWire.contentLength(head)
        if (declared != null && declared > maxBodyBytes) return errorResponse(413, "Body larger than $maxBodyBytes bytes")
        val expect = head.header("expect")
        if (expect != null) {
            if (!expect.equals("100-continue", ignoreCase = true)) return errorResponse(417, "Unsupported Expect")
            if (head.version == "HTTP/1.1") HttpWire.writeContinue(output)
        }
        val body = HttpWire.readBody(head, input, maxBodyBytes)

        val request = TransportRequest(
            authorization = head.single("authorization"),
            body = body.toString(Charsets.UTF_8),
            transport = "local",
            protocolVersionHeader = head.header("mcp-protocol-version"),
            userAgent = head.header("user-agent"),
            remoteAddress = socket.inetAddress?.hostAddress,
        )
        val response = try {
            handler(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Reply(500, JSON_HEADERS, jsonRpcError(-32603, "Internal error"))
        }
        return Reply(response.status, response.headers, response.body?.toByteArray(Charsets.UTF_8))
    }

    private class Reply(val status: Int, val headers: Map<String, String>, val body: ByteArray?)

    private fun write(socket: Socket, output: OutputStream, reply: Reply) {
        HttpWire.writeResponse(output, reply.status, reply.headers, reply.body)
        // Half-close and drain briefly so unread request bytes don't turn the close into a TCP reset
        // that could destroy the response before the client reads it.
        try {
            socket.shutdownOutput()
            socket.soTimeout = DRAIN_TIMEOUT_MS
            val raw = socket.getInputStream()
            val buffer = ByteArray(8192)
            var drained = 0
            while (drained < MAX_DRAIN_BYTES) {
                val n = raw.read(buffer)
                if (n < 0) break
                drained += n
            }
        } catch (e: IOException) {
            // Done either way.
        }
    }

    private fun reject(socket: Socket, status: Int, message: String) {
        try {
            write(socket, BufferedOutputStream(socket.getOutputStream()), errorResponse(status, message))
        } catch (e: IOException) {
            // Ignore.
        }
    }

    private fun errorResponse(status: Int, message: String, extraHeaders: Map<String, String> = emptyMap()): Reply {
        val code = if (status >= 500) -32603 else -32600
        return Reply(status, JSON_HEADERS + extraHeaders, jsonRpcError(code, message))
    }

    /**
     * Applies an overall deadline to reading the request: before each read the socket timeout is
     * set to the remaining time, so slow-drip clients cannot hold a connection open indefinitely.
     */
    private class DeadlineInputStream(private val socket: Socket, private val deadlineNanos: Long) : InputStream() {
        private val raw = socket.getInputStream()

        private fun arm() {
            val remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000
            if (remainingMs <= 0) throw SocketTimeoutException("Request read deadline exceeded")
            socket.soTimeout = remainingMs.toInt().coerceIn(1, READ_TIMEOUT_MS)
        }

        override fun read(): Int {
            arm()
            return raw.read()
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            arm()
            return raw.read(b, off, len)
        }

        override fun available(): Int = raw.available()
    }

    companion object {
        /** Concurrent connections served; more are answered with 503. */
        const val MAX_CONNECTIONS = 64

        private const val BACKLOG = 50
        private const val READ_TIMEOUT_MS = 30_000
        private const val DRAIN_TIMEOUT_MS = 500
        private const val MAX_DRAIN_BYTES = 256 * 1024
        private const val ACCEPT_BACKOFF_MS = 100L
        private val JSON_HEADERS = mapOf("content-type" to "application/json")

        internal fun normalizeOrigin(origin: String): String = origin.trim().trimEnd('/').lowercase()

        private fun jsonRpcError(code: Int, message: String): ByteArray {
            val escaped = message.replace("\\", "\\\\").replace("\"", "\\\"")
            return """{"jsonrpc":"2.0","id":null,"error":{"code":$code,"message":"$escaped"}}""".toByteArray(Charsets.UTF_8)
        }

        private fun closeQuietly(closeable: java.io.Closeable) {
            try {
                closeable.close()
            } catch (e: IOException) {
                // Ignore.
            }
        }
    }
}
