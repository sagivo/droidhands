package dev.phonebridge.core.oauth

import dev.phonebridge.core.transport.PairingDecision
import dev.phonebridge.core.transport.PairingHandler
import dev.phonebridge.core.transport.PairingRequest
import dev.phonebridge.core.util.Encoding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.net.InetAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LocalOAuthTest {
    /** Records prompts; each waits for [decide] (or cancellation). */
    private class FakeHandler : PairingHandler {
        val requests = CopyOnWriteArrayList<PairingRequest>()
        val finished = CopyOnWriteArrayList<Triple<String, String?, Boolean>>()
        val cancelled = CopyOnWriteArrayList<String>()
        private val answers = ConcurrentHashMap<String, CompletableDeferred<PairingDecision>>()
        private var issued = 0

        override suspend fun onPairRequest(request: PairingRequest): PairingDecision {
            requests += request
            try {
                return answer(request.id).await()
            } catch (e: CancellationException) {
                cancelled += request.id
                throw e
            }
        }

        override fun onPairFinished(id: String, tokenId: String?, completed: Boolean) {
            finished += Triple(id, tokenId, completed)
        }

        fun answer(id: String): CompletableDeferred<PairingDecision> = answers.getOrPut(id) { CompletableDeferred() }

        /** Approves [id] with a fresh token; returns (token, tokenId). */
        fun approve(id: String): Pair<String, String> {
            issued++
            val token = "pb1.fake-token-$issued"
            val tokenId = "tokenid$issued"
            answer(id).complete(PairingDecision.Approved(token, tokenId))
            return token to tokenId
        }
    }

    private val scheduler = TestCoroutineScheduler()
    private val handler = FakeHandler()
    private val key = ByteArray(32) { it.toByte() }
    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")

    private fun oauth(config: LocalOAuth.Config = LocalOAuth.Config()) = LocalOAuth(
        pairing = handler,
        clientKey = key,
        deviceName = { "Pixel 8" },
        config = config,
        clock = { START + scheduler.currentTime },
        context = StandardTestDispatcher(scheduler),
    )

    private val server = oauth()

    private fun request(
        method: String,
        path: String,
        query: String = "",
        body: String = "",
        host: String? = HOST,
        local: InetAddress = loopback,
        remote: String = "127.0.0.1",
        contentType: String? = null,
        authorization: String? = null,
        target: LocalOAuth = server,
    ): LocalOAuth.Response = target.handle(
        LocalOAuth.Request(
            method = method,
            path = path,
            query = query,
            host = host,
            localAddress = local,
            remoteAddress = remote,
            contentType = contentType,
            authorization = authorization,
            userAgent = "Mozilla/5.0 (test)",
            body = body,
        ),
    ).also { scheduler.runCurrent() }

    private fun LocalOAuth.Response.json(): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun JsonObject.str(name: String): String? = this[name]?.jsonPrimitive?.content

    private fun register(redirect: String = REDIRECT, target: LocalOAuth = server, name: String = "Claude Code"): String {
        val response = request(
            "POST",
            "/oauth/register",
            body = """{"client_name":"$name","redirect_uris":["$redirect"],"token_endpoint_auth_method":"none"}""",
            contentType = "application/json",
            target = target,
        )
        assertEquals(201, response.status, response.text)
        return assertNotNull(response.json().str("client_id"))
    }

    private class Flow(val clientId: String, val verifier: String, val state: String) {
        val challenge: String = Encoding.base64Url(Encoding.sha256(verifier.toByteArray()))
    }

    private var flows = 0

    private fun newFlow(clientId: String) = flows++.let { Flow(clientId, "verifier-${"x".repeat(40)}-$it", "state$it") }

    private fun query(vararg params: Pair<String, String?>): String =
        params.filter { it.second != null }.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }

    private fun authorizeQuery(flow: Flow, redirect: String? = REDIRECT, resource: String? = RESOURCE) = query(
        "response_type" to "code",
        "client_id" to flow.clientId,
        "redirect_uri" to redirect,
        "code_challenge" to flow.challenge,
        "code_challenge_method" to "S256",
        "state" to flow.state,
        "resource" to resource,
        "scope" to "phone",
    )

    private fun authorize(flow: Flow, redirect: String? = REDIRECT, remote: String = "127.0.0.1", target: LocalOAuth = server) =
        request("GET", "/oauth/authorize", authorizeQuery(flow, redirect), remote = remote, target = target)

    /** The waiting page's poll URL (path and query). */
    private fun pollUrl(page: LocalOAuth.Response): String {
        assertEquals(200, page.status, page.text)
        return assertNotNull(Regex("data-poll=\"([^\"]+)\"").find(page.text)).groupValues[1].replace("&amp;", "&")
    }

    private fun poll(url: String, target: LocalOAuth = server): LocalOAuth.Response =
        request("GET", url.substringBefore('?'), url.substringAfter('?'), target = target)

    private fun params(redirect: String): Map<String, String> =
        URI(redirect).rawQuery.split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }

    private fun exchange(
        flow: Flow,
        code: String,
        verifier: String = flow.verifier,
        redirect: String? = REDIRECT,
        resource: String? = RESOURCE,
        clientId: String? = flow.clientId,
        authorization: String? = null,
        target: LocalOAuth = server,
    ) = request(
        "POST",
        "/oauth/token",
        body = query(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to redirect,
            "client_id" to clientId,
            "code_verifier" to verifier,
            "resource" to resource,
        ),
        contentType = "application/x-www-form-urlencoded",
        authorization = authorization,
        target = target,
    )

    /** Authorizes and approves a new flow; returns it, its code and the token the phone issued. */
    private fun approvedCode(clientId: String = register()): Triple<Flow, String, Pair<String, String>> {
        val flow = newFlow(clientId)
        val url = pollUrl(authorize(flow))
        val issued = handler.approve(handler.requests.last().id)
        scheduler.runCurrent()
        val status = poll(url).json()
        assertEquals("approved", status.str("status"))
        val code = assertNotNull(params(assertNotNull(status.str("redirect")))["code"])
        return Triple(flow, code, issued)
    }

    @Test
    fun fullHappyPath() {
        // 401 → metadata.
        assertEquals(
            "Bearer realm=\"phonebridge\", resource_metadata=\"http://$HOST/.well-known/oauth-protected-resource/mcp\"",
            server.challenge(HOST, loopback),
        )
        val prm = request("GET", "/.well-known/oauth-protected-resource/mcp").json()
        assertEquals(RESOURCE, prm.str("resource"))
        assertEquals(listOf(ISSUER), prm["authorization_servers"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(prm, request("GET", "/.well-known/oauth-protected-resource").json())
        val asMeta = request("GET", "/.well-known/oauth-authorization-server").json()
        assertEquals(ISSUER, asMeta.str("issuer"))
        assertEquals("$ISSUER/oauth/authorize", asMeta.str("authorization_endpoint"))
        assertEquals("$ISSUER/oauth/token", asMeta.str("token_endpoint"))
        assertEquals("$ISSUER/oauth/register", asMeta.str("registration_endpoint"))
        assertEquals(listOf("S256"), asMeta["code_challenge_methods_supported"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(asMeta, request("GET", "/.well-known/openid-configuration").json())

        // Registration, then the authorization request prompts the phone.
        val flow = newFlow(register())
        val page = authorize(flow)
        val url = pollUrl(page)
        assertEquals("text/html; charset=utf-8", page.headers["content-type"])
        assertContains(page.headers["content-security-policy"]!!, "default-src 'none'")
        assertEquals("DENY", page.headers["x-frame-options"])
        val prompt = handler.requests.single()
        assertContains(page.text, ">${prompt.displayCode}<")
        assertContains(page.text, "Pixel 8")
        assertTrue(Regex("^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{4}$").matches(prompt.displayCode))
        assertTrue(Regex("^p_[A-Za-z0-9_-]{22}$").matches(prompt.id))
        assertEquals("Claude Code", prompt.clientName)
        assertEquals("localhost:33418", prompt.redirectHost)
        assertEquals("127.0.0.1", prompt.remoteAddress)
        assertEquals("Mozilla/5.0 (test)", prompt.userAgent)
        assertEquals(START + 180_000, prompt.expiresAt)
        assertTrue(url.startsWith("/oauth/pair/${prompt.id}?t="))

        val pending = poll(url).json()
        assertEquals("pending", pending.str("status"))
        assertNull(pending["redirect"])
        // The noscript view is the waiting page itself until decided.
        assertEquals(200, poll("$url&view=html").status)

        // Allow on the phone → redirect with code, state and iss.
        val (token, tokenId) = handler.approve(prompt.id)
        scheduler.runCurrent()
        val approved = poll(url).json()
        assertEquals("approved", approved.str("status"))
        val redirect = approved.str("redirect")!!
        assertTrue(redirect.startsWith("$REDIRECT?code="), redirect)
        val back = params(redirect)
        assertEquals(flow.state, back["state"])
        assertEquals(ISSUER, back["iss"])
        val html = poll("$url&view=html")
        assertEquals(303, html.status)
        assertEquals(redirect, html.headers["location"])

        // Token exchange.
        val response = exchange(flow, back.getValue("code"))
        assertEquals(200, response.status, response.text)
        assertEquals("no-cache", response.headers["pragma"])
        val body = response.json()
        assertEquals(token, body.str("access_token"))
        assertEquals("Bearer", body.str("token_type"))
        assertEquals("phone", body.str("scope"))
        assertTrue(handler.finished.isEmpty(), "completion waits for the response to be written")
        response.onSent!!(true)
        assertEquals<List<Triple<String, String?, Boolean>>>(listOf(Triple(prompt.id, tokenId, true)), handler.finished)

        // Single use.
        val again = exchange(flow, back.getValue("code"))
        assertEquals(400, again.status)
        assertEquals("invalid_grant", again.json().str("error"))
        assertEquals(1, handler.finished.size)
        assertEquals(0, server.codeCount)
    }

    @Test
    fun undeliveredTokenResponseIsAbandoned() {
        val (flow, code, issued) = approvedCode()
        val response = exchange(flow, code)
        assertEquals(200, response.status)
        response.onSent!!(false)
        assertEquals(Triple(handler.requests.single().id, issued.second, false), handler.finished.single())
    }

    @Test
    fun pkceIsRequired() {
        val clientId = register()
        val flow = newFlow(clientId)
        val noChallenge = request("GET", "/oauth/authorize", authorizeQuery(flow).replace(Regex("code_challenge=[^&]+&"), ""))
        assertEquals(400, noChallenge.status)
        assertContains(noChallenge.text, "PKCE")
        val plain = request("GET", "/oauth/authorize", authorizeQuery(flow).replace("code_challenge_method=S256", "code_challenge_method=plain"))
        assertEquals(400, plain.status)
        val shortChallenge = request("GET", "/oauth/authorize", authorizeQuery(flow).replace(flow.challenge, "abc"))
        assertEquals(400, shortChallenge.status)
        assertTrue(handler.requests.isEmpty(), "invalid requests never prompt the phone")
    }

    @Test
    fun wrongVerifierBurnsTheCodeAndAbandonsTheToken() {
        val (flow, code, issued) = approvedCode()
        val wrong = exchange(flow, code, verifier = "y".repeat(43))
        assertEquals(400, wrong.status)
        assertEquals("invalid_grant", wrong.json().str("error"))
        assertContains(wrong.json().str("error_description")!!, "code_verifier")
        assertEquals(Triple(handler.requests.single().id, issued.second, false), handler.finished.single())
        // The right verifier is too late now.
        assertEquals("invalid_grant", exchange(flow, code).json().str("error"))
        // Malformed verifiers fail the same way.
        val (flow2, code2, _) = approvedCode()
        assertEquals("invalid_grant", exchange(flow2, code2, verifier = "short").json().str("error"))
    }

    @Test
    fun redirectUriMustBeRegistered() {
        val clientId = register()
        val other = request("GET", "/oauth/authorize", authorizeQuery(newFlow(clientId), redirect = "https://evil.example/cb"))
        assertEquals(400, other.status)
        assertContains(other.text, "did not register")
        assertFalse(other.headers.containsKey("location"), "errors are never redirected")
        assertTrue(handler.requests.isEmpty())

        // Loopback redirects match on any port (RFC 8252 §7.3), and may be omitted with one registration.
        assertEquals(200, authorize(newFlow(clientId), redirect = "http://localhost:50000/callback").status)
        assertEquals(200, authorize(newFlow(clientId), redirect = null).status)
        assertEquals(2, handler.requests.size)
    }

    @Test
    fun tokenRequestMustRepeatTheRedirectUri() {
        val (flow, code, issued) = approvedCode()
        val response = exchange(flow, code, redirect = "http://localhost:33418/other")
        assertEquals("invalid_grant", response.json().str("error"))
        assertEquals(issued.second, handler.finished.single().second)
        assertFalse(handler.finished.single().third)

        val (flow2, code2, _) = approvedCode()
        assertEquals("invalid_grant", exchange(flow2, code2, redirect = null).json().str("error"), "registered with redirect_uri: required")
    }

    @Test
    fun codeOfAnotherClientIsRefused() {
        val (_, code, _) = approvedCode()
        val other = newFlow(register(name = "Other"))
        assertEquals("invalid_grant", exchange(other, code, verifier = other.verifier).json().str("error"))
        assertFalse(handler.finished.single().third)
    }

    @Test
    fun clientIdMayComeFromBasicAuth() {
        val (flow, code, _) = approvedCode()
        val basic = "Basic " + java.util.Base64.getEncoder().encodeToString("${flow.clientId}:".toByteArray())
        assertEquals(200, exchange(flow, code, clientId = null, authorization = basic).status)

        val (flow2, code2, _) = approvedCode()
        val conflicting = "Basic " + java.util.Base64.getEncoder().encodeToString("someone-else:".toByteArray())
        val response = exchange(flow2, code2, authorization = conflicting)
        assertEquals("invalid_request", response.json().str("error"))
    }

    @Test
    fun expiredCodeIsAbandoned() {
        val (flow, code, issued) = approvedCode()
        scheduler.advanceTimeBy(120_001)
        scheduler.runCurrent()
        assertEquals(Triple(handler.requests.single().id, issued.second, false), handler.finished.single(), "abandoned when it expires")
        assertEquals("invalid_grant", exchange(flow, code).json().str("error"))
        assertEquals(1, handler.finished.size)
    }

    @Test
    fun unknownCodeAndClientAreRefused() {
        val (flow, _, _) = approvedCode()
        assertEquals("invalid_grant", exchange(flow, "not-a-code").json().str("error"))
        val forged = flow.clientId.dropLast(2) + "AA"
        assertEquals("invalid_client", exchange(flow, "x", clientId = forged).json().str("error"))
        assertEquals("unsupported_grant_type", request("POST", "/oauth/token", body = "grant_type=refresh_token", contentType = "application/x-www-form-urlencoded").json().str("error"))
        assertEquals("invalid_request", request("POST", "/oauth/token", body = "{}", contentType = "application/json").json().str("error"))
        assertEquals("invalid_request", request("POST", "/oauth/token", body = "grant_type=authorization_code&grant_type=x", contentType = "application/x-www-form-urlencoded").json().str("error"))
        assertTrue(handler.finished.isEmpty())
    }

    @Test
    fun forgedOrForeignClientCannotAuthorize() {
        val flow = newFlow(register())
        val forged = Flow(flow.clientId.replaceFirst('e', 'f'), flow.verifier, flow.state)
        assertEquals(400, authorize(forged).status)
        // A client registered with another key (another phone, or before a reset).
        val foreign = OAuthClients(ByteArray(32) { 7 }).encode(RegisteredClient("Claude", listOf(REDIRECT), 0))
        val page = authorize(Flow(foreign, flow.verifier, flow.state))
        assertEquals(400, page.status)
        assertContains(page.text, "registration is not valid")
        assertTrue(handler.requests.isEmpty())
    }

    @Test
    fun repeatedParametersAndBadResponseTypeAreRefused() {
        val flow = newFlow(register())
        assertEquals(400, request("GET", "/oauth/authorize", authorizeQuery(flow) + "&state=again").status)
        assertEquals(400, request("GET", "/oauth/authorize", authorizeQuery(flow).replace("response_type=code", "response_type=token")).status)
        assertEquals(400, request("GET", "/oauth/authorize", "%zz=1").status)
        assertEquals(405, request("HEAD", "/oauth/authorize", authorizeQuery(flow)).status, "HEAD never prompts")
        assertTrue(handler.requests.isEmpty())
    }

    @Test
    fun resourceMustNameThisServer() {
        val clientId = register()
        val foreign = request("GET", "/oauth/authorize", authorizeQuery(newFlow(clientId), resource = "https://relay.example/d/abc/mcp"))
        assertEquals(400, foreign.status)
        assertContains(foreign.text, "does not belong to this phone")
        assertEquals(400, request("GET", "/oauth/authorize", authorizeQuery(newFlow(clientId), resource = "$RESOURCE?x=1")).status)
        // Equivalent spellings, the bare base, and no resource at all are fine.
        assertEquals(200, request("GET", "/oauth/authorize", authorizeQuery(newFlow(clientId), resource = "HTTP://127.0.0.1:8765/mcp/")).status)
        assertEquals(200, request("GET", "/oauth/authorize", authorizeQuery(newFlow(clientId), resource = ISSUER), remote = "127.0.0.2").status)
        assertEquals(200, request("GET", "/oauth/authorize", authorizeQuery(newFlow(clientId), resource = null), remote = "127.0.0.3").status)
        handler.requests.forEach { handler.answer(it.id).complete(PairingDecision.Denied("denied")) }
        scheduler.runCurrent()

        val (flow, code, _) = approvedCode(clientId)
        val response = exchange(flow, code, resource = "http://127.0.0.1:9999/mcp")
        assertEquals("invalid_target", response.json().str("error"))
        assertFalse(handler.finished.single().third)
    }

    @Test
    fun deniedAndBusyAnswersRedirectWithAccessDenied() {
        val clientId = register()
        val flow = newFlow(clientId)
        val url = pollUrl(authorize(flow))
        handler.answer(handler.requests.single().id).complete(PairingDecision.Denied("denied"))
        scheduler.runCurrent()
        val status = poll(url).json()
        assertEquals("denied", status.str("status"))
        val back = params(status.str("redirect")!!)
        assertEquals("access_denied", back["error"])
        assertEquals(flow.state, back["state"])
        assertEquals(ISSUER, back["iss"])
        assertEquals(0, server.codeCount)
        assertTrue(handler.finished.isEmpty())
    }

    @Test
    fun unansweredPromptExpiresAndIsCancelled() {
        val flow = newFlow(register())
        val url = pollUrl(authorize(flow))
        val id = handler.requests.single().id
        scheduler.advanceTimeBy(180_001)
        scheduler.runCurrent()
        val status = poll(url).json()
        assertEquals("expired", status.str("status"))
        assertEquals("access_denied", params(status.str("redirect")!!)["error"])
        assertEquals(listOf(id), handler.cancelled)
        assertEquals(0, server.pendingCount)
    }

    @Test
    fun approvalAfterExpiryIsReportedAbandoned() {
        // The phone's clock passes the deadline before the prompt's own timer fires.
        var offset = 0L
        val target = LocalOAuth(handler, key, clock = { START + scheduler.currentTime + offset }, context = StandardTestDispatcher(scheduler))
        val flow = newFlow(register(target = target))
        val url = pollUrl(request("GET", "/oauth/authorize", authorizeQuery(flow), target = target))
        val id = handler.requests.single().id
        offset = 200_000
        val (_, tokenId) = handler.approve(id)
        scheduler.runCurrent()
        assertEquals(Triple(id, tokenId, false), handler.finished.single(), "nobody can redeem it: the phone deletes it")
        assertEquals(0, target.codeCount)
        assertEquals("expired", poll(url, target = target).json().str("status"))
    }

    @Test
    fun pollingAnExpiredPairingCancelsThePrompt() {
        var offset = 0L
        val target = LocalOAuth(handler, key, clock = { START + scheduler.currentTime + offset }, context = StandardTestDispatcher(scheduler))
        val flow = newFlow(register(target = target))
        val url = pollUrl(request("GET", "/oauth/authorize", authorizeQuery(flow), target = target))
        val id = handler.requests.single().id
        offset = 200_000
        assertEquals("expired", poll(url, target = target).json().str("status"))
        assertEquals(listOf(id), handler.cancelled)
        assertEquals(0, target.pendingCount)
    }

    @Test
    fun sameFlowReusesThePairing() {
        val flow = newFlow(register())
        val first = pollUrl(authorize(flow))
        val second = pollUrl(authorize(flow))
        assertEquals(first, second)
        assertEquals(1, handler.requests.size)
    }

    @Test
    fun pendingCapsOverallAndPerAddress() {
        val clientId = register()
        assertEquals(200, authorize(newFlow(clientId), remote = "10.0.0.1").status)
        assertEquals(200, authorize(newFlow(clientId), remote = "10.0.0.1").status)
        val third = authorize(newFlow(clientId), remote = "10.0.0.1")
        assertEquals(429, third.status, "2 pending per address")
        assertContains(third.text, "from your computer")
        assertEquals(200, authorize(newFlow(clientId), remote = "10.0.0.2").status)
        val overall = authorize(newFlow(clientId), remote = "10.0.0.3")
        assertEquals(429, overall.status, "3 pending in total")
        assertContains(overall.text, "several connection requests")
        assertEquals(3, handler.requests.size)

        // Answering one frees a slot.
        handler.answer(handler.requests.first().id).complete(PairingDecision.Denied("denied"))
        scheduler.runCurrent()
        assertEquals(200, authorize(newFlow(clientId), remote = "10.0.0.3").status)
    }

    @Test
    fun hourlyCaps() {
        val target = oauth(LocalOAuth.Config(maxPerAddressPerHour = 2, maxPerHour = 3))
        val clientId = register(target = target)
        repeat(2) {
            assertEquals(200, authorize(newFlow(clientId), remote = "10.0.0.1", target = target).status)
            handler.answer(handler.requests.last().id).complete(PairingDecision.Denied("denied"))
            scheduler.runCurrent()
        }
        assertEquals(429, authorize(newFlow(clientId), remote = "10.0.0.1", target = target).status)
        assertEquals(200, authorize(newFlow(clientId), remote = "10.0.0.2", target = target).status)
        handler.answer(handler.requests.last().id).complete(PairingDecision.Denied("denied"))
        scheduler.runCurrent()
        val hourly = authorize(newFlow(clientId), remote = "10.0.0.3", target = target)
        assertEquals(429, hourly.status)
        assertContains(hourly.text, "in the last hour")
        scheduler.advanceTimeBy(3_600_001)
        assertEquals(200, authorize(newFlow(clientId), remote = "10.0.0.1", target = target).status)
    }

    @Test
    fun requestBudgetPerAddress() {
        val target = oauth(LocalOAuth.Config(requestsPerMinute = 5))
        repeat(5) { assertEquals(200, request("GET", "/.well-known/oauth-authorization-server", target = target).status) }
        val limited = request("GET", "/.well-known/oauth-authorization-server", target = target)
        assertEquals(429, limited.status)
        assertEquals("60", limited.headers["retry-after"])
        assertEquals(200, request("GET", "/.well-known/oauth-authorization-server", remote = "10.0.0.9", target = target).status)
        scheduler.advanceTimeBy(60_001)
        assertEquals(200, request("GET", "/.well-known/oauth-authorization-server", target = target).status)
    }

    @Test
    fun authFailuresAreLimited() {
        val target = oauth(LocalOAuth.Config(maxAuthFailures = 3))
        val flow = newFlow(register(target = target))
        repeat(3) { assertEquals("invalid_grant", exchange(flow, "guess$it", target = target).json().str("error")) }
        assertEquals(429, exchange(flow, "guess", target = target).status)
        assertEquals(429, request("GET", "/.well-known/oauth-authorization-server", target = target).status)
        scheduler.advanceTimeBy(10 * 60_000L + 1)
        assertEquals(200, request("GET", "/.well-known/oauth-authorization-server", target = target).status)
    }

    @Test
    fun foreignHostsAreRefusedEverywhere() {
        val clientId = register()
        for (host in listOf("evil.example:8765", "192.168.1.20:8765", "phone.local:8765", null)) {
            assertEquals(421, request("GET", "/.well-known/oauth-protected-resource/mcp", host = host).status, "$host")
            assertEquals(421, request("GET", "/.well-known/oauth-authorization-server", host = host).status)
            assertEquals(421, request("POST", "/oauth/register", body = "{}", host = host).status)
            assertEquals(421, request("POST", "/oauth/token", body = "", host = host, contentType = "application/x-www-form-urlencoded").status)
            val page = request("GET", "/oauth/authorize", authorizeQuery(newFlow(clientId)), host = host)
            assertEquals(421, page.status)
            assertContains(page.headers["content-type"]!!, "text/html")
            assertNull(server.challenge(host, loopback))
        }
        assertTrue(handler.requests.isEmpty())
    }

    @Test
    fun urlsFollowTheHostTheAgentUsed() {
        val tailnet = InetAddress.getByName("100.101.102.103")
        val meta = request("GET", "/.well-known/oauth-authorization-server", host = "pixel.tail1234.ts.net:8765", local = tailnet).json()
        assertEquals("http://pixel.tail1234.ts.net:8765", meta.str("issuer"))
        val byIp = request("GET", "/.well-known/oauth-protected-resource/mcp", host = "100.101.102.103:8765", local = tailnet).json()
        assertEquals("http://100.101.102.103:8765/mcp", byIp.str("resource"))
        assertEquals(421, request("GET", "/.well-known/oauth-authorization-server", host = "100.101.102.104:8765", local = tailnet).status)

        val lan = InetAddress.getByName("192.168.1.20")
        assertEquals("http://192.168.1.20:8765", request("GET", "/.well-known/oauth-authorization-server", host = "192.168.1.20:8765", local = lan).json().str("issuer"))
        assertEquals(421, request("GET", "/.well-known/oauth-authorization-server", host = "pixel.tail1234.ts.net:8765", local = lan).status)

        // adb forward: another port on the computer.
        assertEquals("http://localhost:9000", request("GET", "/.well-known/oauth-authorization-server", host = "localhost:9000").json().str("issuer"))
        assertEquals("http://[::1]:9000", request("GET", "/.well-known/oauth-authorization-server", host = "[::1]:9000").json().str("issuer"))
    }

    @Test
    fun codeIsBoundToTheIssuer() {
        // Authorized through 127.0.0.1:8765, exchanged naming the resource as seen through another port.
        val (flow, code, _) = approvedCode()
        assertEquals("invalid_target", exchange(flow, code, resource = "http://localhost:9000/mcp").json().str("error"))
    }

    @Test
    fun pollNeedsThePageToken() {
        val url = pollUrl(authorize(newFlow(register())))
        val wrongToken = url.substringBefore("?t=") + "?t=" + "A".repeat(22)
        assertEquals(404, poll(wrongToken).status)
        assertEquals(404, poll("/oauth/pair/p_unknown?t=x").status)
        assertEquals(404, poll("$wrongToken&view=html").status)
    }

    @Test
    fun registrationValidatesMetadata() {
        val bad = request("POST", "/oauth/register", body = """{"redirect_uris":["javascript:alert(1)"]}""")
        assertEquals(400, bad.status)
        assertEquals("invalid_redirect_uri", bad.json().str("error"))
        assertEquals("invalid_client_metadata", request("POST", "/oauth/register", body = "not json").json().str("error"))
        assertEquals(405, request("GET", "/oauth/register").status)
        assertEquals(404, request("GET", "/oauth/nothing").status)
    }

    @Test
    fun shutdownCancelsPromptsAndAbandonsCodes() {
        val (_, _, issued) = approvedCode()
        pollUrl(authorize(newFlow(register())))
        val pendingId = handler.requests.last().id
        server.shutdown()
        scheduler.runCurrent()
        assertEquals(listOf(pendingId), handler.cancelled)
        assertEquals(Triple(handler.requests.first().id, issued.second, false), handler.finished.single())
        assertEquals(0, server.pendingCount)
        assertEquals(0, server.codeCount)
        // Still usable afterwards.
        assertEquals(200, authorize(newFlow(register()), remote = "10.0.0.5").status)
    }

    @Test
    fun handlerFailureCountsAsBusy() {
        val failing = object : PairingHandler {
            override suspend fun onPairRequest(request: PairingRequest): PairingDecision = error("boom")
            override fun onPairFinished(id: String, tokenId: String?, completed: Boolean) = Unit
        }
        val target = LocalOAuth(failing, key, clock = { START + scheduler.currentTime }, context = StandardTestDispatcher(scheduler))
        val flow = newFlow(register(target = target))
        val url = pollUrl(request("GET", "/oauth/authorize", authorizeQuery(flow), target = target))
        scheduler.runCurrent()
        val status = poll(url, target = target).json()
        assertEquals("denied", status.str("status"))
        assertContains(params(status.str("redirect")!!)["error_description"]!!, "busy")
    }

    @Test
    fun pkceHelper() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertTrue(LocalOAuth.verifyPkce(verifier, "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"), "RFC 7636 Appendix B")
        assertFalse(LocalOAuth.verifyPkce(verifier, "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cN"))
        assertFalse(LocalOAuth.verifyPkce("short", "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"))
    }

    private companion object {
        const val START = 1_700_000_000_000L
        const val HOST = "127.0.0.1:8765"
        const val ISSUER = "http://$HOST"
        const val RESOURCE = "$ISSUER/mcp"
        const val REDIRECT = "http://localhost:33418/callback"
    }
}
