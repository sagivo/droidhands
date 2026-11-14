package dev.phonebridge.core.mcp

import dev.phonebridge.core.audit.AuditOutcome
import dev.phonebridge.core.audit.InMemoryAuditLog
import dev.phonebridge.core.auth.AppAccessMode
import dev.phonebridge.core.auth.AppPolicy
import dev.phonebridge.core.auth.ConfirmPolicy
import dev.phonebridge.core.auth.DeviceIdentity
import dev.phonebridge.core.auth.InMemoryTokenStore
import dev.phonebridge.core.auth.IssuedToken
import dev.phonebridge.core.auth.TokenManager
import dev.phonebridge.core.auth.TokenRecord
import dev.phonebridge.core.auth.ToolRule
import dev.phonebridge.core.confirm.ConfirmationProvider
import dev.phonebridge.core.confirm.ConfirmationRequest
import dev.phonebridge.core.confirm.ConfirmationResult
import dev.phonebridge.core.policy.GlobalSecurity
import dev.phonebridge.core.policy.PolicyEngine
import dev.phonebridge.core.policy.RateLimits
import dev.phonebridge.core.tools.CallContext
import dev.phonebridge.core.tools.Dispatch
import dev.phonebridge.core.tools.DispatchTool
import dev.phonebridge.core.tools.Risk
import dev.phonebridge.core.tools.Scopes
import dev.phonebridge.core.tools.Tool
import dev.phonebridge.core.tools.ToolException
import dev.phonebridge.core.tools.ToolRegistry
import dev.phonebridge.core.tools.ToolResult
import dev.phonebridge.core.tools.objectSchema
import dev.phonebridge.core.tools.optString
import dev.phonebridge.core.tools.tool
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [DispatchTool] calls (`app_action_run`) through the MCP pipeline: the resolved tool gets the whole
 * pipeline under its own name (scope, policy, confirmation, stop checks, audit), and the dispatching
 * tool's own checks still apply.
 */
class McpDispatchTest {
    private val identity = DeviceIdentity(ByteArray(32) { it.toByte() })
    private val tokens = TokenManager(InMemoryTokenStore(), identity.deviceId)
    private val audit = InMemoryAuditLog()
    private val registry = ToolRegistry()
    private val prompts: MutableList<ConfirmationRequest> = Collections.synchronizedList(mutableListOf())
    private val sideEffects = AtomicInteger()
    private val lastContext = AtomicReference<CallContext?>(null)
    /** Completed by `com.app/wait` once running; it then waits for [gate]. Replaced per call in loops. */
    @Volatile private var started = CompletableDeferred<Unit>()

    @Volatile private var gate = CompletableDeferred<Unit>()

    @Volatile private var answer: ConfirmationResult = ConfirmationResult.APPROVED

    @Volatile private var accessEnabled = true

    private fun inner(name: String, scope: String = Scopes.APPS, risk: Risk = Risk.ACTION, alwaysConfirm: Boolean = false): Tool =
        tool(name = name, title = "Inner $name", description = "d", scope = scope, risk = risk, alwaysConfirm = alwaysConfirm,
            describe = { args -> "Do $name with ${args["q"]?.jsonPrimitive?.content}" }) { _, ctx ->
            lastContext.set(ctx)
            sideEffects.incrementAndGet()
            ToolResult.text("ran $name")
        }

    private val inners: Map<String, Tool> = listOf(
        inner("com.app/play"),
        inner("com.app/send", scope = Scopes.MESSAGES_SEND, risk = Risk.SENSITIVE),
        inner("com.app/look", risk = Risk.READ),
        inner("com.app/local", alwaysConfirm = true),
        tool(name = "com.app/wait", title = "Wait", description = "d", scope = Scopes.APPS, risk = Risk.ACTION) { _, ctx ->
            started.complete(Unit)
            gate.await()
            ctx.ensureStillAllowed()
            sideEffects.incrementAndGet()
            ToolResult.text("done")
        },
        tool(
            name = "real_send", title = "Send (internal)", description = "d", scope = Scopes.MESSAGES_SEND, risk = Risk.SENSITIVE,
            confirmsInternally = true,
        ) { _, ctx ->
            ctx.requireConfirmation("Send 'hi' to Tania Cohen")
            sideEffects.incrementAndGet()
            ToolResult.text("sent")
        },
    ).associateBy { it.name }

    /** Resolves {"to": name, "q": …} to the inner tool [name] with {"q": …}; other keys trigger the error paths. */
    private val runner = object : DispatchTool {
        override val name = "run_it"
        override val title = "Run it"
        override val description = "d"
        override val scope = Scopes.APPS
        override val risk = Risk.ACTION
        override val inputSchema = objectSchema { string("to", "target") }

        override suspend fun resolve(args: JsonObject, ctx: CallContext): Dispatch {
            args.optString("blocked")?.let { ctx.appAccess.require(it, "Bank") }
            if (args.optString("confirm") != null) ctx.requireConfirmation("Resolve?")
            if (args.optString("nested") != null) return Dispatch(this, args)
            val to = args.optString("to") ?: throw ToolException("Pass 'to'. Known: ${inners.keys.sorted().joinToString(", ")}.")
            val target = inners[to] ?: throw ToolException("No action '$to'. Known: ${inners.keys.sorted().joinToString(", ")}.")
            val innerArgs = buildJsonObject { args.optString("q")?.let { put("q", it) } }
            return Dispatch(target, innerArgs, alias = args.optString("alias"))
        }
    }

    init {
        registry.register(runner)
    }

    private fun server(limits: RateLimits = RateLimits()) = McpServer(
        registry = registry,
        tokens = tokens,
        policy = PolicyEngine(limits),
        confirmations = ConfirmationProvider { req ->
            prompts += req
            answer
        },
        audit = audit,
        serverInfo = ServerInfo("phonebridge", "0.1.0"),
        instructions = null,
        isAccessEnabled = { accessEnabled },
        globalSecurity = { GlobalSecurity(neverAllowApps = setOf("com.bank")) },
    )

    private fun issue(
        scopes: Set<String> = Scopes.ALL,
        policy: ConfirmPolicy = ConfirmPolicy.SENSITIVE,
        change: (TokenRecord) -> TokenRecord = { it },
    ): IssuedToken {
        val issued = tokens.issue("Claude", scopes, policy, null, appPolicy = AppPolicy(AppAccessMode.ALL))
        tokens.update(issued.record.id, change)
        return issued
    }

    private suspend fun McpServer.raw(token: IssuedToken, args: String): JsonObject {
        val body = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"run_it","arguments":$args}}"""
        val response = handle(TransportRequest("Bearer ${token.plaintext}", body, "relay"))
        return Json.parseToJsonElement(assertNotNull(response.body)).jsonObject
    }

    private suspend fun McpServer.run(token: IssuedToken, args: String): JsonObject = raw(token, args)["result"]!!.jsonObject

    private fun JsonObject.text() = this["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content

    private fun JsonObject.isError() = this["isError"]!!.jsonPrimitive.boolean

    private fun lastAudit() = audit.entries.value.first()

    @Test
    fun theResolvedToolRunsAndIsAuditedUnderItsOwnName() = runTest {
        val token = issue()
        val result = server().run(token, """{"to":"com.app/play","q":"lofi"}""")
        assertFalse(result.isError())
        assertEquals("ran com.app/play", result.text())
        assertEquals(1, sideEffects.get())
        assertEquals(1, audit.entries.value.size)
        val entry = lastAudit()
        assertEquals(AuditOutcome.OK, entry.outcome)
        assertEquals("com.app/play", entry.tool)
        assertEquals("""{"q":"lofi"}""", entry.argsPreview)
        assertEquals("via run_it", entry.detail)
        // The tool gets an ordinary context with the call's app access.
        assertFalse(assertNotNull(lastContext.get()).appAccess.isAllowed("com.bank"))
    }

    @Test
    fun theAliasIsNotedInTheAuditEntry() = runTest {
        server().run(issue(), """{"to":"com.app/play","alias":"com.app/song"}""")
        assertEquals("via run_it (com.app/song)", lastAudit().detail)
    }

    @Test
    fun theDispatchingToolNeedsItsScope() = runTest {
        val token = issue(scopes = setOf(Scopes.DEVICE))
        val response = server().raw(token, """{"to":"com.app/play"}""")
        assertNotNull(response["error"])
        assertEquals(AuditOutcome.DENIED_SCOPE, lastAudit().outcome)
        assertEquals("run_it", lastAudit().tool)
        assertEquals(0, sideEffects.get())
    }

    @Test
    fun theResolvedToolNeedsItsScope() = runTest {
        val token = issue(scopes = setOf(Scopes.APPS))
        val result = server().run(token, """{"to":"com.app/send"}""")
        assertTrue(result.isError())
        assertTrue("Claude isn't allowed to send messages or act on notifications ('com.app/send' needs that)" in result.text(), result.text())
        assertEquals(AuditOutcome.DENIED_SCOPE, lastAudit().outcome)
        assertEquals("com.app/send", lastAudit().tool)
        assertEquals("missing scope messages.send; via run_it", lastAudit().detail)
        assertEquals(0, sideEffects.get())
    }

    @Test
    fun aBlockedAppAtResolutionIsDeniedApp() = runTest {
        val result = server().run(issue(), """{"blocked":"com.bank"}""")
        assertTrue(result.isError())
        assertTrue("never-allow list" in result.text(), result.text())
        assertEquals(AuditOutcome.DENIED_APP, lastAudit().outcome)
        assertEquals("run_it", lastAudit().tool)
        assertEquals("blocked app com.bank", lastAudit().detail)
        assertEquals(0, sideEffects.get())
    }

    @Test
    fun anUnresolvableCallIsRefusedWithWhatExists() = runTest {
        val result = server().run(issue(), """{"to":"com.app/nope"}""")
        assertTrue(result.isError())
        assertTrue("Known: com.app/local, com.app/look" in result.text(), result.text())
        assertEquals(AuditOutcome.TOOL_ERROR, lastAudit().outcome)
        assertEquals("run_it", lastAudit().tool)
    }

    @Test
    fun unresolvableCallsCannotFloodTheAuditLog() = runTest {
        val s = server()
        val token = issue()
        repeat(20) { s.run(token, """{"to":"com.app/nope$it"}""") }
        assertEquals(5, audit.entries.value.size)
        assertTrue(audit.entries.value.all { it.outcome == AuditOutcome.TOOL_ERROR })
    }

    @Test
    fun resolvingMayNotAskForApproval() = runTest {
        val result = server().run(issue(), """{"confirm":"yes"}""")
        assertTrue(result.isError())
        assertTrue(prompts.isEmpty())
        assertEquals(AuditOutcome.TOOL_ERROR, lastAudit().outcome)
    }

    @Test
    fun dispatchDoesNotNest() = runTest {
        val result = server().run(issue(), """{"nested":"yes"}""")
        assertTrue(result.isError())
        assertTrue("nested dispatch" in result.text(), result.text())
        assertEquals(AuditOutcome.EXCEPTION, lastAudit().outcome)
        assertEquals(0, sideEffects.get())
    }

    @Test
    fun aBlockRuleOnTheResolvedToolRefusesIt() = runTest {
        val token = issue { it.copy(toolRules = mapOf("com.app/play" to ToolRule.BLOCK)) }
        val result = server().run(token, """{"to":"com.app/play"}""")
        assertTrue(result.isError())
        assertTrue("blocked the tool 'com.app/play'" in result.text(), result.text())
        assertEquals(AuditOutcome.DENIED_POLICY, lastAudit().outcome)
        assertEquals("com.app/play", lastAudit().tool)
        assertEquals(0, sideEffects.get())
    }

    @Test
    fun aBlockRuleOnTheAliasRefusesIt() = runTest {
        val token = issue { it.copy(toolRules = mapOf("com.app/song" to ToolRule.BLOCK)) }
        val result = server().run(token, """{"to":"com.app/play","alias":"com.app/song"}""")
        assertTrue(result.isError())
        assertTrue("blocked the tool 'com.app/song'" in result.text(), result.text())
        assertEquals(AuditOutcome.DENIED_POLICY, lastAudit().outcome)
        assertEquals(0, sideEffects.get())
    }

    @Test
    fun aBlockRuleOnTheDispatchingToolRefusesEverything() = runTest {
        val token = issue { it.copy(toolRules = mapOf("run_it" to ToolRule.BLOCK)) }
        val result = server().run(token, """{"to":"com.app/look"}""")
        assertTrue(result.isError())
        assertEquals(AuditOutcome.DENIED_POLICY, lastAudit().outcome)
        assertEquals("run_it", lastAudit().tool)
        assertEquals(0, sideEffects.get())
    }

    @Test
    fun theRiskCapAppliesToBothTools() = runTest {
        val s = server()
        // Read-only: the dispatching tool itself (ACTION) is above the cap.
        val readOnly = issue { it.copy(maxRisk = Risk.READ) }
        assertTrue(s.run(readOnly, """{"to":"com.app/look"}""").isError())
        assertEquals("run_it", lastAudit().tool)
        assertEquals(AuditOutcome.DENIED_POLICY, lastAudit().outcome)
        // Capped at ACTION: a SENSITIVE resolved tool is refused under its own name.
        val noSensitive = issue { it.copy(maxRisk = Risk.ACTION) }
        val result = s.run(noSensitive, """{"to":"com.app/send"}""")
        assertTrue(result.isError())
        assertEquals("com.app/send", lastAudit().tool)
        assertEquals(AuditOutcome.DENIED_POLICY, lastAudit().outcome)
        assertEquals(0, sideEffects.get())
    }

    @Test
    fun confirmationFollowsTheResolvedToolsRiskAndRules() = runTest {
        val s = server()
        val token = issue()
        s.run(token, """{"to":"com.app/play","q":"x"}""")
        assertTrue(prompts.isEmpty(), "ACTION under the SENSITIVE policy: no prompt")

        s.run(token, """{"to":"com.app/send","q":"hi"}""")
        val prompt = prompts.single()
        assertEquals("com.app/send", prompt.toolName)
        assertEquals("Inner com.app/send", prompt.toolTitle)
        assertEquals("Do com.app/send with hi", prompt.summary)
        assertEquals(Risk.SENSITIVE, prompt.risk)
        assertEquals("approved by user; via run_it", lastAudit().detail)

        val asking = issue { it.copy(toolRules = mapOf("com.app/play" to ToolRule.ASK)) }
        s.run(asking, """{"to":"com.app/play"}""")
        assertEquals(2, prompts.size)
    }

    @Test
    fun alwaysConfirmBeatsAllowRulesAndTheNeverPolicy() = runTest {
        val s = server()
        val token = issue(policy = ConfirmPolicy.NEVER) { it.copy(toolRules = mapOf("com.app/local" to ToolRule.ALLOW)) }
        answer = ConfirmationResult.DENIED
        val result = s.run(token, """{"to":"com.app/local"}""")
        assertTrue(result.isError())
        assertEquals(1, prompts.size)
        assertEquals(AuditOutcome.DENIED_BY_USER, lastAudit().outcome)
        assertEquals("com.app/local", lastAudit().tool)
        assertEquals(0, sideEffects.get())
    }

    @Test
    fun internallyConfirmingToolsConfirmAsUsual() = runTest {
        val result = server().run(issue(), """{"to":"real_send"}""")
        assertFalse(result.isError())
        assertEquals("Send 'hi' to Tania Cohen", prompts.single().summary)
        assertEquals("real_send", lastAudit().tool)
        assertEquals("approved by user; via run_it", lastAudit().detail)
    }

    @Test
    fun anAskRuleOnTheDispatchingToolForcesOneApproval() = runTest {
        val s = server()
        val token = issue(policy = ConfirmPolicy.NEVER) { it.copy(toolRules = mapOf("run_it" to ToolRule.ASK)) }
        s.run(token, """{"to":"com.app/play","q":"x"}""")
        assertEquals("com.app/play", prompts.single().toolName)
        assertEquals("approved by user; via run_it", lastAudit().detail)
        // An internally-confirming resolved tool still asks exactly once, with its resolved details.
        prompts.clear()
        assertFalse(s.run(token, """{"to":"real_send"}""").isError())
        assertEquals("Send 'hi' to Tania Cohen", prompts.single().summary)
        assertEquals(2, sideEffects.get())
    }

    @Test
    fun anAllowRuleOnTheDispatchingToolKeepsTheResolvedToolsApproval() = runTest {
        val token = issue { it.copy(toolRules = mapOf("run_it" to ToolRule.ALLOW)) }
        server().run(token, """{"to":"com.app/send","q":"hi"}""")
        assertEquals("com.app/send", prompts.single().toolName)
    }

    @Test
    fun rateLimitsCountEachDispatchedCallOnce() = runTest {
        val s = server(RateLimits(callsPerMinute = 2))
        val token = issue()
        assertFalse(s.run(token, """{"to":"com.app/play"}""").isError())
        assertFalse(s.run(token, """{"to":"com.app/play"}""").isError())
        assertTrue(s.run(token, """{"to":"com.app/play"}""").isError())
        assertEquals(AuditOutcome.RATE_LIMITED, lastAudit().outcome)
        assertEquals("com.app/play", lastAudit().tool)
    }

    @Test
    fun pausedCallsAreRefusedBeforeResolving() = runTest {
        accessEnabled = false
        val result = server().run(issue(), """{"blocked":"com.bank"}""")
        assertEquals(ToolCalls.PAUSED_MESSAGE, result.text())
        assertEquals(AuditOutcome.PAUSED, lastAudit().outcome)
        assertEquals("run_it", lastAudit().tool)
    }

    @Test
    fun losingEitherToolMidCallStopsIt() = runTest {
        val s = server()
        for (rule in listOf("com.app/wait", "run_it", "com.app/alias")) {
            started = CompletableDeferred()
            gate = CompletableDeferred()
            val token = issue()
            val response = async { s.run(token, """{"to":"com.app/wait","alias":"com.app/alias"}""") }
            started.await()
            tokens.update(token.record.id) { it.copy(toolRules = mapOf(rule to ToolRule.BLOCK)) }
            gate.complete(Unit)
            val result = response.await()
            assertEquals(StopReason.TOOL_WITHDRAWN.message, result.text(), "BLOCK on $rule")
            assertEquals(AuditOutcome.CANCELLED, lastAudit().outcome)
            assertEquals("com.app/wait", lastAudit().tool)
            assertEquals(
                "stopped while running (${StopReason.TOOL_WITHDRAWN.detail}); the action may have partly completed; via run_it (com.app/alias)",
                lastAudit().detail,
            )
        }
        assertEquals(0, sideEffects.get())
    }

    @Test
    fun cancellingADispatchedCallAuditsTheResolvedTool() = runTest {
        val s = server()
        val token = issue()
        val response = async { s.run(token, """{"to":"com.app/wait"}""") }
        started.await()
        accessEnabled = false
        assertEquals(1, s.cancelCalls())
        assertEquals(StopReason.PAUSED.message, response.await().text())
        assertEquals(1, audit.entries.value.size)
        assertEquals(AuditOutcome.CANCELLED, lastAudit().outcome)
        assertEquals("com.app/wait", lastAudit().tool)
        assertEquals(0, sideEffects.get())
    }

    @Test
    fun calledDirectlyTheDispatchingToolRefuses() = runTest {
        val ctx = CallContext("t", "n", Scopes.ALL, "local", "r") { }
        val e = runCatching { runner.call(JsonObject(emptyMap()), ctx) }.exceptionOrNull()
        assertTrue(e is ToolException)
        assertNull(lastContext.get())
    }
}
