package com.droidhands.core.oauth

import com.droidhands.core.util.Encoding
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** A verified registration, as decoded from a client_id. */
data class RegisteredClient(
    val clientName: String,
    val redirectUris: List<String>,
    /** Seconds since the epoch. */
    val issuedAt: Long,
)

/**
 * RFC 7591 dynamic registration for public clients without server-side state, in the relay's format
 * (relay/src/oauth/clientRegistration.ts, docs/PROTOCOL.md §5.2). The registration lives in the
 * `client_id` itself:
 *
 *     client_id = base64url(json{n: client_name, r: redirect_uris, t: issuedAt}) + "." +
 *                 base64url(HMAC-SHA256(key, <first part>))
 *
 * so registrations survive restarts of the app, and nobody can alter the name or redirect URIs
 * without invalidating the MAC. Pure; thread-safe.
 */
class OAuthClients(key: ByteArray) {
    private val key = key.copyOf()

    init {
        require(key.size >= 16) { "client key must be at least 16 bytes" }
    }

    /** `201` body, or an RFC 7591 §3.2.2 error ([error], [description]). */
    sealed interface Registration {
        data class Ok(val clientId: String, val client: RegisteredClient) : Registration {
            /** RFC 7591 §3.2.1 response body. */
            fun body(): JsonObject = buildJsonObject {
                put("client_id", clientId)
                put("client_name", client.clientName)
                put("redirect_uris", buildJsonArray { client.redirectUris.forEach { add(JsonPrimitive(it)) } })
                put("token_endpoint_auth_method", "none")
                put("grant_types", buildJsonArray { add(JsonPrimitive("authorization_code")) })
                put("response_types", buildJsonArray { add(JsonPrimitive("code")) })
                put("client_id_issued_at", client.issuedAt)
            }
        }

        data class Failed(val error: String, val description: String) : Registration
    }

    /**
     * Validates RFC 7591 client metadata and issues a stateless client_id. Requested values this
     * server cannot honor (e.g. `client_secret_basic`, `refresh_token`) are replaced with the
     * supported ones, as RFC 7591 §3.2.1 allows; the response tells the client what it got.
     */
    fun register(metadata: JsonElement?, nowMs: Long): Registration {
        fun fail(error: String, description: String) = Registration.Failed(error, description)
        if (metadata !is JsonObject) return fail(INVALID_METADATA, "the request body must be a JSON object")
        val uris = metadata["redirect_uris"]
        if (uris !is JsonArray || uris.isEmpty()) return fail(INVALID_REDIRECT_URI, "redirect_uris must be a non-empty array of URIs")
        if (uris.size > MAX_REDIRECT_URIS) return fail(INVALID_REDIRECT_URI, "at most $MAX_REDIRECT_URIS redirect_uris are allowed")
        val strings = uris.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return fail(INVALID_REDIRECT_URI, "redirect_uris must contain strings") }
        strings.firstNotNullOfOrNull { RedirectUris.problem(it) }?.let { return fail(INVALID_REDIRECT_URI, it) }
        val name = metadata["client_name"]
        if (name != null && (name !is JsonPrimitive || !name.isString)) return fail(INVALID_METADATA, "client_name must be a string")
        if (!absentOrIncludes(metadata["grant_types"], "authorization_code")) {
            return fail(INVALID_METADATA, "grant_types must include \"authorization_code\" (the only supported grant)")
        }
        if (!absentOrIncludes(metadata["response_types"], "code")) {
            return fail(INVALID_METADATA, "response_types must include \"code\" (the only supported response type)")
        }
        val client = RegisteredClient(sanitizeName((name as? JsonPrimitive)?.contentOrNull), strings.distinct(), nowMs / 1000)
        return Registration.Ok(encode(client), client)
    }

    /** Serializes and signs [client] into a client_id. */
    fun encode(client: RegisteredClient): String {
        val json = buildJsonObject {
            put("n", client.clientName)
            put("r", buildJsonArray { client.redirectUris.forEach { add(JsonPrimitive(it)) } })
            put("t", client.issuedAt)
        }
        val payload = Encoding.base64Url(json.toString().toByteArray(Charsets.UTF_8))
        return "$payload.${Encoding.base64Url(mac(payload))}"
    }

    /**
     * Verifies the MAC (constant-time) and decodes a client_id. Null for anything this key did not
     * issue, or whose contents are no longer acceptable.
     */
    fun decode(clientId: String): RegisteredClient? {
        if (clientId.length > MAX_CLIENT_ID_LENGTH) return null
        val parts = clientId.split('.')
        if (parts.size != 2) return null
        val (payload, signature) = parts
        val given = decodeOrNull(signature) ?: return null
        if (!Encoding.constantTimeEquals(given, mac(payload))) return null
        val json = decodeOrNull(payload) ?: return null
        val value = try {
            kotlinx.serialization.json.Json.parseToJsonElement(json.toString(Charsets.UTF_8))
        } catch (e: IllegalArgumentException) {
            return null
        } as? JsonObject ?: return null
        val n = (value["n"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val t = (value["t"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: return null
        val r = value["r"] as? JsonArray ?: return null
        if (r.isEmpty()) return null
        val uris = r.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return null }
        // Defense in depth: re-check what the MAC vouches for, in case the rules got stricter since.
        if (uris.any { RedirectUris.problem(it) != null }) return null
        return RegisteredClient(sanitizeName(n), uris, t)
    }

    /**
     * The registration [PUBLIC_CLIENT_ID] stands for in an authorization request that uses [redirectUri]
     * (docs/PROTOCOL.md §5.2): exactly that redirect URI, named after its host. Null when the request
     * names none or an unacceptable one.
     */
    fun publicClient(redirectUri: String?): RegisteredClient? {
        if (redirectUri == null || RedirectUris.problem(redirectUri) != null) return null
        return RegisteredClient(sanitizeName(RedirectUris.displayHost(redirectUri)), listOf(redirectUri), 0)
    }

    /** True for [PUBLIC_CLIENT_ID] or a client_id this key issued. */
    fun isKnown(clientId: String): Boolean = clientId == PUBLIC_CLIENT_ID || decode(clientId) != null

    private fun mac(payload: String): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(payload.toByteArray(Charsets.UTF_8))
        }

    companion object {
        /**
         * For MCP clients that need a client ID typed in and don't register themselves; no weaker than
         * dynamic registration, where anyone may register any acceptable redirect URI. As on the relay.
         */
        const val PUBLIC_CLIENT_ID = "droidhands"

        /** Longest client name kept (in code points); longer names are truncated. */
        const val MAX_CLIENT_NAME_LENGTH = 60

        /** Most redirect URIs per client (keeps the client_id short enough for URLs). */
        const val MAX_REDIRECT_URIS = 5

        /** Name used when a client registers without `client_name`. */
        const val DEFAULT_CLIENT_NAME = "Unnamed app"

        /** Longest client_id accepted when decoding; anything longer cannot be one of ours. */
        private const val MAX_CLIENT_ID_LENGTH = 4096

        const val INVALID_REDIRECT_URI = "invalid_redirect_uri"
        const val INVALID_METADATA = "invalid_client_metadata"

        /** Control, bidi, zero-width and line/paragraph separator characters. */
        private val UNSAFE = Regex("[\\p{Cc}\\u2028\\u2029\\u061c\\u200b-\\u200f\\u202a-\\u202e\\u2060-\\u2069\\ufeff]")
        private val SPACES = Regex("\\s+")

        /**
         * Makes a client-supplied name safe to show on the phone and in HTML: strips control, bidi and
         * line-separator characters, collapses whitespace and truncates to [MAX_CLIENT_NAME_LENGTH]
         * code points. Empty or missing names become [DEFAULT_CLIENT_NAME]. (HTML escaping happens at render.)
         */
        fun sanitizeName(raw: String?): String {
            if (raw == null) return DEFAULT_CLIENT_NAME
            val cleaned = raw.replace(UNSAFE, " ").replace(SPACES, " ").trim()
            val end = if (cleaned.codePointCount(0, cleaned.length) > MAX_CLIENT_NAME_LENGTH) {
                cleaned.offsetByCodePoints(0, MAX_CLIENT_NAME_LENGTH)
            } else {
                cleaned.length
            }
            return cleaned.substring(0, end).trim().ifEmpty { DEFAULT_CLIENT_NAME }
        }

        private fun absentOrIncludes(value: JsonElement?, required: String): Boolean {
            if (value == null) return true
            return value is JsonArray && value.any { it is JsonPrimitive && it.isString && it.content == required }
        }

        private fun decodeOrNull(text: String): ByteArray? = try {
            Encoding.base64UrlDecode(text)
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
