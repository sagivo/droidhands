package com.droidhands.core.mcp

import com.droidhands.core.audit.AuditOutcome
import com.droidhands.core.audit.InMemoryAuditLog
import com.droidhands.core.auth.ConfirmPolicy
import com.droidhands.core.auth.DeviceIdentity
import com.droidhands.core.auth.InMemoryTokenStore
import com.droidhands.core.auth.IssuedToken
import com.droidhands.core.auth.TokenManager
import com.droidhands.core.confirm.ConfirmationProvider
import com.droidhands.core.confirm.ConfirmationResult
import com.droidhands.core.policy.GlobalSecurity
import com.droidhands.core.policy.PolicyEngine
import com.droidhands.core.tools.Risk
import com.droidhands.core.tools.Scopes
import com.droidhands.core.tools.ToolRegistry
import com.droidhands.core.tools.ToolResult
import com.droidhands.core.tools.tool
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What [CallActivity] (the live activity notification) learns about tool calls, and the notification's Stop ([McpServer.stopCalls]). */
class McpActivityTest {
    private val identity = DeviceIdentity(ByteArray(32) { it.toByte() })
    private val tokens = TokenManager(InMemoryTokenStore(), identity.deviceId)
    private val audit = InMemoryAuditLog()
    private val registry = ToolRegistry()
    private val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val calls: MutableList<ActiveCall> = Collections.synchronizedList(mutableListOf())
    private val gate = CompletableDeferred<Unit>()
    private val running = CompletableDeferred<Unit>()

    @Volatile private var redact = true

    @Volatile private var answer = ConfirmationResult.APPROVED

    private val listener = object : CallActivity {
        override fun onStarted(call: ActiveCall) = log("started", call)

        override fun onUpdated(call: ActiveCall) = log("updated", call)

        override fun onFinished(call: ActiveCall, outcome: AuditOutcome) = log("finished $outcome", call)

        private fun log(event: String, call: ActiveCall) {
            val state = listOfNotNull(call.label, "approval".takeIf { call.awaitingApproval }).joinToString(", ")
            events += if (state.isEmpty()) "$event ${call.toolName}" else "$event ${call.toolName} ($state)"
            calls += call
        }
    }

    init {
        registry.register(tool(name = "read_it", title = "Read it", description = "d", scope = Scopes.DEVICE, risk = Risk.READ) { _, _ -> ToolResult.text("ok") })
        registry.register(
            tool(
                name = "send_it", title = "Send message", description = "d", scope = Scopes.MESSAGES_SEND,
                risk = Risk.SENSITIVE, confirmsInternally = true,
            ) { _, ctx ->
                ctx.reportActivity("  Sending to Tania Cohen  ")
                ctx.requireConfirmation("Send 'hi' to Tania Cohen")
                ToolResult.text("sent")
            },
        )
        registry.register(
            tool(name = "wait_it", title = "Wait", description = "d", scope = Scopes.DEVICE, risk = Risk.ACTION) { _, ctx ->
                ctx.reportActivity("Your code is 482913")
                running.complete(Unit)
                gate.await()
                ToolResult.text("done")
            },
        )
        registry.register(tool(name = "broken", title = "Broken", description = "d", scope = Scopes.DEVICE, risk = Risk.ACTION) { _, _ -> error("boom") })
    }

    private fun server(activity: CallActivity = listener) = McpServer(
        registry = registry,
        tokens = tokens,
        policy = PolicyEngine(),
        confirmations = ConfirmationProvider { answer },
        audit = audit,
        serverInfo = ServerInfo("droidhands", "0.1.0"),
        instructions = null,
        globalSecurity = { GlobalSecurity(redactOneTimeCodes = redact) },
        activity = activity,
    )

    private fun issue(name: String = "Claude Code", scopes: Set<String> = Scopes.ALL): IssuedToken =
        tokens.issue(name, scopes, ConfirmPolicy.SENSITIVE, null)

    private suspend fun McpServer.call(token: IssuedToken, name: String) = handle(
        TransportRequest("Bearer ${token.plaintext}", """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$name"}}""", "relay"),
    ).let { Json.parseToJsonElement(it.body!!).jsonObject.let { response -> response["result"]?.jsonObject ?: response } }

    @Test
    fun aCallIsReportedFromStartToItsAuditEntry() = runTest {
        server().call(issue(), "read_it")
        assertEquals(listOf("started read_it", "finished OK read_it"), events)
        val call = calls.first()
        assertEquals("Claude Code", call.agentName)
        assertEquals("Read it", call.toolTitle)
        assertEquals(Risk.READ, call.risk)
        assertEquals(calls.first().id, calls.last().id)
    }

    @Test
    fun approvalAndTheToolsOwnLineAreReported() = runTest {
        server().call(issue(), "send_it")
        assertEquals(
            listOf(
                "started send_it",
                "updated send_it (Sending to Tania Cohen)",
                "updated send_it (Sending to Tania Cohen, approval)",
                "updated send_it (Sending to Tania Cohen)",
                "finished OK send_it (Sending to Tania Cohen)",
            ),
            events,
        )
    }

    @Test
    fun aDeniedApprovalFinishesWithTheDenial() = runTest {
        answer = ConfirmationResult.DENIED
        server().call(issue(), "send_it")
        assertEquals("finished DENIED_BY_USER send_it (Sending to Tania Cohen)", events.last())
    }

    @Test
    fun refusedCallsAreNeverShown() = runTest {
        val s = server()
        val limited = issue(scopes = setOf(Scopes.DEVICE))
        s.call(limited, "send_it") // Missing scope.
        s.call(limited, "no_such_tool")
        assertEquals(emptyList(), events)
    }

    @Test
    fun failuresAndCrashesAreReported() = runTest {
        server().call(issue(), "broken")
        assertEquals("finished EXCEPTION broken", events.last())
    }

    @Test
    fun oneTimeCodesInTheLineAreMaskedWhenTheUserHidesThem() = runTest {
        val s = server()
        val token = issue()
        val response = async { s.call(token, "wait_it") }
        running.await()
        val label = calls.last().label!!
        assertFalse("482913" in label, label)
        gate.complete(Unit)
        response.await()

        assertEquals("Your code is 482913", ToolCalls.activityLabel("Your code is 482913", redact = false))
        assertEquals(null, ToolCalls.activityLabel("   ", redact = false))
        assertEquals(ToolCalls.MAX_ACTIVITY_LABEL_CHARS, ToolCalls.activityLabel("x".repeat(500), redact = false)!!.length)
    }

    @Test
    fun stopEndsTheAgentsCallsAsStoppedByTheUser() = runTest {
        val s = server()
        val token = issue()
        val other = issue("Codex")
        val response = async { s.call(token, "wait_it") }
        running.await()

        assertEquals(0, s.stopCalls(other.record.id))
        assertEquals(1, s.stopCalls(token.record.id))
        val result = response.await()
        assertEquals(StopReason.STOPPED_BY_USER.message, result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        val entry = audit.entries.value.first()
        assertEquals(AuditOutcome.CANCELLED, entry.outcome)
        assertEquals("stopped while running (stopped by the user); the action may have partly completed", entry.detail)
        assertTrue(events.last().startsWith("finished CANCELLED wait_it"), events.last())
    }

    @Test
    fun stopAfterARevokeSaysRevoked() = runTest {
        val s = server()
        val token = issue()
        val response = async { s.call(token, "wait_it") }
        running.await()
        tokens.revoke(token.record.id)
        s.stopCalls(token.record.id)
        assertEquals(StopReason.REVOKED.message, response.await()["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun aThrowingListenerNeverFailsTheCall() = runTest {
        val broken = object : CallActivity {
            override fun onStarted(call: ActiveCall) = error("listener bug")

            override fun onFinished(call: ActiveCall, outcome: AuditOutcome) = error("listener bug")
        }
        val result = server(broken).call(issue(), "read_it")
        assertEquals("ok", result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(AuditOutcome.OK, audit.entries.value.first().outcome)
    }
}
