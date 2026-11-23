package com.droidhands.android.ui.activity

import com.droidhands.core.audit.AuditEntry
import com.droidhands.core.audit.AuditOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class AuditPresentationTest {
    private fun entry(outcome: AuditOutcome, ts: Long = 1L, tool: String? = "sms_send", tokenName: String? = "Claude") = AuditEntry(
        timestamp = ts, tokenId = "abcdefghijkl", tokenName = tokenName, method = "tools/call", tool = tool,
        outcome = outcome, transport = "relay",
    )

    @Test
    fun everyOutcomeHasCategoryAndLabel() {
        AuditOutcome.entries.forEach {
            AuditPresentation.category(it)
            assertTrue(AuditPresentation.label(it).isNotBlank())
        }
    }

    @Test
    fun categories() {
        assertEquals(OutcomeCategory.SUCCESS, AuditPresentation.category(AuditOutcome.OK))
        listOf(AuditOutcome.TOOL_ERROR, AuditOutcome.UNKNOWN_TOOL, AuditOutcome.TIMEOUT, AuditOutcome.EXCEPTION)
            .forEach { assertEquals(it.name, OutcomeCategory.ERROR, AuditPresentation.category(it)) }
        listOf(
            AuditOutcome.DENIED_SCOPE, AuditOutcome.RATE_LIMITED, AuditOutcome.PAUSED,
            AuditOutcome.DENIED_BY_USER, AuditOutcome.CONFIRM_TIMEOUT, AuditOutcome.AUTH_FAILED,
        ).forEach { assertEquals(it.name, OutcomeCategory.DENIED, AuditPresentation.category(it)) }
    }

    @Test
    fun filtersAndCounts() {
        val entries = listOf(
            entry(AuditOutcome.OK), entry(AuditOutcome.TOOL_ERROR), entry(AuditOutcome.DENIED_BY_USER),
            entry(AuditOutcome.AUTH_FAILED), entry(AuditOutcome.OK),
        )
        assertEquals(5, AuditPresentation.filter(entries, AuditFilter.ALL).size)
        assertEquals(listOf(AuditOutcome.TOOL_ERROR), AuditPresentation.filter(entries, AuditFilter.ERRORS).map { it.outcome })
        assertEquals(
            listOf(AuditOutcome.DENIED_BY_USER, AuditOutcome.AUTH_FAILED),
            AuditPresentation.filter(entries, AuditFilter.DENIED).map { it.outcome },
        )
        assertEquals(listOf(AuditOutcome.OK, AuditOutcome.OK), AuditPresentation.filter(entries, AuditFilter.OK).map { it.outcome })
        assertEquals(
            mapOf(AuditFilter.ALL to 5, AuditFilter.OK to 2, AuditFilter.ERRORS to 1, AuditFilter.DENIED to 2),
            AuditPresentation.counts(entries),
        )
    }

    @Test
    fun titlesAndTokenLabels() {
        assertEquals("sms_send", AuditPresentation.title(entry(AuditOutcome.OK)))
        assertEquals("tools/call", AuditPresentation.title(entry(AuditOutcome.OK, tool = null)))
        assertEquals("Claude", AuditPresentation.tokenLabel(entry(AuditOutcome.OK)))
        assertEquals("Token abcdefghijkl", AuditPresentation.tokenLabel(entry(AuditOutcome.OK, tokenName = null)))
        val anon = AuditEntry(1, null, null, "initialize", outcome = AuditOutcome.AUTH_FAILED, transport = "local")
        assertEquals("Unknown caller", AuditPresentation.tokenLabel(anon))
        assertEquals("Relay", AuditPresentation.transportLabel("relay"))
        assertEquals("Local", AuditPresentation.transportLabel("local"))
        assertEquals("bluetooth", AuditPresentation.transportLabel("bluetooth"))
    }

    @Test
    fun stableKeysAreUniqueAndSurvivePrepend() {
        val old = listOf(entry(AuditOutcome.OK, ts = 5), entry(AuditOutcome.OK, ts = 5), entry(AuditOutcome.OK, ts = 4))
        val oldKeys = AuditPresentation.stableKeys(old)
        assertEquals(3, oldKeys.toSet().size)
        val newer = listOf(entry(AuditOutcome.OK, ts = 5)) + old
        val newKeys = AuditPresentation.stableKeys(newer)
        assertEquals(4, newKeys.toSet().size)
        assertEquals(oldKeys, newKeys.drop(1))
    }

    @Test
    fun argumentsReadAsRows() {
        val zone = ZoneId.of("America/New_York")
        val rows = AuditPresentation.argumentRows(
            """{"title":"Lunch","start":"2026-11-23T13:00-06:00","end":"2026-11-23T15:30","day":"2026-11-24","duration_minutes":60,""" +
                """"all_day":false,"to":["a","b"],"x":null}""",
            zone,
            formatDateTime = { it.toLocalDateTime().toString() },
            formatDate = { "day $it" },
        )
        assertEquals(
            listOf(
                ArgumentRow("Title", "Lunch"),
                // The offset is converted to the phone's zone.
                ArgumentRow("Start", "2026-11-23T14:00"),
                ArgumentRow("End", "2026-11-23T15:30"),
                ArgumentRow("Day", "day 2026-11-24"),
                ArgumentRow("Duration", "60 min"),
                ArgumentRow("All day", "No"),
                ArgumentRow("To", """["a","b"]"""),
                ArgumentRow("X", "–"),
            ),
            rows,
        )
        // Cut at 300 characters, not an object, or empty: shown as raw JSON instead.
        val none = { preview: String? -> AuditPresentation.argumentRows(preview, zone, { "" }, { "" }) }
        assertNull(none("""{"title":"Lun"""))
        assertNull(none("[1]"))
        assertNull(none("{}"))
        assertNull(none(null))
    }
}
