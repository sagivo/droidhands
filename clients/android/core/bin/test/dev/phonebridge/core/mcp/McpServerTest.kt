package dev.phonebridge.core.mcp

import dev.phonebridge.core.audit.AuditEntry
import dev.phonebridge.core.audit.AuditOutcome
import dev.phonebridge.core.audit.InMemoryAuditLog
import dev.phonebridge.core.auth.ConfirmPolicy
import dev.phonebridge.core.auth.DeviceIdentity
import dev.phonebridge.core.auth.InMemoryTokenStore
import dev.phonebridge.core.auth.TokenManager
import dev.phonebridge.core.confirm.ConfirmationProvider
import dev.phonebridge.core.confirm.ConfirmationRequest
import dev.phonebridge.core.confirm.ConfirmationResult
import dev.phonebridge.core.policy.PolicyEngine
import dev.phonebridge.core.policy.RateLimits
import dev.phonebridge.core.tools.Risk
import dev.phonebridge.core.tools.Scopes
import dev.phonebridge.core.tools.ToolException
import dev.phonebridge.core.tools.ToolRegistry
import dev.phonebridge.core.tools.ToolResult
import dev.phonebridge.core.tools.objectSchema
import dev.phonebridge.core.tools.optString
import dev.phonebridge.core.tools.reqString
import dev.phonebridge.core.tools.tool
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpServerTest {
    private val identity = DeviceIdentity(ByteArray(32) { it.toByte() })
    private val tokens = TokenManager(InMemoryTokenStore(), identity.deviceId)
    private val audit = InMemoryAuditLog()
    private val registry = ToolRegistry()
    private val prompts: MutableList<ConfirmationRequest> = Collections.synchronizedList(mutableListOf())

    @Volatile private var accessEnabled: () -> Boolean = { true }

    @Volatile private var answer: suspend (ConfirmationRequest) -> ConfirmationResult = { ConfirmationResult.APPROVED }

    private val sideEffects = AtomicInteger()

    init {
        registry.register(capabilitiesTool(registry))
        registry.register(
            tool(
                name = "echo", title = "Echo", description = "Echoes text", scope = Scopes.DEVICE, risk = Risk.READ,
                schema = objectSchema { string("text", "Text", required = true) },
            ) { args, _ -> ToolResult.text(args.optString("text") ?: "") },
        )
        registry.register(
            tool(name = "app_poke", title = "Poke app", description = "Action", scope = Scopes.APPS, risk = Risk.ACTION) { _, _ ->
                sideEffects.incrementAndGet()
                ToolResult.text("poked")
            },
        )
        registry.register(
            tool(
                name = "send_pre", title = "Send (pre)", description = "Pre-confirmed send", scope = Scopes.MESSAGES_SEND,
                risk = Risk.SENSITIVE, describe = { "Send '${it.reqString("text")}' to ${it.reqString("to")}" },
            ) { _, ctx ->
                ctx.requireConfirmation("must be a no-op for pre-confirmed tools")
                sideEffects.incrementAndGet()
                ToolResult.text("sent")
            },
        )
        registry.register(
            tool(
                name = "send_internal", title = "Send (internal)", description = "Internally confirmed send",
                scope = Scopes.MESSAGES_SEND, risk = Risk.SENSITIVE, confirmsInternally = true, timeoutMs = 1_000,
            ) { _, ctx ->
                ctx.requireConfirmation("Send 'hi' to Tania Cohen +972541234567")
                delay(500) // Real work after approval, within the 1 s budget.
                sideEffects.incrementAndGet()
                ToolResult.text("sent")
            },
        )
        registry.register(
            tool(
                name = "send_skipper", title = "Skipper", description = "Never confirms", scope = Scopes.MESSAGES_SEND,
                risk = Risk.SENSITIVE, confirmsInternally = true,
            ) { _, _ -> ToolResult.text("done") },
        )
        registry.register(
            tool(name = "slow", title = "Slow", description = "Hangs", scope = Scopes.DEVICE, risk = Risk.READ, timeoutMs = 1_000) { _, _ ->
                awaitCancellation()
            },
        )
        registry.register(
            tool(name = "fails", title = "Fails", description = "d", scope = Scopes.DEVICE, risk = Risk.READ) { _, _ ->
                throw ToolException("No contact named 'Bob'. Ask the user for the number.")
            },
        )
        registry.register(
            tool(name = "crashes", title = "Crashes", description = "d", scope = Scopes.DEVICE, risk = Risk.READ) { _, _ ->
                throw IllegalStateException("boom")
            },
        )
        registry.register(
            tool(name = "soft_error", title = "Soft", description = "d", scope = Scopes.DEVICE, risk = Risk.READ) { _, _ ->
                ToolResult.error("Nothing to do")
            },
        )
        registry.register(
            tool(name = "picture", title = "Picture", description = "d", scope = Scopes.SCREEN_READ, risk = Risk.READ) { _, _ ->
                ToolResult.image("iVBORw0KGgo=", "image/png", caption = "1x1")
            },
        )
        registry.register(
            tool(
                name = "needs_perm", title = "Needs permission", description = "Base description", scope = Scopes.DEVICE,
                risk = Risk.READ, unavailable = { "Permission READ_SMS not granted." },
            ) { _, _ -> ToolResult.text("x") },
        )
        registry.register(
            tool(
                name = "broken_check", title = "Broken check", description = "Desc", scope = Scopes.DEVICE, risk = Risk.READ,
                unavailable = { throw RuntimeException("check exploded") },
            ) { _, _ -> ToolResult.text("x") },
        )
        registry.register(
            tool(
                name = "bad_describe", title = "Bad describe", description = "d", scope = Scopes.INTENTS, risk = Risk.SENSITIVE,
                describe = { throw IllegalStateException("nope") },
            ) { _, _ -> ToolResult.text("ok") },
        )
        registry.register(
            tool(name = "inner_timeout", title = "Inner", description = "d", scope = Scopes.DEVICE, risk = Risk.READ) { _, _ ->
                withTimeout(10) { delay(1_000) }
                ToolResult.text("unreachable")
            },
        )
    }

    private fun server(
        confirmationTimeoutMs: Long = 60_000,
        limits: RateLimits = RateLimits(),
        instructions: String? = "Use contacts_search first.",
        info: ServerInfo = ServerInfo("phonebridge", "0.1.0", "PhoneBridge"),
    ) = McpServer(
        registry = registry,
        tokens = tokens,
        policy = PolicyEngine(limits),
        confirmations = ConfirmationProvider { req ->
            prompts += req
            answer(req)
        },
        audit = audit,
        serverInfo = info,
        instructions = instructions,
        isAccessEnabled = { accessEnabled() },
        confirmationTimeoutMs = confirmationTimeoutMs,
    )

    private fun issue(scopes: Set<String> = Scopes.ALL, policy: ConfirmPolicy = ConfirmPolicy.SENSITIVE) =
        tokens.issue("Test agent", scopes, policy, null).plaintext

    private suspend fun McpServer.post(token: String?, body: String, transport: String = "local") =
        handle(TransportRequest(token?.let { "Bearer $it" }, body, transport, userAgent = "test/1", remoteAddress = "127.0.0.1"))

    private fun rpc(method: String, params: String? = null, id: String = "1") =
        """{"jsonrpc":"2.0","id":$id,"method":"$method"${params?.let { ",\"params\":$it" } ?: ""}}"""

    private fun call(name: String, args: String = "{}", id: String = "1") = rpc("tools/call", """{"name":"$name","arguments":$args}""", id)

    private fun TransportResponse.obj(): JsonObject = Json.parseToJsonElement(assertNotNull(body)).jsonObject

    private fun JsonObject.result() = assertNotNull(this["result"], "expected result in $this").jsonObject

    private fun JsonObject.errorCode() = assertNotNull(this["error"], "expected error in $this").jsonObject["code"]!!.jsonPrimitive.int

    private fun JsonObject.text() = result()["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content

    private fun JsonObject.isError() = result()["isError"]!!.jsonPrimitive.boolean

    private fun lastAudit(): AuditEntry = audit.entries.value.first()

    // ---- Authentication ----

    @Test
    fun missingTokenGets401AndIsAudited() = runTest {
        val response = server().post(null, rpc("initialize", """{"protocolVersion":"2025-06-18"}"""))
        assertEquals(401, response.status)
        assertEquals("Bearer realm=\"phonebridge\"", response.headers["www-authenticate"])
        assertEquals("application/json", response.headers["content-type"])
        assertEquals("""{"jsonrpc":"2.0","id":null,"error":{"code":-32001,"message":"Unauthorized"}}""", response.body)
        val entry = lastAudit()
        assertEquals(AuditOutcome.AUTH_FAILED, entry.outcome)
        assertEquals("initialize", entry.method)
        assertNull(entry.tokenId)
        assertEquals("local", entry.transport)
    }

    @Test
    fun invalidRevokedAndExpiredTokensGetIdenticalResponses() = runTest {
        val s = server()
        val good = tokens.issue("Revoked one", Scopes.ALL, ConfirmPolicy.NEVER, null)
        tokens.revoke(good.record.id)
        val tampered = good.plaintext.dropLast(2) + if (good.plaintext.endsWith("AA")) "QA" else "AA"
        val responses = listOf("garbage", good.plaintext, tampered).map { s.post(it, call("echo", """{"text":"x"}""")) }
        responses.forEach {
            assertEquals(401, it.status)
            assertEquals(responses[0].body, it.body)
        }
        val revokedEntry = audit.entries.value[1]
        assertEquals("tools/call", revokedEntry.method)
        assertEquals("echo", revokedEntry.tool)
        assertEquals(good.record.id, revokedEntry.tokenId)
        assertEquals("Revoked one", revokedEntry.tokenName)
        assertTrue(revokedEntry.detail!!.contains("revoked"))
        val secret = good.plaintext.substringAfterLast('.')
        audit.entries.value.forEach { assertFalse(it.toString().contains(secret), "secret leaked into audit: $it") }
    }

    @Test
    fun authenticationHappensBeforeParsing() = runTest {
        val response = server().post(null, "not json")
        assertEquals(401, response.status)
        assertEquals("unknown", lastAudit().method)
    }

    // ---- JSON-RPC framing ----

    @Test
    fun parseErrorIs400() = runTest {
        val response = server().post(issue(), "{nope")
        assertEquals(400, response.status)
        val body = response.obj()
        assertEquals(JsonNull, body["id"])
        assertEquals(-32700, body.errorCode())
        assertEquals(400, server().post(issue(), "").status)
    }

    @Test
    fun invalidRequestsGetMinus32600() = runTest {
        val s = server()
        val t = issue()
        val cases = mapOf(
            """{"jsonrpc":"1.0","id":7,"method":"ping"}""" to JsonPrimitive(7),
            """{"id":"x","method":"ping"}""" to JsonPrimitive("x"),
            """{"jsonrpc":"2.0","id":8,"method":5}""" to JsonPrimitive(8),
            """{"jsonrpc":"2.0","id":9}""" to JsonPrimitive(9),
            """{"jsonrpc":"2.0","id":{"a":1},"method":"ping"}""" to JsonNull,
            """{"jsonrpc":"2.0","id":null,"method":"ping"}""" to JsonNull,
            """{"jsonrpc":"2.0","id":true,"method":"ping"}""" to JsonNull,
            "42" to JsonNull,
            "\"str\"" to JsonNull,
        )
        for ((body, id) in cases) {
            val response = s.post(t, body)
            assertEquals(200, response.status, body)
            val obj = response.obj()
            assertEquals(-32600, obj.errorCode(), body)
            assertEquals(id, obj["id"], body)
        }
        assertEquals(0, audit.entries.value.size, "framing errors are not audited")
    }

    @Test
    fun paramsMustBeAnObject() = runTest {
        val obj = server().post(issue(), rpc("tools/list", "[1,2]")).obj()
        assertEquals(-32602, obj.errorCode())
    }

    @Test
    fun notificationsAndResponsesGet202() = runTest {
        val s = server()
        val t = issue()
        listOf(
            """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
            """{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":1}}""",
            """{"jsonrpc":"2.0","method":"anything/else"}""",
            """{"jsonrpc":"2.0","id":5,"result":{}}""",
            """{"jsonrpc":"2.0","id":5,"error":{"code":1,"message":"x"}}""",
        ).forEach {
            val response = s.post(t, it)
            assertEquals(202, response.status, it)
            assertNull(response.body)
        }
    }

    @Test
    fun unknownMethod() = runTest {
        val obj = server().post(issue(), rpc("sampling/createMessage", id = "\"abc\"")).obj()
        assertEquals(-32601, obj.errorCode())
        assertEquals(JsonPrimitive("abc"), obj["id"])
    }

    @Test
    fun batchesAreSupported() = runTest {
        val s = server()
        val t = issue()
        val body = "[" + listOf(
            rpc("ping", id = "1"),
            """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
            call("echo", """{"text":"hello"}""", id = "\"b\""),
            "7",
        ).joinToString(",") + "]"
        val response = s.post(t, body)
        assertEquals(200, response.status)
        val array = Json.parseToJsonElement(response.body!!).jsonArray
        assertEquals(3, array.size)
        assertEquals(JsonPrimitive(1), array[0].jsonObject["id"])
        assertEquals(JsonObject(emptyMap()), array[0].jsonObject["result"])
        assertEquals("hello", array[1].jsonObject.text())
        assertEquals(-32600, array[2].jsonObject.errorCode())

        val empty = s.post(t, "[]")
        assertEquals(200, empty.status)
        assertEquals(-32600, empty.obj().errorCode())

        val onlyNotifications = s.post(t, """[{"jsonrpc":"2.0","method":"notifications/initialized"}]""")
        assertEquals(202, onlyNotifications.status)
        assertNull(onlyNotifications.body)

        val tooBig = s.post(t, "[" + List(McpServer.MAX_BATCH + 1) { rpc("ping", id = "$it") }.joinToString(",") + "]")
        assertEquals(-32600, tooBig.obj().errorCode())
    }

    // ---- Lifecycle methods ----

    @Test
    fun initializeNegotiatesVersion() = runTest {
        val s = server()
        val t = issue()
        suspend fun negotiated(params: String) = s.post(t, rpc("initialize", params)).obj().result()["protocolVersion"]!!.jsonPrimitive.content
        assertEquals("2025-06-18", negotiated("""{"protocolVersion":"2025-06-18"}"""))
        assertEquals("2024-11-05", negotiated("""{"protocolVersion":"2024-11-05"}"""))
        assertEquals("2025-11-25", negotiated("""{"protocolVersion":"1999-01-01"}"""))
        assertEquals("2025-11-25", negotiated("{}"))

        val result = s.post(t, rpc("initialize", """{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"c","version":"1"}}""")).obj().result()
        assertEquals(false, result["capabilities"]!!.jsonObject["tools"]!!.jsonObject["listChanged"]!!.jsonPrimitive.boolean)
        val info = result["serverInfo"]!!.jsonObject
        assertEquals("phonebridge", info["name"]!!.jsonPrimitive.content)
        assertEquals("0.1.0", info["version"]!!.jsonPrimitive.content)
        assertEquals("PhoneBridge", info["title"]!!.jsonPrimitive.content)
        assertEquals("Use contacts_search first.", result["instructions"]!!.jsonPrimitive.content)
        assertEquals(0, audit.entries.value.size, "initialize is not audited")
    }

    @Test
    fun initializeOmitsNullFields() = runTest {
        val result = server(instructions = null, info = ServerInfo("pb", "1")).post(issue(), rpc("initialize", "{}")).obj().result()
        assertNull(result["instructions"])
        assertNull(result["serverInfo"]!!.jsonObject["title"])
    }

    @Test
    fun pingAndEmptyLists() = runTest {
        val s = server()
        val t = issue()
        assertEquals(JsonObject(emptyMap()), s.post(t, rpc("ping")).obj().result())
        assertEquals(JsonArray(emptyList()), s.post(t, rpc("resources/list")).obj().result()["resources"])
        assertEquals(JsonArray(emptyList()), s.post(t, rpc("resources/templates/list")).obj().result()["resourceTemplates"])
        assertEquals(JsonArray(emptyList()), s.post(t, rpc("prompts/list")).obj().result()["prompts"])
    }

    // ---- tools/list ----

    @Test
    fun toolsListIsFilteredByScope() = runTest {
        val response = server().post(issue(setOf(Scopes.DEVICE)), rpc("tools/list"))
        assertEquals(200, response.status)
        assertEquals("application/json", response.headers["content-type"])
        val tools = response.obj().result()["tools"]!!.jsonArray.map { it.jsonObject }
        val names = tools.map { it["name"]!!.jsonPrimitive.content }
        assertEquals(
            listOf("bridge_capabilities", "echo", "slow", "fails", "crashes", "soft_error", "needs_perm", "broken_check", "inner_timeout"),
            names,
        )
        val echo = tools.first { it["name"]!!.jsonPrimitive.content == "echo" }
        assertEquals("Echo", echo["title"]!!.jsonPrimitive.content)
        assertEquals("Echoes text", echo["description"]!!.jsonPrimitive.content)
        assertEquals("object", echo["inputSchema"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val annotations = echo["annotations"]!!.jsonObject
        assertEquals("Echo", annotations["title"]!!.jsonPrimitive.content)
        assertTrue(annotations["readOnlyHint"]!!.jsonPrimitive.boolean)
        assertFalse(annotations["destructiveHint"]!!.jsonPrimitive.boolean)
        assertTrue(annotations["idempotentHint"]!!.jsonPrimitive.boolean)
        assertFalse(annotations["openWorldHint"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun toolsListAnnotationsByRiskAndUnavailableNotes() = runTest {
        val tools = server().post(issue(), rpc("tools/list")).obj().result()["tools"]!!.jsonArray.associate {
            it.jsonObject["name"]!!.jsonPrimitive.content to it.jsonObject
        }
        fun hints(name: String) = tools[name]!!["annotations"]!!.jsonObject.let { a ->
            listOf("readOnlyHint", "destructiveHint", "idempotentHint", "openWorldHint").map { a[it]!!.jsonPrimitive.boolean }
        }
        assertEquals(listOf(false, false, false, false), hints("app_poke"))
        assertEquals(listOf(false, true, false, true), hints("send_pre"))
        assertEquals(
            "Base description\n\nCurrently unavailable: Permission READ_SMS not granted.",
            tools["needs_perm"]!!["description"]!!.jsonPrimitive.content,
        )
        val broken = tools["broken_check"]!!["description"]!!.jsonPrimitive.content
        assertTrue(broken.startsWith("Desc\n\nCurrently unavailable: Could not check availability"), broken)
        assertEquals(registry.all().size, tools.size, "full-scope token sees everything")
    }

    // ---- tools/call basics ----

    @Test
    fun callsToolAndAudits() = runTest {
        val s = server()
        val token = tokens.issue("Claude laptop", setOf(Scopes.DEVICE), ConfirmPolicy.SENSITIVE, null)
        val obj = s.post(token.plaintext, call("echo", """{"text":"hello"}"""), transport = "relay").obj()
        assertEquals("hello", obj.text())
        assertFalse(obj.isError())
        assertEquals(JsonPrimitive(1), obj["id"])
        val entry = lastAudit()
        assertEquals(AuditOutcome.OK, entry.outcome)
        assertEquals("tools/call", entry.method)
        assertEquals("echo", entry.tool)
        assertEquals("""{"text":"hello"}""", entry.argsPreview)
        assertEquals(token.record.id, entry.tokenId)
        assertEquals("Claude laptop", entry.tokenName)
        assertEquals("relay", entry.transport)
        assertTrue(entry.durationMs >= 0)
        assertEquals(0, prompts.size)
    }

    @Test
    fun argumentsDefaultToEmptyAndMustBeObjects() = runTest {
        val s = server()
        val t = issue()
        val noArgs = s.post(t, rpc("tools/call", """{"name":"echo"}""")).obj()
        assertEquals("", noArgs.text())
        assertEquals(-32602, s.post(t, rpc("tools/call", """{"name":"echo","arguments":[1]}""")).obj().errorCode())
        assertEquals(-32602, s.post(t, rpc("tools/call", """{"arguments":{}}""")).obj().errorCode())
        assertEquals(-32602, s.post(t, rpc("tools/call", """{"name":7}""")).obj().errorCode())
    }

    @Test
    fun unknownAndOutOfScopeToolsAreInvalidParams() = runTest {
        val s = server()
        val t = issue(setOf(Scopes.DEVICE))
        val unknown = s.post(t, call("no_such_tool")).obj()
        assertEquals(-32602, unknown.errorCode())
        assertEquals(AuditOutcome.UNKNOWN_TOOL, lastAudit().outcome)

        val outOfScope = s.post(t, call("app_poke")).obj()
        assertEquals(-32602, outOfScope.errorCode())
        assertTrue(outOfScope["error"]!!.jsonObject["message"]!!.jsonPrimitive.content.contains("'apps' scope"))
        assertEquals(AuditOutcome.DENIED_SCOPE, lastAudit().outcome)
        assertEquals("app_poke", lastAudit().tool)
        assertEquals(0, sideEffects.get())
    }

    @Test
    fun pausedAccessReturnsIsError() = runTest {
        accessEnabled = { false }
        val obj = server().post(issue(), call("app_poke")).obj()
        assertTrue(obj.isError())
        assertEquals("Agent access is paused on the phone. Ask the user to resume it in the PhoneBridge app.", obj.text())
        assertEquals(AuditOutcome.PAUSED, lastAudit().outcome)
        assertEquals(0, sideEffects.get())

        accessEnabled = { throw IllegalStateException("settings unavailable") }
        assertTrue(server().post(issue(), call("app_poke")).obj().isError(), "fails closed")
        assertEquals(0, sideEffects.get())
    }

    @Test
    fun rateLimitedCallsAreRefused() = runTest {
        val s = server(limits = RateLimits(callsPerMinute = 2, sensitiveCallsPerMinute = 1))
        val t = issue(policy = ConfirmPolicy.NEVER)
        assertFalse(s.post(t, call("echo")).obj().isError())
        assertFalse(s.post(t, call("echo")).obj().isError())
        val limited = s.post(t, call("echo")).obj()
        assertTrue(limited.isError())
        assertTrue(limited.text().contains("Slow down"), limited.text())
        assertEquals(AuditOutcome.RATE_LIMITED, lastAudit().outcome)
    }

    // ---- Confirmation: pre ----

    @Test
    fun preConfirmationShowsDescribedCallAndRunsOnApproval() = runTest {
        val s = server()
        val obj = s.post(issue(), call("send_pre", """{"to":"+15551234","text":"hi"}"""), transport = "relay").obj()
        assertFalse(obj.isError())
        assertEquals("sent", obj.text())
        val prompt = prompts.single()
        assertEquals("Send 'hi' to +15551234", prompt.summary)
        assertEquals("Test agent", prompt.tokenName)
        assertEquals("send_pre", prompt.toolName)
        assertEquals("Send (pre)", prompt.toolTitle)
        assertEquals(Risk.SENSITIVE, prompt.risk)
        assertEquals("relay", prompt.transport)
        assertTrue(prompt.id.length >= 16)
        assertEquals(1, sideEffects.get())
        assertEquals(AuditOutcome.OK, lastAudit().outcome)
        assertTrue(lastAudit().detail!!.contains("approved by user"))
    }

    @Test
    fun preConfirmationDenied() = runTest {
        answer = { ConfirmationResult.DENIED }
        val obj = server().post(issue(), call("send_pre", """{"to":"1","text":"x"}""")).obj()
        assertTrue(obj.isError())
        assertEquals("The user denied this action on the phone. Do not retry unless the user asks again.", obj.text())
        assertEquals(0, sideEffects.get())
        assertEquals(AuditOutcome.DENIED_BY_USER, lastAudit().outcome)
    }

    @Test
    fun preConfirmationTimesOutAndCancelsThePrompt() = runTest {
        val cancelled = CompletableDeferred<Unit>()
        answer = {
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }
        val obj = server(confirmationTimeoutMs = 2_000).post(issue(), call("send_pre", """{"to":"1","text":"x"}""")).obj()
        assertTrue(obj.isError())
        assertEquals("The user did not approve this action on the phone within 2 seconds.", obj.text())
        assertTrue(cancelled.isCompleted, "provider must observe cancellation")
        assertEquals(0, sideEffects.get())
        assertEquals(AuditOutcome.CONFIRM_TIMEOUT, lastAudit().outcome)
    }

    @Test
    fun providerTimedOutResultIsATimeout() = runTest {
        answer = { ConfirmationResult.TIMED_OUT }
        server().post(issue(), call("send_pre", """{"to":"1","text":"x"}"""))
        assertEquals(AuditOutcome.CONFIRM_TIMEOUT, lastAudit().outcome)
    }

    @Test
    fun providerFailureIsReportedNotThrown() = runTest {
        answer = { throw IllegalStateException("no notification permission") }
        val obj = server().post(issue(), call("send_pre", """{"to":"1","text":"x"}""")).obj()
        assertTrue(obj.isError())
        assertTrue(obj.text().startsWith("Could not ask the user"), obj.text())
        assertEquals(AuditOutcome.EXCEPTION, lastAudit().outcome)
        assertEquals(0, sideEffects.get())
    }

    @Test
    fun describeCallFailureFallsBackToTitleAndArgs() = runTest {
        server().post(issue(), call("bad_describe", """{"action":"android.intent.action.VIEW"}"""))
        assertEquals("""Bad describe: {"action":"android.intent.action.VIEW"}""", prompts.single().summary)
    }

    @Test
    fun confirmPolicyMatrixDecidesPrompts() = runTest {
        val s = server()
        s.post(issue(policy = ConfirmPolicy.NEVER), call("send_pre", """{"to":"1","text":"x"}"""))
        assertEquals(0, prompts.size, "NEVER")
        s.post(issue(policy = ConfirmPolicy.SENSITIVE), call("app_poke"))
        assertEquals(0, prompts.size, "SENSITIVE does not prompt for ACTION")
        s.post(issue(policy = ConfirmPolicy.ALL_ACTIONS), call("app_poke"))
        assertEquals(1, prompts.size, "ALL_ACTIONS prompts for ACTION")
        s.post(issue(policy = ConfirmPolicy.ALL_ACTIONS), call("echo"))
        assertEquals(1, prompts.size, "READ never prompts")
        assertEquals(3, sideEffects.get())
    }

    // ---- Confirmation: internal ----

    @Test
    fun internalConfirmationUsesResolvedSummary() = runTest {
        val obj = server().post(issue(), call("send_internal")).obj()
        assertFalse(obj.isError(), obj.toString())
        assertEquals("Send 'hi' to Tania Cohen +972541234567", prompts.single().summary)
        assertEquals("send_internal", prompts.single().toolName)
        assertEquals(1, sideEffects.get())
        assertEquals(AuditOutcome.OK, lastAudit().outcome)
        assertTrue(lastAudit().detail!!.contains("approved by user"))
    }

    @Test
    fun internalConfirmationDenied() = runTest {
        answer = { ConfirmationResult.DENIED }
        val obj = server().post(issue(), call("send_internal")).obj()
        assertTrue(obj.isError())
        assertEquals("The user denied this action on the phone. Do not retry unless the user asks again.", obj.text())
        assertEquals(0, sideEffects.get())
        assertEquals(AuditOutcome.DENIED_BY_USER, lastAudit().outcome)
    }

    @Test
    fun internalConfirmationTimeout() = runTest {
        answer = { awaitCancellation() }
        val obj = server(confirmationTimeoutMs = 1_500).post(issue(), call("send_internal")).obj()
        assertTrue(obj.isError())
        assertEquals("The user did not approve this action on the phone within 1.5 seconds.", obj.text())
        assertEquals(AuditOutcome.CONFIRM_TIMEOUT, lastAudit().outcome)
        assertEquals(0, sideEffects.get())
    }

    @Test
    fun internalConfirmationIsNoOpWhenPolicyDoesNotRequireIt() = runTest {
        val obj = server().post(issue(policy = ConfirmPolicy.NEVER), call("send_internal")).obj()
        assertFalse(obj.isError())
        assertEquals(0, prompts.size)
        assertEquals(1, sideEffects.get())
        assertNull(lastAudit().detail)
    }

    @Test
    fun preConfirmedToolCallingRequireConfirmationDoesNotPromptTwice() = runTest {
        server().post(issue(), call("send_pre", """{"to":"1","text":"x"}"""))
        assertEquals(1, prompts.size)
    }

    @Test
    fun toolThatSkipsConfirmationIsFlagged() = runTest {
        val obj = server().post(issue(), call("send_skipper")).obj()
        assertFalse(obj.isError())
        assertEquals(AuditOutcome.OK, lastAudit().outcome)
        assertEquals("tool skipped confirmation", lastAudit().detail)
        server().post(issue(policy = ConfirmPolicy.NEVER), call("send_skipper"))
        assertNull(lastAudit().detail, "not flagged when no confirmation was required")
    }

    // ---- Timeouts ----

    @Test
    fun toolTimeout() = runTest {
        val obj = server().post(issue(), call("slow")).obj()
        assertTrue(obj.isError())
        assertTrue(obj.text().startsWith("Timed out after 1 s"), obj.text())
        assertEquals(AuditOutcome.TIMEOUT, lastAudit().outcome)
    }

    @Test
    fun slowApprovalDoesNotCountTowardToolTimeout() = runTest {
        // The user takes 50 s to approve; send_internal has a 1 s budget and needs 0.5 s after approval.
        answer = {
            delay(50_000)
            ConfirmationResult.APPROVED
        }
        val internal = server().post(issue(), call("send_internal")).obj()
        assertFalse(internal.isError(), internal.text())
        assertEquals(1, sideEffects.get())

        val preWithBudget = tool(
            name = "pre_budget", title = "Pre", description = "d", scope = Scopes.CALLS, risk = Risk.SENSITIVE, timeoutMs = 1_000,
        ) { _, _ ->
            delay(500)
            ToolResult.text("called")
        }
        registry.register(preWithBudget)
        val pre = server().post(issue(), call("pre_budget")).obj()
        assertFalse(pre.isError(), pre.text())
    }

    @Test
    fun repeatedInternalConfirmationsEachPauseTheBudget() = runTest {
        registry.register(
            tool(
                name = "multi_send", title = "Multi", description = "d", scope = Scopes.MESSAGES_SEND, risk = Risk.SENSITIVE,
                confirmsInternally = true, timeoutMs = 1_000,
            ) { _, ctx ->
                repeat(3) {
                    ctx.requireConfirmation("Send part $it")
                    delay(300)
                }
                ToolResult.text("sent all")
            },
        )
        answer = {
            delay(20_000)
            ConfirmationResult.APPROVED
        }
        val obj = server().post(issue(), call("multi_send")).obj()
        assertFalse(obj.isError(), obj.text())
        assertEquals(3, prompts.size)
    }

    @Test
    fun blockingToolIsAbandonedAtItsDeadline() = runBlocking {
        registry.register(
            tool(name = "blocking", title = "Blocking", description = "d", scope = Scopes.DEVICE, risk = Risk.READ, timeoutMs = 200) { _, _ ->
                withContext(Dispatchers.IO) { Thread.sleep(3_000) }
                ToolResult.text("late")
            },
        )
        val started = System.nanoTime()
        val obj = server().post(issue(), call("blocking")).obj()
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(obj.isError())
        assertTrue(obj.text().startsWith("Timed out after 0.2 s"), obj.text())
        assertTrue(elapsedMs < 2_000, "returned after $elapsedMs ms")
    }

    @Test
    fun innerTimeoutInsideToolIsATimeoutResult() = runTest {
        val obj = server().post(issue(), call("inner_timeout")).obj()
        assertTrue(obj.isError())
        assertTrue(obj.text().startsWith("Timed out inside the tool"), obj.text())
        assertEquals(AuditOutcome.TIMEOUT, lastAudit().outcome)
    }

    // ---- Errors and results ----

    @Test
    fun toolExceptionBecomesIsError() = runTest {
        val obj = server().post(issue(), call("fails")).obj()
        assertTrue(obj.isError())
        assertEquals("No contact named 'Bob'. Ask the user for the number.", obj.text())
        assertEquals(AuditOutcome.TOOL_ERROR, lastAudit().outcome)
        assertEquals("No contact named 'Bob'. Ask the user for the number.", lastAudit().detail)
    }

    @Test
    fun unexpectedExceptionBecomesInternalError() = runTest {
        val obj = server().post(issue(), call("crashes")).obj()
        assertTrue(obj.isError())
        assertEquals("Internal error: IllegalStateException: boom", obj.text())
        assertEquals(AuditOutcome.EXCEPTION, lastAudit().outcome)
    }

    @Test
    fun isErrorResultIsAuditedAsToolError() = runTest {
        val obj = server().post(issue(), call("soft_error")).obj()
        assertTrue(obj.isError())
        assertEquals(AuditOutcome.TOOL_ERROR, lastAudit().outcome)
    }

    @Test
    fun imageContentIsEncoded() = runTest {
        val result = server().post(issue(), call("picture")).obj().result()
        val content = result["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals("image", content[0]["type"]!!.jsonPrimitive.content)
        assertEquals("iVBORw0KGgo=", content[0]["data"]!!.jsonPrimitive.content)
        assertEquals("image/png", content[0]["mimeType"]!!.jsonPrimitive.content)
        assertEquals("text", content[1]["type"]!!.jsonPrimitive.content)
        assertEquals("1x1", content[1]["text"]!!.jsonPrimitive.content)
        assertFalse(result["isError"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun argsPreviewIsTruncated() = runTest {
        server().post(issue(), call("echo", """{"text":"${"x".repeat(1_000)}"}"""))
        val preview = lastAudit().argsPreview!!
        assertEquals(300, preview.length)
        assertTrue(preview.startsWith("""{"text":"xxx"""))
        assertTrue(preview.endsWith("…"))
    }

    @Test
    fun capabilitiesToolIsCallableWithAnyScope() = runTest {
        val obj = server().post(issue(emptySet()), call("bridge_capabilities")).obj()
        assertFalse(obj.isError())
        val payload = Json.parseToJsonElement(obj.text()).jsonObject
        assertEquals(listOf("bridge_capabilities"), payload["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content })
    }

    // ---- Concurrency and cancellation ----

    @Test
    fun concurrentRequestsAreIndependent() = runBlocking {
        val s = server()
        val t = issue(policy = ConfirmPolicy.NEVER)
        val results = coroutineScope {
            (1..50).map { i -> async(Dispatchers.Default) { s.post(t, call("echo", """{"text":"m$i"}""", id = "$i")).obj() } }.awaitAll()
        }
        results.forEachIndexed { index, obj ->
            assertEquals(JsonPrimitive(index + 1), obj["id"])
            assertEquals("m${index + 1}", obj.text())
        }
        assertEquals(50, audit.entries.value.size)
    }

    @Test
    fun cancellingTheRequestPropagatesButTheToolCallFinishesAndIsAudited() = runBlocking {
        // E.g. the relay socket was restarted on a Wi-Fi/cellular switch while sms_send was waiting for the radio.
        val toolCancelled = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        registry.register(
            tool(name = "hang", title = "Hang", description = "d", scope = Scopes.DEVICE, risk = Risk.ACTION) { _, _ ->
                started.complete(Unit)
                try {
                    release.await()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    toolCancelled.complete(Unit)
                    throw e
                }
                sideEffects.incrementAndGet()
                ToolResult.text("done")
            },
        )
        val s = server()
        val t = issue()
        val job = launch { s.post(t, call("hang")) }
        started.await()
        job.cancel()
        assertNotNull(withTimeoutOrNull(2_000) { job.join() }, "the transport stops waiting at once")
        assertTrue(job.isCancelled)
        assertEquals(1, s.inFlightCalls, "the call goes on")
        assertTrue(audit.entries.value.isEmpty())

        release.complete(Unit)
        withTimeout(2_000) { while (s.inFlightCalls > 0) delay(10) }
        assertFalse(toolCancelled.isCompleted, "the tool must not be cancelled")
        assertEquals(1, sideEffects.get())
        val entry = lastAudit()
        assertEquals(AuditOutcome.OK, entry.outcome)
        assertEquals("hang", entry.tool)
        assertEquals(ToolCalls.ABANDONED_NOTE, entry.detail)
    }

    @Test
    fun userAgentAndAddressAppearInAuthFailureDetail() = runTest {
        server().post("pb1.bad", rpc("ping"))
        val detail = lastAudit().detail!!
        assertTrue(detail.contains("127.0.0.1"))
        assertTrue(detail.contains("test/1"))
        assertEquals("ping", lastAudit().method)
    }

    @Test
    fun auditFailureDoesNotBreakCalls() = runTest {
        val failingAudit = object : dev.phonebridge.core.audit.AuditLog {
            override fun record(entry: AuditEntry) = throw IllegalStateException("disk full")
            override val entries = kotlinx.coroutines.flow.MutableStateFlow(emptyList<AuditEntry>())
            override fun clear() = Unit
        }
        val s = McpServer(
            registry, tokens, PolicyEngine(), { ConfirmationResult.APPROVED }, failingAudit,
            ServerInfo("pb", "1"), null,
        )
        assertEquals("ok", s.post(issue(), call("echo", """{"text":"ok"}""")).obj().text())
        assertEquals(401, s.post(null, rpc("ping")).status)
    }
}
