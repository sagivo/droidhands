package com.droidhands.core.account

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** The signed-in person, as the API server describes them. */
@Serializable
data class AccountUser(
    val id: String,
    val name: String = "",
    val email: String = "",
    val image: String? = null,
)

/** A signed-in session: [token] is a bearer secret, never logged. */
data class AccountSession(val token: String, val user: AccountUser) {
    override fun toString(): String = "AccountSession(token=***, user=${user.id})"
}

/** A short-lived token for the relay's hello (`accountToken`, docs/PROTOCOL.md §3.1). */
data class RelayToken(val token: String, val expiresAt: Long) {
    override fun toString(): String = "RelayToken(token=***, expiresAt=$expiresAt)"
}

/** What an accounts call came to. */
sealed interface AccountResult<out T> {
    data class Ok<T>(val value: T) : AccountResult<T>

    /** HTTP 401: the session expired, was signed out elsewhere, or the account was deleted. */
    data object SignedOut : AccountResult<Nothing>

    /** Anything else: no network, a server error, a refused code. [error] is the server's code (`invalid_grant`), if any. */
    data class Failed(val message: String, val error: String? = null) : AccountResult<Nothing>
}

/** The API server's calls ([AccountClient]); an interface so the app's account logic can be tested without HTTP. */
interface AccountApi {
    fun signInUrl(challenge: String): String
    suspend fun exchange(code: String, verifier: String): AccountResult<AccountSession>
    suspend fun me(sessionToken: String): AccountResult<AccountUser>
    suspend fun relayToken(sessionToken: String): AccountResult<RelayToken>
    suspend fun signOut(sessionToken: String): AccountResult<Unit>
    suspend fun deleteAccount(sessionToken: String): AccountResult<Unit>
}

/**
 * The DroidHands API server (sign in with Google, then a session for this app). Platform-neutral:
 * the app opens [signInUrl] in a browser, gets `droidhands://signed-in?code=…` back and calls [exchange]
 * with the verifier it kept. Every other call carries the session as a bearer token.
 *
 * @param baseUrl e.g. `https://api.droidhands.com` (no trailing slash).
 */
class AccountClient(
    private val baseUrl: String,
    private val httpClient: OkHttpClient = OkHttpClient(),
) : AccountApi {
    /** The page that starts sign-in with Google for [challenge] ([Pkce.challenge]). */
    override fun signInUrl(challenge: String): String =
        requireNotNull("$baseUrl/app/sign-in".toHttpUrlOrNull()) { "Invalid accounts URL" }.newBuilder()
            .addQueryParameter("code_challenge", challenge)
            .addQueryParameter("code_challenge_method", Pkce.METHOD)
            .build()
            .toString()

    /** Trades the sign-in [code] for a session; a code is good once, and only with its [verifier]. */
    override suspend fun exchange(code: String, verifier: String): AccountResult<AccountSession> {
        val body = buildJsonObject {
            put("code", code)
            put("code_verifier", verifier)
        }
        return call("POST", "/app/token", null, body) { json ->
            val token = (json["sessionToken"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            val user = json["user"]?.let { decodeUser(it) }
            if (token == null || user == null) null else AccountSession(token, user)
        }
    }

    /** Who [sessionToken] belongs to; [AccountResult.SignedOut] once it no longer works. */
    override suspend fun me(sessionToken: String): AccountResult<AccountUser> =
        call("GET", "/app/me", sessionToken, null) { json -> json["user"]?.let { decodeUser(it) } }

    /** A fresh token for the relay's hello. */
    override suspend fun relayToken(sessionToken: String): AccountResult<RelayToken> =
        call("POST", "/app/relay-token", sessionToken, JsonObject(emptyMap())) { json ->
            val token = (json["token"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            val expiresAt = (json["expiresAt"] as? JsonPrimitive)?.longOrNull
            if (token == null || expiresAt == null) null else RelayToken(token, expiresAt)
        }

    /** Ends the session on the server. A session that is already gone counts as signed out. */
    override suspend fun signOut(sessionToken: String): AccountResult<Unit> = noContent("/app/sign-out", sessionToken)

    /** Deletes the account and everything stored with it. */
    override suspend fun deleteAccount(sessionToken: String): AccountResult<Unit> = noContent("/app/delete-account", sessionToken)

    private suspend fun noContent(path: String, sessionToken: String): AccountResult<Unit> =
        call("POST", path, sessionToken, JsonObject(emptyMap()), allowEmpty = true) { }

    private suspend fun <T> call(
        method: String,
        path: String,
        bearer: String?,
        body: JsonObject?,
        allowEmpty: Boolean = false,
        parse: (JsonObject) -> T?,
    ): AccountResult<T> = withContext(Dispatchers.IO) {
        val url = "$baseUrl$path".toHttpUrlOrNull() ?: return@withContext AccountResult.Failed("Invalid accounts URL")
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .apply { if (bearer != null) header("Authorization", "Bearer $bearer") }
            .method(method, body?.toString()?.toRequestBody(JSON))
            .build()
        try {
            httpClient.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val json = parseObject(text)
                when {
                    response.code == 401 -> AccountResult.SignedOut
                    !response.isSuccessful -> {
                        val error = json?.string("error")
                        val description = json?.string("error_description")
                        AccountResult.Failed(description ?: "The DroidHands server answered HTTP ${response.code}", error)
                    }
                    allowEmpty && json == null -> parse(JsonObject(emptyMap()))?.let { AccountResult.Ok(it) }
                        ?: AccountResult.Failed("Unexpected answer from the DroidHands server")
                    else -> json?.let(parse)?.let { AccountResult.Ok(it) }
                        ?: AccountResult.Failed("Unexpected answer from the DroidHands server")
                }
            }
        } catch (e: IOException) {
            AccountResult.Failed("Couldn't reach the DroidHands server: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun decodeUser(element: kotlinx.serialization.json.JsonElement): AccountUser? =
        try {
            json.decodeFromJsonElement(AccountUser.serializer(), element).takeIf { it.id.isNotBlank() }
        } catch (e: IllegalArgumentException) {
            null
        }

    private fun parseObject(text: String): JsonObject? =
        if (text.isBlank()) null else try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null
        }

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private companion object {
        val JSON = "application/json".toMediaType()
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }
    }
}
