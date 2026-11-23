package com.droidhands.android.ui.activity

import com.droidhands.core.audit.AuditEntry
import com.droidhands.core.audit.AuditOutcome
import com.droidhands.core.auth.TokenRecord
import com.droidhands.core.tools.Scopes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class ActivityFiltersTest {
    private val zone = ZoneId.of("Asia/Jerusalem")
    private fun at(day: Int, hour: Int) = ZonedDateTime.of(2024, 11, day, hour, 0, 0, 0, zone).toInstant().toEpochMilli()

    private fun entry(ts: Long, tokenId: String? = "tok1", tool: String? = "sms_send", outcome: AuditOutcome = AuditOutcome.OK, name: String? = "Claude") =
        AuditEntry(timestamp = ts, tokenId = tokenId, tokenName = name, method = "tools/call", tool = tool, outcome = outcome, transport = "relay")

    private fun token(id: String, name: String) =
        TokenRecord(id = id, name = name, secretHash = "00", scopes = Scopes.PRESET_ASSISTANT, createdAt = 0)

    // Newest first, like the audit log.
    private val entries = listOf(
        entry(at(14, 10), tool = "whatsapp_send"),
        entry(at(14, 9), tokenId = "tok2", name = "ChatGPT", outcome = AuditOutcome.DENIED_BY_USER),
        entry(at(13, 22), tool = "screen_read", outcome = AuditOutcome.TOOL_ERROR),
        entry(at(13, 8), tokenId = null, tool = null, outcome = AuditOutcome.AUTH_FAILED, name = null),
        entry(at(10, 12), tokenId = "gone", name = "Old agent"),
    )

    @Test
    fun filtersByAgentOutcomeAndSearch() {
        assertEquals(entries, ActivityFilters.apply(entries, ActivityQuery()))
        assertEquals(2, ActivityFilters.apply(entries, ActivityQuery(agent = "tok1")).size)
        assertEquals(1, ActivityFilters.apply(entries, ActivityQuery(agent = ActivityFilters.NO_TOKEN)).size)
        assertEquals(
            listOf(AuditOutcome.TOOL_ERROR),
            ActivityFilters.apply(entries, ActivityQuery(agent = "tok1", outcome = AuditFilter.ERRORS)).map { it.outcome },
        )
        assertEquals(listOf("whatsapp_send"), ActivityFilters.apply(entries, ActivityQuery(search = "WhatsApp send")).map { it.tool })
        assertEquals(listOf("whatsapp_send"), ActivityFilters.apply(entries, ActivityQuery(search = "whats")).map { it.tool })
        assertEquals(1, ActivityFilters.apply(entries, ActivityQuery(search = "tools/call", outcome = AuditFilter.DENIED, agent = "tok2")).size)
        assertTrue(ActivityFilters.apply(entries, ActivityQuery(search = "calendar")).isEmpty())
        assertFalse(ActivityQuery().isFiltered)
        assertTrue(ActivityQuery(search = "x").isFiltered)
    }

    @Test
    fun agentOptionsIncludeDeletedAndUnknownCallers() {
        val options = ActivityFilters.agentOptions(entries, listOf(token("tok1", "Claude"), token("tok2", "ChatGPT")))
        assertEquals(
            listOf(null to "All agents", "tok1" to "Claude", "tok2" to "ChatGPT", "gone" to "Old agent (deleted)", ActivityFilters.NO_TOKEN to "Unknown callers"),
            options.map { it.agent to it.label },
        )
        assertEquals("ChatGPT", ActivityFilters.agentLabel(options, "tok2"))
        assertEquals("All agents", ActivityFilters.agentLabel(options, "vanished"))
    }

    @Test
    fun rowsGetDayHeaders() {
        val rows = ActivityFilters.rows(entries, zone)
        val headers = rows.filterIsInstance<ActivityRow.DayHeader>()
        assertEquals(listOf(LocalDate.of(2024, 11, 14), LocalDate.of(2024, 11, 13), LocalDate.of(2024, 11, 10)), headers.map { it.date })
        assertEquals(listOf(2, 2, 1), headers.map { it.count })
        assertEquals(entries.size + headers.size, rows.size)
        assertTrue(rows.first() is ActivityRow.DayHeader)
        assertEquals(rows.size, rows.map { it.key }.toSet().size)
        assertTrue(ActivityFilters.rows(emptyList(), zone).isEmpty())
    }

    @Test
    fun dayLabels() {
        val today = LocalDate.of(2024, 11, 14)
        assertEquals("Today", ActivityFilters.dayLabel(today, today) { "x" })
        assertEquals("Yesterday", ActivityFilters.dayLabel(today.minusDays(1), today) { "x" })
        assertEquals("Nov 10", ActivityFilters.dayLabel(LocalDate.of(2024, 11, 10), today) { "Nov ${it.dayOfMonth}" })
    }
}
