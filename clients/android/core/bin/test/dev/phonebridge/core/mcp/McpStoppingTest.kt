package dev.phonebridge.core.mcp

import dev.phonebridge.core.audit.AuditOutcome
import dev.phonebridge.core.audit.InMemoryAuditLog
import dev.phonebridge.core.auth.ConfirmPolicy
import dev.phonebridge.core.auth.DeviceIdentity
import dev.phonebridge.core.auth.InMemoryTokenStore
import dev.phonebridge.core.auth.IssuedToken
import dev.phonebridge.core.auth.TokenManager
import dev.phonebridge.core.auth.ToolRule
import dev.phonebridge.core.confirm.ConfirmationProvider
import dev.phonebridge.core.confirm.ConfirmationRequest
import dev.phonebridge.core.confirm.ConfirmationResult
import dev.phonebridge.core.policy.PolicyEngine
import dev.phonebridge.core.tools.Risk
import dev.phonebridge.core.tools.Scopes
import dev.phonebridge.core.tools.ToolRegistry
import dev.phonebridge.core.tools.ToolResult
import dev.phonebridge.core.tools.tool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pause, revoke, delete, expiry and permission changes stop calls that are already in flight
 * ([McpServer.cancelCalls], [dev.phonebridge.core.tools.CallContext.ensureStillAllowed]), and every
 * stopped call is audited.
 */
class McpStoppingTest {
    private val identity = DeviceIdentity(ByteArray(32) { it.toByte() })
    private val tokens = TokenManager(InMemoryTokenStore(), identity.deviceId)
    private val audit = InMemoryAuditLog()
    private val registry = ToolRegistry()
    private val prompts: MutableList<ConfirmationRequest> = Collections.synchronizedList(mutableListOf())
    private val sideEffects = AtomicInteger()

    @Volatile private var accessEnabled = true

    @Volatile private var now = System.currentTimeMillis()

    @Volatile private var answer: suspend (ConfirmationRequest) -> ConfirmationResult = { ConfirmationResult.APPROVED }

    /** Completed by `wait_then_act` / `slow_send` once running; keyed by token id. */
    private val started = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    /** What `wait_then_act` waits for; [gate] unless a test sets one for a token. */
    private val gates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val gate = CompletableDeferred<Unit>()
    private val toolCancelled = CompletableDeferred<Unit>()

    private fun startedFor(tokenId: String) = started.getOrPut(tokenId) { CompletableDeferred() }

    init {
        // Like ui_tap waiting for the user to unlock: waits, re-checks, then acts.
        registry.register(
            tool(name = "wait_then_act", title = "Wait then act", description = "d", scope = Scopes.SCREEN_CONTROL, risk = Risk.ACTION) { _, ctx ->
                startedFor(ctx.tokenId).complete(Unit)
                try {
                    (gates[ctx.tokenId] ?: gate).await()
                } catch (e: CancellationException) {
                    toolCancelled.complete(Unit)
                    throw e
                }
                ctx.ensureStillAllowed()
                sideEffects.incrementAndGet()
                ToolResult.text("done")
            },
        )
        registry.register(
            tool(name = "send_pre", title = "Send (pre)", description = "d", scope = Scopes.MESSAGES_SEND, risk = Risk.SENSITIVE) { _, _ ->
                sideEffects.incrementAndGet()
                ToolResult.text("sent")
            },
        )
        // Resolves its target (slowly), then confirms itself.
        registry.register(
            tool(
                name = "slow_send", title = "Send (internal)", description = "d", scope = Scopes.MESSAGES_SEND,
                risk = Risk.SENSITIVE, confirmsInternally = true,
            ) { _, ctx ->
                startedFor(ctx.tokenId).complete(Unit)
                gate.await()
                ctx.requireConfirmation("Send 'hi' to Tania Cohen")
                sideEffects.incrementAndGet()
                ToolResult.text("sent")
            },
        )
    }

    private fun server() = McpServer(
        registry = registry,
        tokens = tokens,
        policy = PolicyEngine(),
        confirmations = ConfirmationProvider { req ->
            prompts += req
            answer(req)
        },
        audit = audit,
        serverInfo = ServerInfo("phonebridge", "0.1.0"),
        instructions = null,
        isAccessEnabled = { accessEnabled },
        clock = { now },
    )

    private fun issue(name: String = "Claude", ttlMillis: Long? = null): IssuedToken =
        tokens.issue(name, Scopes.ALL, ConfirmPolicy.SENSITIVE, ttlMillis)

    private fun call(name: String, id: Int = 1) =
        """{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"$name","arguments":{}}}"""

    private suspend fun McpServer.post(token: IssuedToken, body: String) =
        handle(TransportRequest("Bearer ${token.plaintext}", body, "relay"))

    private fun TransportResponse.result(): JsonObject = Json.parseToJsonElement(body!!).jsonObject["result"]!!.jsonObject

    private fun JsonObject.text() = this["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content

    private fun JsonObject.isError() = this["isError"]!!.jsonPrimitive.boolean

    private fun lastAudit() = audit.entries.value.first()

    private val whileRunning = { reason: StopReason -> "stopped while running (${reason.detail}); the action may have partly completed" }
    private val beforeRunning = { reason: StopReason -> "stopped before it ran (${reason.detail})" }

    @Test
    fun pauseCancelsACallWaitingToActAndAuditsIt() = runTest {
        val s = server()
        val token = issue()
        val response = async { s.post(token, call("wait_then_act")) }
        startedFor(token.record.id).await()

        accessEnabled = false
        assertEquals(1, s.cancelCalls())
        val result = response.await().result()
        assertTrue(result.isError())
        assertEquals(StopReason.PAUSED.message, result.text())
        toolCancelled.await()
        assertEquals(0, sideEffects.get())
        assertEquals(1, audit.entries.value.size)
        assertEquals(AuditOutcome.CANCELLED, lastAudit().outcome)
        assertEquals("wait_then_act", lastAudit().tool)
        assertEquals(whileRunning(StopReason.PAUSED), lastAudit().detail)
        assertEquals(0, s.inFlightCalls)

        // Nothing is left to cancel.
        assertEquals(0, s.cancelCalls())
    }

    @Test
    fun ensureStillAllowedRefusesAfterAPauseEvenWithoutCancel() = runTest {
        val s = server()
        val token = issue()
        val response = async { s.post(token, call("wait_then_act")) }
        startedFor(token.record.id).await()
        accessEnabled = false
        gate.complete(Unit) // E.g. the user unlocked the phone to check after tapping Pause.
        val result = response.await().result()
        assertTrue(result.isError())
        assertEquals(StopReason.PAUSED.message, result.text())
        assertEquals(0, sideEffects.get())
        assertEquals(AuditOutcome.CANCELLED, lastAudit().outcome)
        assertEquals(whileRunning(StopReason.PAUSED), lastAudit().detail)
    }

    @Test
    fun revokeCancelsOnlyThatAgentsCalls() = runTest {
        val s = server()
        val a = issue("Agent A")
        val b = issue("Agent B")
        val ra = async { s.post(a, call("wait_then_act")) }
        val rb = async { s.post(b, call("wait_then_act")) }
        startedFor(a.record.id).await()
        startedFor(b.record.id).await()

        tokens.revoke(a.record.id)
        assertEquals(1, s.cancelCalls(a.record.id))
        assertEquals(StopReason.REVOKED.message, ra.await().result().text())
        assertEquals(1, s.inFlightCalls)

        gate.complete(Unit)
        assertEquals("done", rb.await().result().text())
        assertEquals(1, sideEffects.get())
        val byAgent = audit.entries.value.associateBy { it.tokenName }
        assertEquals(AuditOutcome.CANCELLED, byAgent["Agent A"]!!.outcome)
        assertEquals(whileRunning(StopReason.REVOKED), byAgent["Agent A"]!!.detail)
        assertEquals(AuditOutcome.OK, byAgent["Agent B"]!!.outcome)
    }

    @Test
    fun revokeAllThenCancelAllGivesEachCallItsReason() = runTest {
        val s = server()
        val a = issue("Agent A")
        val b = issue("Agent B")
        val ra = async { s.post(a, call("wait_then_act")) }
        val rb = async { s.post(b, call("wait_then_act")) }
        startedFor(a.record.id).await()
        startedFor(b.record.id).await()
        tokens.revokeAll()
        assertEquals(2, s.cancelCalls())
        assertEquals(StopReason.REVOKED.message, ra.await().result().text())
        assertEquals(StopReason.REVOKED.message, rb.await().result().text())
        assertEquals(2, audit.entries.value.count { it.detail == whileRunning(StopReason.REVOKED) })
    }

    @Test
    fun deleteCancelsWithTheRemovedReason() = runTest {
        val s = server()
        val token = issue()
        val response = async { s.post(token, call("wait_then_act")) }
        startedFor(token.record.id).await()
        tokens.delete(token.record.id)
        assertEquals(1, s.cancelCalls(token.record.id))
        assertEquals(StopReason.DELETED.message, response.await().result().text())
        assertEquals(whileRunning(StopReason.DELETED), lastAudit().detail)
        assertEquals("Claude", lastAudit().tokenName)
    }

    @Test
    fun cancellingWithdrawsTheOpenPrompt() = runTest {
        val shown = CompletableDeferred<Unit>()
        val withdrawn = CompletableDeferred<Unit>()
        answer = {
            shown.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                withdrawn.complete(Unit)
            }
        }
        val s = server()
        val response = async { s.post(issue(), call("send_pre")) }
        shown.await()
        accessEnabled = false
        assertEquals(1, s.cancelCalls())
        assertEquals(StopReason.PAUSED.message, response.await().result().text())
        assertTrue(withdrawn.isCompleted, "the confirmation provider must see the cancellation")
        assertEquals(0, sideEffects.get())
        assertEquals(AuditOutcome.CANCELLED, lastAudit().outcome)
        assertEquals(beforeRunning(StopReason.PAUSED), lastAudit().detail)
    }

    @Test
    fun aPauseThatDeniesTheOpenPromptIsReportedAsAPause() = runTest {
        answer = {
            accessEnabled = false // Pausing denies every open prompt.
            ConfirmationResult.DENIED
        }
        val result = server().post(issue(), call("send_pre")).result()
        assertEquals(StopReason.PAUSED.message, result.text())
        assertEquals(AuditOutcome.CANCELLED, lastAudit().outcome)
        assertEquals(beforeRunning(StopReason.PAUSED), lastAudit().detail)
    }

    @Test
    fun anApprovalThatArrivesAfterRevokeDoesNotRun() = runTest {
        val s = server()
        val pre = issue("Pre")
        answer = {
            tokens.revoke(pre.record.id)
            ConfirmationResult.APPROVED
        }
        assertEquals(StopReason.REVOKED.message, s.post(pre, call("send_pre")).result().text())
        assertEquals(beforeRunning(StopReason.REVOKED), lastAudit().detail)

        val internal = issue("Internal")
        answer = {
            tokens.revoke(internal.record.id)
            ConfirmationResult.APPROVED
        }
        gate.complete(Unit)
        val result = s.post(internal, call("slow_send")).result()
        assertTrue(result.isError())
        assertEquals(StopReason.REVOKED.message, result.text())
        assertEquals(AuditOutcome.CANCELLED, lastAudit().outcome)
        assertEquals(whileRunning(StopReason.REVOKED) + "; approved by user", lastAudit().detail)
        assertEquals(0, sideEffects.get())
    }

    @Test
    fun noPromptIsShownOnceAccessIsPaused() = runTest {
        val s = server()
        val token = issue()
        val response = async { s.post(token, call("slow_send")) }
        startedFor(token.record.id).await()
        accessEnabled = false
        gate.complete(Unit) // The tool resolved its target and now asks for confirmation.
        assertEquals(StopReason.PAUSED.message, response.await().result().text())
        assertEquals(0, prompts.size)
        assertEquals(0, sideEffects.get())
        assertEquals(AuditOutcome.CANCELLED, lastAudit().outcome)
    }

    @Test
    fun losingTheToolMidCallStopsIt() = runTest {
        val s = server()
        for (change in listOf<(dev.phonebridge.core.auth.TokenRecord) -> dev.phonebridge.core.auth.TokenRecord>(
            { it.copy(scopes = it.scopes - Scopes.SCREEN_CONTROL) },
            { it.copy(maxRisk = Risk.READ) },
            { it.copy(toolRules = mapOf("wait_then_act" to ToolRule.BLOCK)) },
        )) {
            val token = issue()
            val release = CompletableDeferred<Unit>().also { gates[token.record.id] = it }
            val response = async { s.post(token, call("wait_then_act")) }
            startedFor(token.record.id).await()
            tokens.update(token.record.id, change)
            release.complete(Unit)
            assertEquals(StopReason.TOOL_WITHDRAWN.message, response.await().result().text())
            assertEquals(whileRunning(StopReason.TOOL_WITHDRAWN), lastAudit().detail)
        }
        assertEquals(0, sideEffects.get())
    }

    @Test
    fun expiringMidCallStopsIt() = runTest {
        val s = server()
        val token = issue(ttlMillis = 60_000)
        val response = async { s.post(token, call("wait_then_act")) }
        startedFor(token.record.id).await()
        now += 120_000
        gate.complete(Unit)
        assertEquals(StopReason.EXPIRED.message, response.await().result().text())
        assertEquals(0, sideEffects.get())
    }

    @Test
    fun callsWhoseAgentDisconnectedCanStillBeCancelled() = runTest {
        val s = server()
        val token = issue()
        val job = launch { s.post(token, call("wait_then_act")) }
        startedFor(token.record.id).await()
        job.cancel() // The relay connection was restarted.
        job.join()
        assertEquals(1, s.inFlightCalls)

        accessEnabled = false
        assertEquals(1, s.cancelCalls())
        while (s.inFlightCalls > 0) yield()
        assertEquals(0, sideEffects.get())
        val entry = lastAudit()
        assertEquals(AuditOutcome.CANCELLED, entry.outcome)
        assertEquals(whileRunning(StopReason.PAUSED) + "; " + ToolCalls.ABANDONED_NOTE, entry.detail)
    }

    @Test
    fun everyCallOfABatchIsStoppedAndAnswered() = runTest {
        val s = server()
        val token = issue()
        val body = "[" + (1..3).joinToString(",") { call("wait_then_act", it) } + "]"
        val response = async { s.post(token, body) }
        startedFor(token.record.id).await()
        while (s.inFlightCalls < 3) yield()
        accessEnabled = false
        assertEquals(3, s.cancelCalls())
        val answers = Json.parseToJsonElement(response.await().body!!).jsonArray.map { it.jsonObject["result"]!!.jsonObject }
        assertEquals(3, answers.size)
        answers.forEach { assertEquals(StopReason.PAUSED.message, it.text()) }
        assertEquals(3, audit.entries.value.count { it.outcome == AuditOutcome.CANCELLED })
        assertEquals(0, sideEffects.get())
    }

    @Test
    fun cancelCallsForAnotherTokenLeavesTheCallAlone() = runTest {
        val s = server()
        val token = issue()
        val other = issue("Other")
        val response = async { s.post(token, call("wait_then_act")) }
        startedFor(token.record.id).await()
        assertEquals(0, s.cancelCalls(other.record.id))
        gate.complete(Unit)
        val result = response.await().result()
        assertFalse(result.isError())
        assertEquals(AuditOutcome.OK, lastAudit().outcome)
        assertNull(lastAudit().detail)
    }
}
