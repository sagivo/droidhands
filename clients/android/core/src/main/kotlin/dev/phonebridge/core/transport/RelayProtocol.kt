package dev.phonebridge.core.transport

import dev.phonebridge.core.auth.DeviceIdentity
import dev.phonebridge.core.mcp.TransportRequest
import dev.phonebridge.core.mcp.TransportResponse
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import kotlin.random.Random

/** Device ⇄ relay tunnel frames and helpers (docs/PROTOCOL.md §3). Pure; no I/O. */
internal object RelayProtocol {
    const val PROTOCOL_VERSION = 1

    /** Max frame size accepted by the relay. */
    const val MAX_FRAME_BYTES = 16 * 1024 * 1024

    const val CLOSE_NORMAL = 1000
    const val CLOSE_UNAUTHORIZED = 4001
    const val CLOSE_PROTOCOL_VERSION = 4002

    const val MIN_BACKOFF_MS = 1_000L
    const val MAX_BACKOFF_MS = 60_000L

    /**
     * `http(s)://host[:port][/prefix]` → `ws(s)://host[:port][/prefix]/v1/device`. `ws`/`wss`
     * input is kept. Trailing slashes, query and fragment are dropped. Null if not a usable URL.
     */
    fun deviceUrl(baseUrl: String): String? = base(baseUrl)?.let { (scheme, rest) -> "$scheme://$rest/v1/device" }

    /** Fallback MCP URL (`http(s)://…/mcp`) when the relay's welcome lacks `mcpUrl`. */
    fun mcpUrl(baseUrl: String): String? = base(baseUrl)?.let { (scheme, rest) ->
        val http = if (scheme == "wss") "https" else "http"
        "$http://$rest/mcp"
    }

    /** (ws scheme, "host[:port][/prefix]") */
    private fun base(baseUrl: String): Pair<String, String>? {
        val uri = try {
            URI(baseUrl.trim())
        } catch (e: Exception) {
            return null
        }
        val scheme = when (uri.scheme?.lowercase()) {
            "http", "ws" -> "ws"
            "https", "wss" -> "wss"
            else -> return null
        }
        val host = uri.host?.takeIf { it.isNotEmpty() } ?: return null
        val port = if (uri.port >= 0) ":${uri.port}" else ""
        val path = (uri.rawPath ?: "").trimEnd('/')
        return scheme to "$host$port$path"
    }

    /** Exponential backoff with equal jitter: attempt 0 → 1 s, doubling up to 60 s. */
    fun backoffDelayMs(attempt: Int, random: Random = Random.Default): Long {
        val exp = MIN_BACKOFF_MS shl attempt.coerceIn(0, 6) // 1, 2, 4, … 64 s
        val ceiling = exp.coerceAtMost(MAX_BACKOFF_MS)
        val jittered = ceiling / 2 + random.nextLong(ceiling / 2 + 1)
        return jittered.coerceIn(MIN_BACKOFF_MS, MAX_BACKOFF_MS)
    }

    fun hello(identity: DeviceIdentity, hello: DeviceHello): JsonObject = buildJsonObject {
        put("type", "hello")
        put("protocolVersion", PROTOCOL_VERSION)
        put("deviceId", identity.deviceId)
        put("deviceSecret", identity.secretEncoded)
        put("platform", hello.platform)
        put("appVersion", hello.appVersion)
        put("deviceName", hello.deviceName)
        hello.accessKey?.takeIf { it.isNotBlank() }?.let { put("accessKey", it) }
    }

    fun pong(t: JsonElement?): JsonObject = buildJsonObject {
        put("type", "pong")
        if (t != null) put("t", t)
    }

    fun response(id: String, response: TransportResponse): JsonObject = buildJsonObject {
        put("type", "response")
        put("id", id)
        put("status", response.status)
        putJsonObject("headers") { for ((k, v) in response.headers) put(k.lowercase(), v) }
        put("body", response.body?.let(::JsonPrimitive) ?: JsonNull)
    }

    /** Builds the handler input from a `request` frame (headers are matched case-insensitively). */
    fun transportRequest(frame: JsonObject): TransportRequest {
        val headers = (frame["headers"] as? JsonObject).orEmpty().entries
            .mapNotNull { (k, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.let { k.lowercase() to it.content } }
            .toMap()
        return TransportRequest(
            authorization = headers["authorization"],
            body = frame.string("body").orEmpty(),
            transport = "relay",
            protocolVersionHeader = headers["mcp-protocol-version"],
            userAgent = headers["user-agent"],
            remoteAddress = frame.string("remoteAddress"),
        )
    }

    /** A JSON-RPC error response with `id: null`. */
    fun errorResponse(status: Int, code: Int, message: String, extraHeaders: Map<String, String> = emptyMap()) = TransportResponse(
        status = status,
        body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", JsonNull)
            putJsonObject("error") {
                put("code", code)
                put("message", message)
            }
        }.toString(),
        headers = mapOf("content-type" to "application/json") + extraHeaders,
    )

    /** UTF-8 byte length without allocating the encoded bytes (an upper bound for lone surrogates). */
    fun utf8Length(text: String): Long {
        var bytes = 0L
        var i = 0
        while (i < text.length) {
            val c = text[i]
            bytes += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1]) -> { i++; 4 }
                else -> 3
            }
            i++
        }
        return bytes
    }

    fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject(emptyMap())
}
