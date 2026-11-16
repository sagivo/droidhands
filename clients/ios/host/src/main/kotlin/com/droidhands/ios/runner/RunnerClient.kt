package com.droidhands.ios.runner

import com.droidhands.core.screen.ScreenException
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.net.ConnectException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration

/**
 * HTTP client of the on-phone runner (docs/IOS.md "Runner protocol"): JSON bodies, `Authorization: Bearer <secret>` on
 * every request, a timeout per call. Every failure is a [ScreenException] whose message tells the agent what happened
 * and what fixes it: the runner's own `{"error": …}` sentence, or a plain explanation when it can't be reached or
 * refuses the secret. Thread-safe.
 */
class RunnerClient(
    baseUrl: String,
    private val secret: String,
    private val connectTimeoutMs: Long = 3_000,
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(connectTimeoutMs))
        .version(HttpClient.Version.HTTP_1_1)
        .build(),
) {
    private val base: String = baseUrl.trim().trimEnd('/')

    init {
        require(secret.isNotBlank()) { "The runner secret must not be blank" }
        require(base.startsWith("http://") || base.startsWith("https://")) { "The runner URL must start with http:// or https://" }
    }

    suspend fun get(path: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): JsonObject =
        send(request(path, timeoutMs).GET().build(), path, timeoutMs)

    suspend fun post(path: String, body: JsonObject = JsonObject(emptyMap()), timeoutMs: Long = DEFAULT_TIMEOUT_MS): JsonObject =
        send(
            request(path, timeoutMs)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(JsonElement.serializer(), body)))
                .build(),
            path,
            timeoutMs,
        )

    private fun request(path: String, timeoutMs: Long): HttpRequest.Builder =
        HttpRequest.newBuilder(URI.create(base + path))
            .timeout(Duration.ofMillis(timeoutMs))
            .header("Authorization", "Bearer $secret")
            .header("Accept", "application/json")

    private suspend fun send(request: HttpRequest, path: String, timeoutMs: Long): JsonObject {
        val response = try {
            http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        } catch (e: HttpConnectTimeoutException) {
            throw ScreenException(UNREACHABLE)
        } catch (e: HttpTimeoutException) {
            throw ScreenException(
                "The DroidHands runner on the iPhone didn't answer within ${timeoutMs / 1000} s ($path). The iPhone may be busy, " +
                    "asleep or disconnected; try again, and restart scripts/ios-host.sh if it keeps happening.",
            )
        } catch (e: ConnectException) {
            throw ScreenException(UNREACHABLE)
        } catch (e: IOException) {
            throw ScreenException(UNREACHABLE)
        }
        val status = response.statusCode()
        val body = response.body().orEmpty()
        val parsed = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        if (status == 401) {
            throw ScreenException(
                "The DroidHands runner on the iPhone refused this host's secret (it was started for another session). " +
                    "Restart scripts/ios-host.sh.",
            )
        }
        if (status !in 200..299) {
            val message = (parsed?.get("error") as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
            throw ScreenException(message ?: "The DroidHands runner on the iPhone couldn't do that ($path answered HTTP $status).")
        }
        if (body.isBlank()) return JsonObject(emptyMap())
        return parsed ?: throw ScreenException("The DroidHands runner answered $path with something that isn't JSON. Restart scripts/ios-host.sh.")
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 15_000L

        /** The agent-facing sentence for a runner that isn't listening. */
        const val UNREACHABLE = "The DroidHands runner on the iPhone isn't running or reachable: start scripts/ios-host.sh"

        private val json = Json { ignoreUnknownKeys = true }
    }
}
