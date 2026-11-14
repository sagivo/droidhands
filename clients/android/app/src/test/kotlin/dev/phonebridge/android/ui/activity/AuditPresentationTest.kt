package dev.phonebridge.android.ui.activity

import dev.phonebridge.core.audit.AuditEntry
import dev.phonebridge.core.audit.AuditOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
