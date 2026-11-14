package dev.phonebridge.core.oauth

import dev.phonebridge.core.util.Encoding
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OAuthClientsTest {
    private val clients = OAuthClients(ByteArray(32) { 1 })

    private fun register(json: String) = clients.register(Json.parseToJsonElement(json), 1_700_000_000_000)

    @Test
    fun registersAndDecodesStatelessClients() {
        val result = assertIs<OAuthClients.Registration.Ok>(
            register("""{"client_name":"Claude","redirect_uris":["https://claude.ai/api/mcp/auth_callback","http://localhost:33418/cb","http://localhost:33418/cb"],"token_endpoint_auth_method":"client_secret_basic","grant_types":["authorization_code","refresh_token"]}"""),
        )
        val body = result.body()
        assertEquals("none", body["token_endpoint_auth_method"]!!.jsonPrimitive.content)
        assertEquals(listOf("authorization_code"), body["grant_types"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(2, body["redirect_uris"]!!.jsonArray.size, "duplicates removed")
        assertEquals(1_700_000_000L, body["client_id_issued_at"]!!.jsonPrimitive.content.toLong())
        assertEquals(result.client, clients.decode(result.clientId))
        // The relay's format: base64url(json).base64url(mac).
        val payload = String(Encoding.base64UrlDecode(result.clientId.substringBefore('.')))
        assertEquals("""{"n":"Claude","r":["https://claude.ai/api/mcp/auth_callback","http://localhost:33418/cb"],"t":1700000000}""", payload)
    }

    @Test
    fun tamperedOrForeignClientIdsAreRejected() {
        val ok = assertIs<OAuthClients.Registration.Ok>(register("""{"client_name":"A","redirect_uris":["https://a.example/cb"]}"""))
        val (payload, mac) = ok.clientId.split('.')
        val forgedPayload = Encoding.base64Url("""{"n":"A","r":["https://evil.example/cb"],"t":1}""".toByteArray())
        assertNull(clients.decode("$forgedPayload.$mac"))
        assertNull(clients.decode("$payload.${mac.reversed()}"))
        assertNull(clients.decode(payload))
        assertNull(clients.decode("$payload.$mac.x"))
        assertNull(clients.decode(""))
        assertNull(clients.decode("a".repeat(5000)))
        assertNull(OAuthClients(ByteArray(32) { 2 }).decode(ok.clientId))
    }

    @Test
    fun registrationErrors() {
        fun error(json: String) = assertIs<OAuthClients.Registration.Failed>(register(json))
        assertEquals("invalid_redirect_uri", error("""{"redirect_uris":[]}""").error)
        assertEquals("invalid_redirect_uri", error("""{}""").error)
        assertEquals("invalid_redirect_uri", error("""{"redirect_uris":[1]}""").error)
        assertEquals("invalid_redirect_uri", error("""{"redirect_uris":["http://evil.example/cb"]}""").error)
        assertEquals("invalid_redirect_uri", error("""{"redirect_uris":["https://a/1","https://a/2","https://a/3","https://a/4","https://a/5","https://a/6"]}""").error)
        assertEquals("invalid_client_metadata", error("""{"client_name":5,"redirect_uris":["https://a.example/cb"]}""").error)
        assertEquals("invalid_client_metadata", error("""{"redirect_uris":["https://a.example/cb"],"grant_types":["implicit"]}""").error)
        assertEquals("invalid_client_metadata", error("""{"redirect_uris":["https://a.example/cb"],"response_types":["token"]}""").error)
        assertEquals("invalid_client_metadata", error("""[]""").error)
    }

    @Test
    fun namesAreSanitized() {
        assertEquals("Unnamed app", OAuthClients.sanitizeName(null))
        assertEquals("Unnamed app", OAuthClients.sanitizeName(" ‮\u0000 "))
        assertEquals("Claude Code", OAuthClients.sanitizeName("Claude\n‮Code"))
        assertEquals("x".repeat(60), OAuthClients.sanitizeName("x".repeat(100)))
        assertEquals("😀".repeat(60), OAuthClients.sanitizeName("😀".repeat(61)), "truncated by code point")
    }

    @Test
    fun redirectUriRules() {
        for (good in listOf("https://claude.ai/cb", "http://localhost:1234/cb", "http://127.0.0.1/cb", "http://[::1]:5/cb", "com.example.app:/cb", "cursor://anysphere.cursor-mcp/oauth")) {
            assertNull(RedirectUris.problem(good), good)
        }
        for (bad in listOf(
            "", "/relative", "http://example.com/cb", "http://192.168.1.2/cb", "https://claude.ai/cb#frag", "https://user:pw@claude.ai/cb",
            "javascript:alert(1)", "data:text/html,x", "file:///etc/passwd", "intent://x#Intent;end", "content://x", "ws://localhost/cb",
            "https://claude.ai/c b", "https://claude.ai/\u0000", "x".repeat(600),
        )) {
            assertTrue(RedirectUris.problem(bad) != null, bad)
        }
    }

    @Test
    fun loopbackRedirectsMatchOnAnyPort() {
        val registered = listOf("http://localhost:33418/callback", "https://claude.ai/cb")
        assertTrue(RedirectUris.matches(registered, "http://localhost:50000/callback"))
        assertTrue(RedirectUris.matches(registered, "http://localhost/callback"))
        assertTrue(RedirectUris.matches(registered, "https://claude.ai/cb"))
        assertFalse(RedirectUris.matches(registered, "http://localhost:50000/other"))
        assertFalse(RedirectUris.matches(registered, "http://127.0.0.1:33418/callback"))
        assertFalse(RedirectUris.matches(registered, "https://claude.ai:444/cb"))
    }

    @Test
    fun redirectParamsAndDisplayHost() {
        assertEquals("http://localhost:1/cb?code=a%2Bb&state=s+1", RedirectUris.withParams("http://localhost:1/cb", listOf("code" to "a+b", "state" to "s 1", "iss" to null)))
        assertEquals("https://a.example/cb?x=1&code=c", RedirectUris.withParams("https://a.example/cb?x=1", listOf("code" to "c")))
        assertEquals("localhost:33418", RedirectUris.displayHost("http://localhost:33418/cb"))
        assertEquals("claude.ai", RedirectUris.displayHost("https://Claude.AI/cb"))
        assertEquals("cursor", RedirectUris.displayHost("cursor://anysphere.cursor-mcp/oauth"))
        assertNotNull(RedirectUris.kind("com.example.app:/cb"))
    }
}
