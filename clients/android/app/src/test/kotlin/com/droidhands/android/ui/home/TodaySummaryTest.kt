package com.droidhands.android.ui.home

import com.droidhands.core.audit.AuditEntry
import com.droidhands.core.audit.AuditOutcome
import com.droidhands.core.audit.TokenUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class TodaySummaryTest {
    private val zone = ZoneId.of("America/New_York")
    private val now = ZonedDateTime.of(2024, 11, 14, 15, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val yesterday = ZonedDateTime.of(2024, 11, 13, 23, 59, 0, 0, zone).toInstant().toEpochMilli()

    private fun entry(ts: Long, tokenId: String?, outcome: AuditOutcome = AuditOutcome.OK, method: String = "tools/call") =
        AuditEntry(timestamp = ts, tokenId = tokenId, tokenName = null, method = method, tool = "x", outcome = outcome, transport = "relay")

    @Test
    fun countsTodaysToolCallsFromTheLog() {
        val entries = listOf(
            entry(now - 1000, "a"),
            entry(now - 2000, "a", AuditOutcome.DENIED_BY_USER),
            entry(now - 3000, "b", AuditOutcome.TOOL_ERROR),
            entry(now - 4000, null, AuditOutcome.AUTH_FAILED, method = "initialize"),
            entry(now - 5000, "b", method = "tools/list"),
            entry(yesterday, "c"),
        )
        val summary = TodaySummary.compute(entries, emptyMap(), now, zone)
        assertEquals(TodaySummary(actions = 3, agents = 2, denied = 1, errors = 1), summary)
        assertEquals("3 actions by 2 agents · 1 denied · 1 failed", TodaySummary.text(summary))
    }

    @Test
    fun usageCountersWinWhenTheLogDroppedEntries() {
        val usage = mapOf(
            "a" to TokenUsage("a", perDay = mapOf("2024-11-14" to 23L)),
            "c" to TokenUsage("c", perDay = mapOf("2024-11-13" to 5L)),
        )
        val summary = TodaySummary.compute(listOf(entry(now - 1000, "a")), usage, now, zone)
        assertEquals(TodaySummary(actions = 23, agents = 1, denied = 0, errors = 0), summary)
        assertEquals("23 actions by 1 agent", TodaySummary.text(summary))
    }

    @Test
    fun emptyDay() {
        val summary = TodaySummary.compute(listOf(entry(yesterday, "a")), emptyMap(), now, zone)
        assertTrue(summary.isEmpty)
        assertEquals("No agent activity today", TodaySummary.text(summary))
        assertEquals("1 action by 1 agent", TodaySummary.text(TodaySummary(1, 1, 0, 0)))
    }
}
