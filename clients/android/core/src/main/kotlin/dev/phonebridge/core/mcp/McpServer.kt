package dev.phonebridge.core.mcp

import dev.phonebridge.core.audit.AuditEntry
import dev.phonebridge.core.audit.AuditLog
import dev.phonebridge.core.audit.AuditOutcome
import dev.phonebridge.core.auth.AuthResult
import dev.phonebridge.core.auth.TokenFormat
import dev.phonebridge.core.auth.TokenManager
import dev.phonebridge.core.auth.TokenRecord
import dev.phonebridge.core.auth.ToolRule
import dev.phonebridge.core.confirm.ConfirmationProvider
import dev.phonebridge.core.policy.GlobalSecurity
import dev.phonebridge.core.policy.PolicyEngine
import dev.phonebridge.core.tools.Risk
import dev.phonebridge.core.tools.Scopes
import dev.phonebridge.core.tools.Tool
import dev.phonebridge.core.tools.ToolRegistry
import dev.phonebridge.core.tools.ToolResult
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
 * audited; refused requests (failed authentication, unknown or out-of-scope tools, bad arguments,
 * paused, policy and rate-limit refusals) are throttled per token or caller address so they cannot
 * flood the audit log ([AuditThrottle]).
 *
 * Each `tools/call` runs in a job of its own on the caller's dispatcher ([InFlightCalls]): if the
 * caller is cancelled (the relay socket dropped or was restarted, the local server stopped), [handle]
 * throws at once but the tool call goes on, is audited (noting that its result was not delivered),
 * and its response is dropped. [cancelCalls] is how calls are stopped on purpose.
 *
 * `tools/list` hides tools outside the token's scopes, tools above its [TokenRecord.maxRisk] and tools
 * the token has a [ToolRule.BLOCK] rule for (calling one anyway is refused by [PolicyEngine] and
 * audited DENIED_POLICY).
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
    /**
     * Device-wide security settings, read on every `tools/call` and applied to its [dev.phonebridge.core.tools.CallContext]
     * (never-allow apps, one-time-code redaction). If it throws, the call is refused (fail closed).
     */
    private val globalSecurity: () -> GlobalSecurity = { GlobalSecurity() },
) {
    private val auditThrottle = AuditThrottle(audit, clock)
    private val inFlight = InFlightCalls()
    private val toolCalls = ToolCalls(
        registry = registry,
        policy = policy,
        confirmations = confirmations,
        audit = auditThrottle,
        isAccessEnabled = isAccessEnabled,
        confirmationTimeoutMs = confirmationTimeoutMs,
        clock = clock,
        globalSecurity = globalSecurity,
        currentToken = { id -> tokens.tokens.value.firstOrNull { it.id == id } },
    )

    /**
     * Cancels in-flight tools/call executions — all of them ([tokenId] = null, e.g. on Pause) or one token's
     * (on revoke/delete; all of them also after "revoke all"), including calls whose agent already
     * disconnected. Their open confirmation prompts are withdrawn (the [ConfirmationProvider] call is
     * cancelled). Each cancelled call answers its agent with an error saying why (its token removed or
     * revoked, else access paused) and is audited CANCELLED, noting whether the tool had started. Call it
     * after the change (pause, revoke, delete) is saved, so the reason is accurate. Returns how many calls
     * were cancelled. Thread-safe and non-blocking.
     */
    fun cancelCalls(tokenId: String? = null): Int = inFlight.cancel(tokenId) { call -> stopReasonFor(call.tokenId) }

    /** Calls in progress, including those whose agent disconnected (tests and diagnostics). */
    internal val inFlightCalls: Int get() = inFlight.size

    /** Why a call of [tokenId] is being cancelled, from the current state: token first, then the kill switch. */
    private fun stopReasonFor(tokenId: String): StopReason {
        val current = tokens.tokens.value.firstOrNull { it.id == tokenId }
        val enabled = try {
            isAccessEnabled()
        } catch (e: Exception) {
            false
        }
        return when {
            current == null -> StopReason.DELETED
            current.revoked -> StopReason.REVOKED
            !enabled -> StopReason.PAUSED
            else -> StopReason.STOPPED
        }
    }

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
            "tools/call" -> callTool(id, params, token, request.transport)
            "resources/list" -> JsonRpc.result(id, buildJsonObject { putJsonArray("resources") {} })
            "resources/templates/list" -> JsonRpc.result(id, buildJsonObject { putJsonArray("resourceTemplates") {} })
            "prompts/list" -> JsonRpc.result(id, buildJsonObject { putJsonArray("prompts") {} })
            else -> JsonRpc.error(id, JsonRpc.METHOD_NOT_FOUND, "Method not found: ${ToolText.truncate(message.method, 100)}")
        }
    }

    private suspend fun callTool(id: JsonElement, params: JsonObject, token: TokenRecord, transport: String): JsonObject {
        val call = InFlightCall(token.id)
        val stopped = { reason: StopReason -> JsonRpc.result(id, ToolCalls.encodeResult(ToolResult.error(reason.message))) }
        return inFlight.run(call, stopped) { toolCalls.call(id, params, token, transport, call) }
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
                if (!isVisible(token, tool)) continue
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
        val entry = AuditEntry(
            timestamp = clock(),
            tokenId = known?.id,
            tokenName = known?.name,
            method = method,
            tool = tool,
            outcome = AuditOutcome.AUTH_FAILED,
            transport = request.transport,
            detail = ToolText.truncate(detail, ToolText.PREVIEW_CHARS),
        )
        // Throttled per known token, else per caller address, so bad tokens cannot flood the log.
        val source = known?.let { "token:${it.id}" } ?: "from:${request.transport}:${request.remoteAddress ?: "unknown"}"
        auditThrottle.recordRefusal(entry, source)
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

        /**
         * `tools/list` shows tools within the token's scopes and risk cap, except those it has a BLOCK rule
         * for. A running call re-checks this against the token's current record and stops once it is false.
         */
        internal fun isVisible(token: TokenRecord, tool: Tool): Boolean =
            Scopes.allows(token.scopes, tool.scope) && token.toolRules[tool.name] != ToolRule.BLOCK &&
                !PolicyEngine.exceedsMaxRisk(token.maxRisk, tool.risk)

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
