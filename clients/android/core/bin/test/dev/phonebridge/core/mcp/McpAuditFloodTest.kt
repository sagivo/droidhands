package dev.phonebridge.core.mcp

import dev.phonebridge.core.audit.AuditOutcome
import dev.phonebridge.core.audit.InMemoryAuditLog
import dev.phonebridge.core.auth.ConfirmPolicy
import dev.phonebridge.core.auth.DeviceIdentity
import dev.phonebridge.core.auth.InMemoryTokenStore
import dev.phonebridge.core.auth.TokenManager
import dev.phonebridge.core.confirm.ConfirmationResult
import dev.phonebridge.core.policy.PolicyEngine
import dev.phonebridge.core.policy.RateLimits
import dev.phonebridge.core.tools.Risk
import dev.phonebridge.core.tools.Scopes
import dev.phonebridge.core.tools.ToolRegistry
import dev.phonebridge.core.tools.ToolResult
import dev.phonebridge.core.tools.tool
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Junk and refused requests cannot push real entries out of the audit log (docs/SECURITY.md "Audit log"). */
class McpAuditFloodTest {
    private val identity = DeviceIdentity(ByteArray(32) { it.toByte() })
    private val tokens = TokenManager(InMemoryTokenStore(), identity.deviceId)
    private val audit = InMemoryAuditLog(capacity = 100)
    private val registry = ToolRegistry()
    private var now = 1_800_000_000_000L

    @Volatile private var accessEnabled = true

    init {
        registry.register(
            tool(name = "sms_send", title = "Send SMS", description = "d", scope = Scopes.MESSAGES_SEND, risk = Risk.SENSITIVE) { _, _ ->
                ToolResult.text("sent")
            },
        )
        registry.register(tool(name = "echo", title = "Echo", description = "d", scope = Scopes.DEVICE, risk = Risk.READ) { _, _ -> ToolResult.text("ok") })
    }

    private fun server(limits: RateLimits = RateLimits()) = McpServer(
        registry = registry,
        tokens = tokens,
        policy = PolicyEngine(limits, clock = { now }),
        confirmations = { ConfirmationResult.APPROVED },
        audit = audit,
        serverInfo = ServerInfo("phonebridge", "0.1.0"),
        instructions = null,
        isAccessEnabled = { accessEnabled },
        clock = { now },
    )

    private fun issue(scopes: Set<String> = Scopes.ALL) = tokens.issue("Claude", scopes, ConfirmPolicy.NEVER, null).plaintext

    private fun call(name: String, id: Int = 1) =
        """{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"$name","arguments":{"to":"+15551234","text":"hi"}}}"""

    private fun batch(name: String) = "[" + (1..McpServer.MAX_BATCH).joinToString(",") { call(name, it) } + "]"

    private suspend fun McpServer.post(token: String?, body: String, address: String = "127.0.0.1") =
        handle(TransportRequest(token?.let { "Bearer $it" }, body, "local", remoteAddress = address))

    @Test
    fun junkCallsCannotEvictTheRecordOfARealAction() = runTest {
        val s = server()
        val token = issue()
        s.post(token, call("sms_send"))
        repeat(16) {
            val response = s.post(token, batch("x"))
            val answers = Json.parseToJsonElement(response.body!!).jsonArray
            assertEquals(McpServer.MAX_BATCH, answers.size, "every junk call is still answered")
            answers.forEach { assertEquals(-32602, it.jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.int) }
            now += 100
        }
        val entries = audit.entries.value
        assertEquals(6, entries.size, "the real call plus 5 junk entries")
        assertEquals("sms_send", entries.last().tool)
        assertEquals(AuditOutcome.OK, entries.last().outcome)

        // After the window, the next activity is preceded by one entry that counts the rest.
        now += 60_000
        s.post(token, call("echo"))
        val summary = audit.entries.value[1]
        assertEquals(AuditOutcome.UNKNOWN_TOOL, summary.outcome)
        assertEquals("x", summary.tool)
        assertTrue(summary.detail!!.startsWith("Flood protection: ${16 * McpServer.MAX_BATCH - 5} more requests like this"), summary.detail)
        assertEquals("echo", audit.entries.value[0].tool)
    }

    @Test
    fun refusedRealToolsAreThrottledToo() = runTest {
        // Out of scope, then rate-limited, then paused: each is logged up to its budget, never evicting the rest.
        val s = server(RateLimits(callsPerMinute = 2, sensitiveCallsPerMinute = 2))
        val limited = issue(setOf(Scopes.DEVICE))
        repeat(50) { s.post(limited, call("sms_send")) }
        assertEquals(5, audit.entries.value.count { it.outcome == AuditOutcome.DENIED_SCOPE })

        val token = issue()
        repeat(50) { s.post(token, call("echo")) }
        assertEquals(2, audit.entries.value.count { it.outcome == AuditOutcome.OK })
        assertEquals(5, audit.entries.value.count { it.outcome == AuditOutcome.RATE_LIMITED })

        accessEnabled = false
        repeat(50) { s.post(token, call("echo")) }
        assertEquals(5, audit.entries.value.count { it.outcome == AuditOutcome.PAUSED })
        assertEquals(17, audit.entries.value.size)
    }

    @Test
    fun authFailuresAreThrottledPerCaller() = runTest {
        val s = server()
        repeat(100) { assertEquals(401, s.post("pb1.bogus", call("echo")).status) }
        assertEquals(5, audit.entries.value.size)
        repeat(10) { s.post(null, call("echo"), address = "10.0.0.7") }
        assertEquals(10, audit.entries.value.size, "another caller has its own budget")
        assertEquals(setOf(AuditOutcome.AUTH_FAILED), audit.entries.value.map { it.outcome }.toSet())
    }
}
