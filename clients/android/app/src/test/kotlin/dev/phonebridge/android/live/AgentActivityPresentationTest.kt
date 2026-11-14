// Adapted from T3 Code (MIT, Copyright (c) 2026 T3 Tools Inc.),
// apps/mobile/modules/t3-agent-notifications/android/src/test/java/expo/modules/t3agentnotifications/AgentNotificationsTest.kt
package dev.phonebridge.android.live

import dev.phonebridge.core.audit.AuditOutcome
import dev.phonebridge.core.mcp.ActiveCall
import dev.phonebridge.core.tools.Risk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentActivityPresentationTest {
    private val android15 = 35
    private val android16 = AgentActivityPresentation.PROMOTION_SDK

    private fun call(
        id: Long,
        agent: String = "Claude Code",
        token: String = "t1",
        title: String = "Send WhatsApp message",
        label: String? = null,
        approval: Boolean = false,
    ) = ActiveCall(id, token, agent, "tool_$id", title, Risk.SENSITIVE, startedAt = 0, label = label, awaitingApproval = approval)

    private fun running(vararg calls: ActiveCall, sdk: Int = android16) =
        AgentActivityPresentation.content(ActivitySnapshot.Running(calls.toList()), sdk)

    private fun finished(vararg calls: Pair<ActiveCall, AuditOutcome>, sdk: Int = android16) =
        AgentActivityPresentation.content(ActivitySnapshot.Finished(calls.toList()), sdk, lingerMs = 4_000)

    @Test
    fun aSingleCallSaysWhoAndWhat() {
        val content = running(call(1, label = "Sending WhatsApp to Tania Cohen"))
        assertEquals(ActivityPhase.WORKING, content.phase)
        assertEquals("Claude Code is using your phone", content.title)
        assertEquals("Sending WhatsApp to Tania Cohen", content.text)
        assertEquals("Sending WhatsApp to Tania Cohen", content.bigText)
        assertEquals("Agent", content.chip)
        assertEquals(listOf("t1"), content.stopTokenIds)
        assertTrue(content.ongoing)
    }

    @Test
    fun withoutALineTheToolTitleIsShown() {
        assertEquals("Send WhatsApp message", running(call(1)).text)
        assertEquals("tool_1", running(call(1, title = " ")).text)
    }

    @Test
    fun aBlankAgentNameCannotLeaveTheTitleEmpty() {
        assertEquals("An agent is using your phone", running(call(1, agent = "  ‮ ")).title)
    }

    @Test
    fun agentTextIsCleanedOfControlAndBidiCharacters() {
        val content = running(call(1, agent = "Evil‮agent\n", label = "Sending\u0000 WhatsApp\nto  Tania"))
        assertEquals("Evilagent is using your phone", content.title)
        assertEquals("Sending WhatsApp to Tania", content.text)
    }

    @Test
    fun approvalTakesTheHeadlineAndTheChip() {
        val content = running(call(1, title = "Read the screen"), call(2, label = "Sending WhatsApp to Tania Cohen", approval = true))
        assertEquals(ActivityPhase.APPROVAL, content.phase)
        assertEquals("Claude Code needs your approval", content.title)
        assertEquals("Sending WhatsApp to Tania Cohen (+1 more)", content.text)
        assertEquals("Read the screen\nApproval needed: Sending WhatsApp to Tania Cohen", content.bigText)
        assertEquals("Approve", content.chip)
        assertEquals("An agent needs your approval", content.publicTitle)
    }

    @Test
    fun multipleAgentsShareOneSummary() {
        val content = running(
            call(1, label = "Sending WhatsApp to Tania Cohen"),
            call(2, agent = "Codex", token = "t2", title = "Read the screen"),
            call(3, title = "List notifications"),
        )
        assertEquals("2 agents are using your phone", content.title)
        assertEquals("Claude Code, Codex", content.text)
        assertEquals("Claude Code: Sending WhatsApp to Tania Cohen (+1 more)\nCodex: Read the screen", content.bigText)
        assertEquals("Agents", content.chip)
        assertEquals(listOf("t1", "t2"), content.stopTokenIds)
        assertEquals("Agents are using your phone", content.publicTitle)
    }

    @Test
    fun multipleAgentsCountThoseWaitingForYou() {
        val one = running(call(1, approval = true), call(2, agent = "Codex", token = "t2"))
        assertEquals("1 needs your approval", one.text)
        assertEquals("Approve", one.chip)
        val two = running(call(1, approval = true), call(2, agent = "Codex", token = "t2", approval = true))
        assertEquals("2 need your approval", two.text)
    }

    @Test
    fun longListsAreCut() {
        val calls = (1L..7L).map { call(it, title = "Tool $it") }.toTypedArray()
        val lines = running(*calls).bigText.lines()
        assertEquals(6, lines.size)
        assertEquals("+2 more", lines.last())
    }

    @Test
    fun belowAndroid16ItIsAPlainOngoingNotification() {
        val content = running(call(1), sdk = android15)
        assertTrue(content.ongoing)
        assertFalse(content.requestPromotion)
        assertNull(content.chip)
    }

    @Test
    fun doneIsShortLivedAndHasNoActions() {
        val content = finished(call(1, label = "Sending WhatsApp to Tania Cohen") to AuditOutcome.OK)
        assertEquals(ActivityPhase.DONE, content.phase)
        assertEquals("Claude Code finished", content.title)
        assertEquals("Done: Sending WhatsApp to Tania Cohen", content.text)
        assertEquals("Done", content.chip)
        assertEquals(emptyList<String>(), content.stopTokenIds)
        assertEquals(6_000L, content.timeoutMs)
        assertEquals("An agent finished", content.publicTitle)
    }

    @Test
    fun aFinishedCardStaysOngoingOnlyWhereThatKeepsItPromoted() {
        assertTrue(finished(call(1) to AuditOutcome.OK, sdk = android16).ongoing)
        val old = finished(call(1) to AuditOutcome.OK, sdk = android15)
        assertFalse(old.ongoing)
        assertNull(old.chip)
    }

    @Test
    fun failuresSayWhy() {
        val content = finished(call(1, title = "Send SMS") to AuditOutcome.DENIED_BY_USER)
        assertEquals(ActivityPhase.FAILED, content.phase)
        assertEquals("Claude Code couldn't finish", content.title)
        assertEquals("Failed: Send SMS (denied by you)", content.text)
        assertEquals("Failed", content.chip)
        assertEquals("Failed: Send SMS", finished(call(1, title = "Send SMS") to AuditOutcome.TOOL_ERROR).text)
    }

    @Test
    fun stoppedCallsAreNotFailures() {
        val content = finished(call(1) to AuditOutcome.OK, call(2, title = "Place a phone call") to AuditOutcome.CANCELLED)
        assertEquals(ActivityPhase.STOPPED, content.phase)
        assertEquals("Claude Code was stopped", content.title)
        assertEquals("2 actions", content.text)
        assertEquals("Done: Send WhatsApp message\nStopped: Place a phone call", content.bigText)
        assertEquals("Stopped", content.chip)
    }

    @Test
    fun severalAgentsFinishTogether() {
        val content = finished(call(1) to AuditOutcome.OK, call(2, agent = "Codex", token = "t2", title = "Send SMS") to AuditOutcome.TIMEOUT)
        assertEquals("Agents finished, 1 failed", content.title)
        assertEquals("Claude Code, Codex", content.text)
        assertEquals("Claude Code: Done: Send WhatsApp message\nCodex: Failed: Send SMS (timed out)", content.bigText)
    }
}
