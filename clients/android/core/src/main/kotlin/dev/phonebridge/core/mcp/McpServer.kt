package dev.phonebridge.core.mcp

import dev.phonebridge.core.audit.AuditLog
import dev.phonebridge.core.auth.TokenManager
import dev.phonebridge.core.confirm.ConfirmationProvider
import dev.phonebridge.core.policy.PolicyEngine
import dev.phonebridge.core.tools.ToolRegistry

data class ServerInfo(val name: String, val version: String, val title: String? = null)

/** Transport-neutral HTTP request for `POST /mcp` (local server and relay tunnel both produce this). */
data class TransportRequest(
    /** Raw Authorization header value (e.g. "Bearer pb1..."), or null. */
    val authorization: String?,
    /** Raw request body. */
    val body: String,
    /** "relay" or "local". */
    val transport: String,
    val protocolVersionHeader: String? = null,
    val userAgent: String? = null,
    val remoteAddress: String? = null,
)

data class TransportResponse(
    val status: Int,
    /** null => empty body (e.g. 202). */
    val body: String?,
    /** Lowercase header names. Includes content-type when body != null. */
    val headers: Map<String, String> = emptyMap(),
)

/**
 * Stateless MCP (Streamable HTTP subset) server. See docs/PROTOCOL.md §4 and docs/ARCHITECTURE.md
 * "Request lifecycle". Also registers nothing itself: the `bridge_capabilities` meta tool is
 * created by [capabilitiesTool] and registered by the platform graph.
 *
 * STUB — implemented by the core agent.
 */
class McpServer(
    private val registry: ToolRegistry,
    private val tokens: TokenManager,
    private val policy: PolicyEngine,
    private val confirmations: ConfirmationProvider,
    private val audit: AuditLog,
    private val serverInfo: ServerInfo,
    private val instructions: String?,
    /** Global kill switch. When false, tools/call returns an isError "paused" result. */
    private val isAccessEnabled: () -> Boolean = { true },
    private val confirmationTimeoutMs: Long = 60_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun handle(request: TransportRequest): TransportResponse = TODO()

    companion object {
        val SUPPORTED_PROTOCOL_VERSIONS = listOf("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")
    }
}
