package dev.phonebridge.core.mcp

import dev.phonebridge.core.audit.AuditEntry
import dev.phonebridge.core.audit.AuditLog
import dev.phonebridge.core.audit.AuditOutcome
import dev.phonebridge.core.audit.InMemoryAuditLog
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuditThrottleTest {
    private var now = 1_000_000L
    private val log = InMemoryAuditLog(capacity = 1_000)

    private fun throttle(perKey: Int = 3, global: Int = 10, maxKeys: Int = 4) =
        AuditThrottle(log, { now }, perKeyBudget = perKey, globalBudget = global, windowMs = 60_000, maxKeys = maxKeys)

    private fun entry(
        outcome: AuditOutcome,
        tool: String? = "x",
        tokenId: String? = "tok",
        method: String = "tools/call",
        detail: String? = null,
    ) = AuditEntry(timestamp = now, tokenId = tokenId, tokenName = tokenId?.let { "Agent $it" }, method = method, tool = tool, outcome = outcome, transport = "relay", detail = detail)

    private fun oldestFirst() = log.entries.value.reversed()

    @Test
    fun recordedEntriesAreNeverThrottled() {
        val t = throttle()
        repeat(50) { t.record(entry(AuditOutcome.OK)) }
        assertEquals(50, log.entries.value.size)
    }

    @Test
    fun refusalsBeyondTheBudgetAreSummarizedAfterTheWindow() {
        val t = throttle()
        repeat(100) {
            t.recordRefusal(entry(AuditOutcome.UNKNOWN_TOOL, tool = "junk$it", detail = "d$it"), "token:tok")
            now += 100
        }
        assertEquals(3, log.entries.value.size, "only the budget is written during the window")
        val lastSuppressedAt = now - 100

        now += 60_000
        t.record(entry(AuditOutcome.OK, tool = "sms_send"))
        val entries = oldestFirst()
        assertEquals(5, entries.size)
        assertEquals(listOf("junk0", "junk1", "junk2"), entries.take(3).map { it.tool })
        val summary = entries[3]
        assertEquals(AuditOutcome.UNKNOWN_TOOL, summary.outcome)
        assertEquals("tok", summary.tokenId)
        assertEquals("Agent tok", summary.tokenName)
        assertEquals("tools/call", summary.method)
        assertNull(summary.tool, "several tools")
        assertEquals(lastSuppressedAt, summary.timestamp)
        val detail = summary.detail!!
        assertTrue(detail.startsWith("Flood protection: 97 more requests like this were not logged one by one"), detail)
        assertTrue(detail.contains("tools: junk3, junk4, junk5, …"), detail)
        assertTrue(detail.contains("last: d99"), detail)
        assertEquals("sms_send", entries[4].tool, "the summary comes just before the next activity")
    }

    @Test
    fun aSingleRepeatedToolIsNamedInTheSummary() {
        val t = throttle(perKey = 1)
        repeat(3) { t.recordRefusal(entry(AuditOutcome.DENIED_SCOPE, tool = "sms_send"), "token:tok") }
        now += 60_000
        t.recordRefusal(entry(AuditOutcome.DENIED_SCOPE, tool = "sms_send"), "token:tok")
        val entries = oldestFirst()
        assertEquals(3, entries.size, "first, summary, first of the new window")
        assertEquals("sms_send", entries[1].tool)
        assertTrue(entries[1].detail!!.startsWith("Flood protection: 2 more requests like this were not logged"), entries[1].detail)
        assertEquals(AuditOutcome.DENIED_SCOPE, entries[2].outcome)
        assertNull(entries[2].detail)
    }

    @Test
    fun budgetsArePerSourceAndOutcome() {
        val t = throttle(perKey = 2, global = 100)
        repeat(5) {
            t.recordRefusal(entry(AuditOutcome.UNKNOWN_TOOL), "token:a")
            t.recordRefusal(entry(AuditOutcome.RATE_LIMITED), "token:a")
            t.recordRefusal(entry(AuditOutcome.UNKNOWN_TOOL), "token:b")
        }
        assertEquals(6, log.entries.value.size)
    }

    @Test
    fun theGlobalBudgetCapsManySources() {
        val t = throttle(perKey = 5, global = 4, maxKeys = 100)
        repeat(20) { t.recordRefusal(entry(AuditOutcome.AUTH_FAILED, tokenId = null), "from:local:10.0.0.$it") }
        assertEquals(4, log.entries.value.size)
        now += 60_000
        t.record(entry(AuditOutcome.OK))
        // One summary per source that had suppressed entries, then the OK entry.
        assertEquals(4 + 16 + 1, log.entries.value.size)
    }

    @Test
    fun sourcesBeyondMaxKeysShareOneBucket() {
        val t = throttle(perKey = 1, global = 100, maxKeys = 2)
        repeat(10) { t.recordRefusal(entry(AuditOutcome.AUTH_FAILED, tokenId = null, method = "m$it"), "from:relay:203.0.113.$it") }
        assertEquals(3, t.trackedKeys(), "two sources plus the shared bucket")
        assertEquals(3, log.entries.value.size)
        now += 60_000
        t.record(entry(AuditOutcome.OK))
        val summary = log.entries.value[1]
        assertEquals("multiple", summary.method)
        assertNull(summary.tokenId)
        assertTrue(summary.detail!!.startsWith("Flood protection: 7 more requests"), summary.detail)
        assertEquals(0, t.trackedKeys())
    }

    @Test
    fun mixedTokensInASummaryAreNotAttributed() {
        val t = throttle(perKey = 1, global = 100, maxKeys = 1)
        t.recordRefusal(entry(AuditOutcome.UNKNOWN_TOOL, tokenId = "a"), "token:a")
        t.recordRefusal(entry(AuditOutcome.UNKNOWN_TOOL, tokenId = "b"), "token:b") // shared bucket, written
        t.recordRefusal(entry(AuditOutcome.UNKNOWN_TOOL, tokenId = "c"), "token:c")
        t.recordRefusal(entry(AuditOutcome.UNKNOWN_TOOL, tokenId = "d"), "token:d")
        now += 60_000
        t.record(entry(AuditOutcome.OK))
        val summary = log.entries.value[1]
        assertNull(summary.tokenId)
        assertNull(summary.tokenName)
        assertTrue(summary.detail!!.startsWith("Flood protection: 2 more"), summary.detail)
    }

    @Test
    fun aSingleSuppressedEntryIsWorded() {
        val t = throttle(perKey = 1)
        t.recordRefusal(entry(AuditOutcome.PAUSED), "token:tok")
        t.recordRefusal(entry(AuditOutcome.PAUSED), "token:tok")
        now += 60_000
        t.record(entry(AuditOutcome.OK))
        assertTrue(log.entries.value[1].detail!!.startsWith("Flood protection: 1 more request like this was not logged one by one"))
    }

    @Test
    fun windowsWithoutSuppressedEntriesEndSilently() {
        val t = throttle()
        t.recordRefusal(entry(AuditOutcome.UNKNOWN_TOOL), "token:tok")
        now += 60_000
        t.record(entry(AuditOutcome.OK))
        assertEquals(2, log.entries.value.size)
        assertEquals(0, t.trackedKeys())
    }

    @Test
    fun theClockMovingBackEndsTheWindow() {
        val t = throttle(perKey = 1)
        t.recordRefusal(entry(AuditOutcome.UNKNOWN_TOOL), "token:tok")
        t.recordRefusal(entry(AuditOutcome.UNKNOWN_TOOL), "token:tok")
        now -= 3_600_000
        t.recordRefusal(entry(AuditOutcome.UNKNOWN_TOOL), "token:tok")
        assertEquals(3, log.entries.value.size, "first, summary, first of the new window")
    }

    @Test
    fun aFailingLogNeverThrows() {
        val failing = object : AuditLog {
            override fun record(entry: AuditEntry) = throw IllegalStateException("disk full")
            override val entries = MutableStateFlow(emptyList<AuditEntry>())
            override fun clear() = Unit
        }
        val t = AuditThrottle(failing, { now }, perKeyBudget = 1)
        t.record(entry(AuditOutcome.OK))
        repeat(3) { t.recordRefusal(entry(AuditOutcome.UNKNOWN_TOOL), "token:tok") }
        now += 60_000
        t.record(entry(AuditOutcome.OK))
    }
}
