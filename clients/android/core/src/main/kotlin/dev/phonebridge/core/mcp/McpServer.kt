package dev.phonebridge.core.mcp

import dev.phonebridge.core.audit.AuditEntry
import dev.phonebridge.core.audit.AuditLog
import dev.phonebridge.core.audit.AuditOutcome
import dev.phonebridge.core.auth.AuthResult
import dev.phonebridge.core.auth.TokenFormat
import dev.phonebridge.core.auth.TokenManager
import dev.phonebridge.core.auth.TokenRecord
import dev.phonebridge.core.confirm.ConfirmationProvider
import dev.phonebridge.core.policy.PolicyEngine
import dev.phonebridge.core.tools.Risk
import dev.phonebridge.core.tools.Scopes
import dev.phonebridge.core.tools.ToolRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.coroutines.cancellation.CancellationException

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
) {
    // Never print the bearer token.
    override fun toString(): String =
        "TransportRequest(transport=$transport, bodyLength=${body.length}, protocolVersionHeader=$protocolVersionHeader, " +
            "userAgent=$userAgent, remoteAddress=$remoteAddress, authorization=${if (authorization == null) "absent" else "<redacted>"})"
}

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
 * [handle] is safe to call concurrently. It may block briefly on persistence (token last-use
 * timestamps, audit appends), so call it from a background dispatcher. Every request is
 * authenticated first, including `initialize`. Only `tools/call` and authentication failures are
 * audited.
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
    private val toolCalls = ToolCalls(registry, policy, confirmations, audit, isAccessEnabled, confirmationTimeoutMs, clock)

    suspend fun handle(request: TransportRequest): TransportResponse {
        val auth = try {
            tokens.authenticate(request.authorization)
        } catch (e: Exception) {
            AuthResult.Denied("authentication error: ${ToolText.describe(e)}")
        }
        val token = when (auth) {
            is AuthResult.Ok -> auth.token
            is AuthResult.Denied -> {
                recordAuthFailure(request, auth.reason)
                return UNAUTHORIZED
            }
        }

        val root = try {
            JsonRpc.json.parseToJsonElement(request.body)
        } catch (e: Exception) {
            return json(400, JsonRpc.error(JsonNull, JsonRpc.PARSE_ERROR, "Parse error: the body is not valid JSON"))
        }

        return when (root) {
            is JsonArray -> handleBatch(root, token, request)
            else -> handleMessage(root, token, request)?.let { json(200, it) } ?: ACCEPTED
        }
    }

    private suspend fun handleBatch(batch: JsonArray, token: TokenRecord, request: TransportRequest): TransportResponse {
        if (batch.isEmpty()) return json(200, JsonRpc.error(JsonNull, JsonRpc.INVALID_REQUEST, "Invalid Request: empty batch"))
        if (batch.size > MAX_BATCH) {
            return json(200, JsonRpc.error(JsonNull, JsonRpc.INVALID_REQUEST, "Invalid Request: batch too large (max $MAX_BATCH messages)"))
        }
        // Concurrent, so several slow confirmations in one batch don't add up past transport timeouts.
        val responses = coroutineScope {
            batch.map { element -> async { handleMessage(element, token, request) } }.awaitAll()
        }.filterNotNull()
        return if (responses.isEmpty()) ACCEPTED else json(200, JsonArray(responses))
    }

    /** Returns the JSON-RPC response, or null for notifications and client responses. */
    private suspend fun handleMessage(element: JsonElement, token: TokenRecord, request: TransportRequest): JsonObject? =
        when (val message = Incoming.classify(element)) {
            is Incoming.ClientResponse, is Incoming.Notification -> null
            is Incoming.Invalid -> JsonRpc.error(message.id, JsonRpc.INVALID_REQUEST, message.message)
            is Incoming.Request -> try {
                dispatch(message, token, request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                JsonRpc.error(message.id, JsonRpc.INTERNAL_ERROR, "Internal error: ${ToolText.describe(e)}")
            }
        }

    private suspend fun dispatch(message: Incoming.Request, token: TokenRecord, request: TransportRequest): JsonObject {
        val id = message.id
        val params = when (val p = message.params) {
            null, JsonNull -> EMPTY_OBJECT
            is JsonObject -> p
            else -> return JsonRpc.error(id, JsonRpc.INVALID_PARAMS, "Invalid params: \"params\" must be an object")
        }
        return when (message.method) {
            "initialize" -> JsonRpc.result(id, initializeResult(params))
            "ping" -> JsonRpc.result(id, EMPTY_OBJECT)
            "tools/list" -> JsonRpc.result(id, toolsList(token))
            "tools/call" -> toolCalls.call(id, params, token, request.transport)
            "resources/list" -> JsonRpc.result(id, buildJsonObject { putJsonArray("resources") {} })
            "resources/templates/list" -> JsonRpc.result(id, buildJsonObject { putJsonArray("resourceTemplates") {} })
            "prompts/list" -> JsonRpc.result(id, buildJsonObject { putJsonArray("prompts") {} })
            else -> JsonRpc.error(id, JsonRpc.METHOD_NOT_FOUND, "Method not found: ${ToolText.truncate(message.method, 100)}")
        }
    }

    private fun initializeResult(params: JsonObject): JsonObject {
        val requested = (params["protocolVersion"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val version = requested?.takeIf { it in SUPPORTED_PROTOCOL_VERSIONS } ?: SUPPORTED_PROTOCOL_VERSIONS.first()
        return buildJsonObject {
            put("protocolVersion", version)
            putJsonObject("capabilities") {
                putJsonObject("tools") { put("listChanged", false) }
            }
            putJsonObject("serverInfo") {
                put("name", serverInfo.name)
                put("version", serverInfo.version)
                serverInfo.title?.let { put("title", it) }
            }
            instructions?.let { put("instructions", it) }
        }
    }

    private fun toolsList(token: TokenRecord): JsonObject = buildJsonObject {
        putJsonArray("tools") {
            for (tool in registry.all()) {
                if (!Scopes.allows(token.scopes, tool.scope)) continue
                val unavailable = ToolText.unavailableReason(tool)
                addJsonObject {
                    put("name", tool.name)
                    put("title", tool.title)
                    put("description", if (unavailable == null) tool.description else "${tool.description}\n\nCurrently unavailable: $unavailable")
                    put("inputSchema", tool.inputSchema)
                    putJsonObject("annotations") {
                        put("title", tool.title)
                        put("readOnlyHint", tool.risk == Risk.READ)
                        put("destructiveHint", tool.risk == Risk.SENSITIVE)
                        put("idempotentHint", tool.risk == Risk.READ)
                        put("openWorldHint", tool.risk == Risk.SENSITIVE)
                    }
                }
            }
        }
    }

    private fun recordAuthFailure(request: TransportRequest, reason: String) {
        val (method, tool) = peekMethod(request.body)
        // Name the token when its id is well-formed and known (never the secret).
        val parsed = TokenFormat.parseAuthorization(request.authorization)
        val known = parsed?.let { p -> tokens.tokens.value.firstOrNull { it.id == p.tokenId } }
        val detail = buildString {
            append(reason)
            request.remoteAddress?.let { append("; from ").append(it) }
            request.userAgent?.let { append("; ua ").append(it) }
        }
        try {
            audit.record(
                AuditEntry(
                    timestamp = clock(),
                    tokenId = known?.id,
                    tokenName = known?.name,
                    method = method,
                    tool = tool,
                    outcome = AuditOutcome.AUTH_FAILED,
                    transport = request.transport,
                    detail = ToolText.truncate(detail, ToolText.PREVIEW_CHARS),
                ),
            )
        } catch (e: Exception) {
            // Auditing must never break request handling.
        }
    }

    companion object {
        val SUPPORTED_PROTOCOL_VERSIONS = listOf("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")

        /** Max messages in one batch; larger batches are rejected with -32600. */
        const val MAX_BATCH = 64

        /** Unauthenticated bodies larger than this are not parsed for the audit entry. */
        private const val MAX_PEEK_BODY = 64 * 1024

        private val EMPTY_OBJECT = JsonObject(emptyMap())
        private val JSON_HEADERS = mapOf("content-type" to "application/json")

        private val UNAUTHORIZED = TransportResponse(
            status = 401,
            body = JsonRpc.errorBody(JsonRpc.UNAUTHORIZED, "Unauthorized"),
            headers = JSON_HEADERS + ("www-authenticate" to "Bearer realm=\"phonebridge\""),
        )

        private val ACCEPTED = TransportResponse(status = 202, body = null)

        private fun json(status: Int, body: JsonElement) = TransportResponse(status, JsonRpc.encode(body), JSON_HEADERS)

        /** Best-effort method (and tool name for tools/call) from an unauthenticated body. */
        internal fun peekMethod(body: String): Pair<String, String?> {
            if (body.length > MAX_PEEK_BODY) return "unknown" to null
            val root = try {
                JsonRpc.json.parseToJsonElement(body)
            } catch (e: Exception) {
                return "unknown" to null
            }
            val messages = when (root) {
                is JsonArray -> root.filterIsInstance<JsonObject>()
                is JsonObject -> listOf(root)
                else -> emptyList()
            }
            val methods = messages.mapNotNull { (it["method"] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.distinct()
            if (methods.isEmpty()) return "unknown" to null
            val tool = messages.firstNotNullOfOrNull { m ->
                if ((m["method"] as? JsonPrimitive)?.content != "tools/call") return@firstNotNullOfOrNull null
                ((m["params"] as? JsonObject)?.get("name") as? JsonPrimitive)?.takeIf { it.isString }?.content
            }
            return ToolText.truncate(methods.joinToString(","), 100) to tool?.let { ToolText.truncate(it, 100) }
        }
    }
}
