package dev.phonebridge.core.transport

import dev.phonebridge.core.mcp.TransportRequest
import dev.phonebridge.core.mcp.TransportResponse

/**
 * Minimal HTTP/1.1 server for local mode (docs/PROTOCOL.md §4): `POST /mcp` → [handler],
 * `GET|DELETE /mcp` → 405, `GET /health` → 200 `{"status":"ok"}`, else 404. Rejects requests whose
 * Origin header is present and not in [allowedOrigins] with 403. Supports Content-Length and
 * chunked request bodies, enforces [maxBodyBytes] (413) and header limits, one request per
 * connection (`Connection: close`). Each connection is handled on its own coroutine.
 *
 * STUB — implemented by the core agent.
 */
class LocalHttpServer(
    private val port: Int,
    private val bindAddress: String = "127.0.0.1",
    private val handler: suspend (TransportRequest) -> TransportResponse,
    private val allowedOrigins: Set<String> = emptySet(),
    private val maxBodyBytes: Int = 1_048_576,
) {
    /** Binds and starts accepting. Throws IOException if the port can't be bound. */
    fun start(): Unit = TODO()

    fun stop(): Unit = TODO()

    val isRunning: Boolean get() = TODO()

    /** Actual bound port (useful when constructed with port 0). */
    val boundPort: Int get() = TODO()
}
