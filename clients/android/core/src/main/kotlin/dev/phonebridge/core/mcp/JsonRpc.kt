package dev.phonebridge.core.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/** JSON-RPC 2.0 codes and message helpers (pure; no I/O). */
internal object JsonRpc {
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603

    /** Server-defined: missing/invalid bearer token. */
    const val UNAUTHORIZED = -32001

    /** Compact encoder used for every response body. */
    val json = Json { encodeDefaults = true; explicitNulls = false }

    fun result(id: JsonElement, result: JsonElement): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }

    fun error(id: JsonElement, code: Int, message: String): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("error", buildJsonObject {
            put("code", code)
            put("message", message)
        })
    }

    /** Serialized error with `id: null`, for transport-level failures. */
    fun errorBody(code: Int, message: String): String = json.encodeToString(JsonElement.serializer(), error(JsonNull, code, message))

    fun encode(element: JsonElement): String = json.encodeToString(JsonElement.serializer(), element)
}

/** One element of a request body, classified per JSON-RPC 2.0. */
internal sealed interface Incoming {
    /** A request expecting a response. [id] is a string or number primitive. */
    data class Request(val id: JsonElement, val method: String, val params: JsonElement?) : Incoming

    /** No `id`: no response is sent. */
    data class Notification(val method: String) : Incoming

    /** A response from the client (to a server request we never send): ignored. */
    data object ClientResponse : Incoming

    /** Malformed; answered with -32600. [id] is the echoed id or JsonNull. */
    data class Invalid(val id: JsonElement, val message: String) : Incoming

    companion object {
        fun classify(element: JsonElement): Incoming {
            if (element !is JsonObject) return Invalid(JsonNull, "Invalid Request: expected a JSON-RPC object")
            val hasId = "id" in element
            val id = element["id"]
            val validId = isValidId(id)
            val echoId: JsonElement = if (validId) id!! else JsonNull
            val method = element["method"]

            if (method == null && ("result" in element || "error" in element)) return ClientResponse

            val version = element["jsonrpc"]
            if (version !is JsonPrimitive || !version.isString || version.content != "2.0") {
                return Invalid(echoId, "Invalid Request: \"jsonrpc\" must be \"2.0\"")
            }
            if (method == null) return Invalid(echoId, "Invalid Request: missing \"method\"")
            if (method !is JsonPrimitive || !method.isString) return Invalid(echoId, "Invalid Request: \"method\" must be a string")
            if (hasId && !validId) return Invalid(JsonNull, "Invalid Request: \"id\" must be a string or a number")
            return if (hasId) Request(echoId, method.content, element["params"]) else Notification(method.content)
        }

        private fun isValidId(id: JsonElement?): Boolean {
            if (id !is JsonPrimitive || id is JsonNull) return false
            return id.isString || id.doubleOrNull != null
        }
    }
}
