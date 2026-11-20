package com.droidhands.core.account

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AccountClientTest {
    private val server = MockWebServer().apply { start() }
    private val base = server.url("/").toString().trimEnd('/')
    private val client = AccountClient(base)

    @After
    fun tearDown() = server.shutdown()

    private fun json(code: Int, body: String) =
        server.enqueue(MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body))

    @Test
    fun pkceMatchesTheRfcExample() {
        // base64url(SHA-256("dBjftJeZ4CVP-mJ92IqVhlRVWeJjvCnXrnqDbRvqVZn")), computed independently.
        assertEquals("djWC_Af3cAypxPgYos4jSdwmSfNUXiZjRlwgWoeSOPU", Pkce.challenge("dBjftJeZ4CVP-mJ92IqVhlRVWeJjvCnXrnqDbRvqVZn"))
        val verifier = Pkce.newVerifier()
        assertEquals(43, verifier.length)
        assertTrue(verifier.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test
    fun signInUrlCarriesTheChallenge() {
        val url = client.signInUrl("abc").toHttpUrl()
        assertEquals("/app/sign-in", url.encodedPath)
        assertEquals("abc", url.queryParameter("code_challenge"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
    }

    @Test
    fun exchangeReturnsTheSession() = runBlocking {
        json(200, """{"sessionToken":"s.sig","user":{"id":"u1","name":"Ada","email":"ada@example.com","image":null,"extra":1}}""")
        val session = assertIs<AccountResult.Ok<AccountSession>>(client.exchange("c0de", "v3rifier")).value
        assertEquals("s.sig", session.token)
        assertEquals(AccountUser("u1", "Ada", "ada@example.com", null), session.user)
        assertTrue("s.sig" !in session.toString())
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("/app/token", request.path)
        assertEquals("""{"code":"c0de","code_verifier":"v3rifier"}""", request.body.readUtf8())
    }

    @Test
    fun refusedCodeKeepsTheServersReason() = runBlocking {
        json(400, """{"error":"invalid_grant","error_description":"The sign-in code expired."}""")
        val failed = assertIs<AccountResult.Failed>(client.exchange("old", "v"))
        assertEquals("invalid_grant", failed.error)
        assertEquals("The sign-in code expired.", failed.message)
    }

    @Test
    fun bearerCallsAndSignedOut() = runBlocking {
        json(200, """{"token":"jwt","expiresAt":1700000000000}""")
        assertEquals(RelayToken("jwt", 1_700_000_000_000), assertIs<AccountResult.Ok<RelayToken>>(client.relayToken("s")).value)
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("Bearer s", request.getHeader("Authorization"))
        assertEquals("/app/relay-token", request.path)

        json(401, """{"error":"unauthorized"}""")
        assertEquals(AccountResult.SignedOut, client.me("s"))
    }

    @Test
    fun signOutAcceptsNoContent() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        assertEquals(AccountResult.Ok(Unit), client.signOut("s"))
        server.enqueue(MockResponse().setResponseCode(204))
        assertEquals(AccountResult.Ok(Unit), client.deleteAccount("s"))
        assertEquals("/app/sign-out", server.takeRequest(5, TimeUnit.SECONDS)!!.path)
        assertEquals("/app/delete-account", server.takeRequest(5, TimeUnit.SECONDS)!!.path)
    }

    @Test
    fun serverErrorsAndGarbageFail() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody("<html>"))
        assertIs<AccountResult.Failed>(client.me("s"))
        json(200, """{"user":{"name":"no id"}}""")
        assertIs<AccountResult.Failed>(client.me("s"))
        server.shutdown()
        assertIs<AccountResult.Failed>(client.me("s"))
        Unit
    }
}
