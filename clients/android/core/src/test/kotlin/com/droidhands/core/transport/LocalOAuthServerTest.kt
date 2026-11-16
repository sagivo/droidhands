package com.droidhands.core.transport

import com.droidhands.core.mcp.TransportResponse
import com.droidhands.core.oauth.LocalOAuth
import com.droidhands.core.util.Encoding
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Test
import java.net.Socket
import java.net.URLEncoder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** [LocalOAuth] served by [LocalHttpServer] over real sockets (docs/PROTOCOL.md §4.1). */
class LocalOAuthServerTest {
    private val decision = CompletableDeferred<PairingDecision>()
    private val finished = LinkedBlockingQueue<Triple<String, String?, Boolean>>()
    private val handler = object : PairingHandler {
        override suspend fun onPairRequest(request: PairingRequest): PairingDecision = decision.await()
        override fun onPairFinished(id: String, tokenId: String?, completed: Boolean) {
            finished += Triple(id, tokenId, completed)
        }
    }
    private val oauth = LocalOAuth(handler, ByteArray(32) { 3 })
    private val server = LocalHttpServer(
        port = 0,
        handler = { TransportResponse(401, """{"error":1}""", mapOf("content-type" to "application/json", "www-authenticate" to "Bearer realm=\"droidhands\"")) },
        oauth = oauth,
    ).also { it.start() }
    private val base = "http://127.0.0.1:${server.boundPort}"
    private val client = OkHttpClient.Builder().followRedirects(false).build()

    @After
    fun tearDown() = server.stop()

    private fun call(request: Request) = client.newCall(request).execute()

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    private fun raw(request: String): String = Socket("127.0.0.1", server.boundPort).use { s ->
        s.soTimeout = 10_000
        s.getOutputStream().write(request.toByteArray())
        s.shutdownOutput()
        s.getInputStream().readBytes().toString(Charsets.UTF_8)
    }

    @Test
    fun unauthorizedMcpPointsAtTheMetadata() {
        call(Request.Builder().url("$base/mcp").post("{}".toRequestBody("application/json".toMediaType())).build()).use {
            assertEquals(401, it.code)
            assertEquals(
                "Bearer realm=\"droidhands\", resource_metadata=\"$base/.well-known/oauth-protected-resource/mcp\"",
                it.headers("WWW-Authenticate").single(),
            )
        }
        // A Host that does not name this phone keeps the plain challenge.
        val spoofed = raw("POST /mcp HTTP/1.1\r\nHost: evil.example\r\nContent-Length: 2\r\n\r\n{}")
        assertTrue(spoofed.contains("www-authenticate: Bearer realm=\"droidhands\"\r\n"), spoofed)
    }

    @Test
    fun fullFlowOverHttp() {
        val prm = json(call(Request.Builder().url("$base/.well-known/oauth-protected-resource/mcp").build()).body!!.string())
        assertEquals("$base/mcp", prm["resource"]!!.jsonPrimitive.content)
        val registration = call(
            Request.Builder().url("$base/oauth/register")
                .post("""{"client_name":"Test","redirect_uris":["http://localhost:4000/cb"]}""".toRequestBody("application/json".toMediaType())).build(),
        )
        assertEquals(201, registration.code)
        val clientId = json(registration.body!!.string())["client_id"]!!.jsonPrimitive.content

        val verifier = "v".repeat(50)
        val challenge = Encoding.base64Url(Encoding.sha256(verifier.toByteArray()))
        val query = listOf(
            "response_type" to "code", "client_id" to clientId, "redirect_uri" to "http://localhost:4000/cb", "code_challenge" to challenge,
            "code_challenge_method" to "S256", "state" to "st", "resource" to "$base/mcp",
        ).joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val page = call(Request.Builder().url("$base/oauth/authorize?$query").build()).body!!.string()
        val poll = assertNotNull(Regex("""/oauth/pair/[^"'\s?]+\?t=[A-Za-z0-9_-]+""").find(page)).value

        decision.complete(PairingDecision.Approved("pb1.token", "tid"))
        var redirect: String? = null
        val deadline = System.currentTimeMillis() + 10_000
        while (redirect == null && System.currentTimeMillis() < deadline) {
            redirect = json(call(Request.Builder().url("$base$poll").build()).body!!.string())["redirect"]?.jsonPrimitive?.content
            if (redirect == null) Thread.sleep(20)
        }
        val code = assertNotNull(redirect).substringAfter("code=").substringBefore('&')

        val token = call(
            Request.Builder().url("$base/oauth/token").post(
                FormBody.Builder().add("grant_type", "authorization_code").addEncoded("code", code).add("redirect_uri", "http://localhost:4000/cb")
                    .add("client_id", clientId).add("code_verifier", verifier).build(),
            ).build(),
        )
        assertEquals(200, token.code)
        assertEquals("pb1.token", json(token.body!!.string())["access_token"]!!.jsonPrimitive.content)
        // Reported once the response was written.
        val done = assertNotNull(finished.poll(10, TimeUnit.SECONDS))
        assertEquals("tid" to true, done.second to done.third)
    }

    @Test
    fun originAllowlistStillApplies() {
        val response = raw("GET /.well-known/oauth-authorization-server HTTP/1.1\r\nHost: 127.0.0.1\r\nOrigin: http://evil.example\r\n\r\n")
        assertTrue(response.startsWith("HTTP/1.1 403"), response)
    }

    @Test
    fun headHasNoBody() {
        val response = raw("HEAD /.well-known/oauth-authorization-server HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
        assertTrue(response.startsWith("HTTP/1.1 200"), response)
        assertTrue(response.endsWith("\r\n\r\n"), "no body after the headers: $response")
        assertTrue(Regex("Content-Length: [1-9]").containsMatchIn(response))
    }

    @Test
    fun oversizedRegistrationIsRefused() {
        val body = "x".repeat(LocalOAuth.MAX_BODY_BYTES + 1)
        val response = raw("POST /oauth/register HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: ${body.length}\r\n\r\n$body")
        assertTrue(response.startsWith("HTTP/1.1 413"), response.take(200))
    }

    @Test
    fun withoutOAuthThePathsAreUnknown() {
        val plain = LocalHttpServer(port = 0, handler = { TransportResponse(401, "{}") }).also { it.start() }
        try {
            val response = Socket("127.0.0.1", plain.boundPort).use { s ->
                s.getOutputStream().write("GET /.well-known/oauth-authorization-server HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
                s.shutdownOutput()
                s.getInputStream().readBytes().toString(Charsets.UTF_8)
            }
            assertTrue(response.startsWith("HTTP/1.1 404"), response)
        } finally {
            plain.stop()
        }
    }

    @Test
    fun stoppingTheServerCancelsOpenPrompts() {
        val verifier = "v".repeat(50)
        val clientId = json(
            call(
                Request.Builder().url("$base/oauth/register")
                    .post("""{"redirect_uris":["http://localhost:4000/cb"]}""".toRequestBody("application/json".toMediaType())).build(),
            ).body!!.string(),
        )["client_id"]!!.jsonPrimitive.content
        val challenge = Encoding.base64Url(Encoding.sha256(verifier.toByteArray()))
        call(Request.Builder().url("$base/oauth/authorize?response_type=code&client_id=$clientId&code_challenge=$challenge&code_challenge_method=S256").build()).close()
        assertEquals(1, oauth.pendingCount)
        server.stop()
        assertEquals(0, oauth.pendingCount)
    }
}
