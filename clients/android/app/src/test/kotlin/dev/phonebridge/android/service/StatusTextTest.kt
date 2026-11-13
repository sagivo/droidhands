package dev.phonebridge.android.service

import dev.phonebridge.android.graph.LocalServerStatus
import dev.phonebridge.core.audit.AuditEntry
import dev.phonebridge.core.audit.AuditOutcome
import dev.phonebridge.core.transport.RelayStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusTextTest {
    private val now = 1_731_500_000_000L

    private fun entry(
        ageMs: Long,
        tool: String? = "whatsapp_send",
        method: String = "tools/call",
        token: String? = "Claude",
        outcome: AuditOutcome = AuditOutcome.OK,
    ) = AuditEntry(
        timestamp = now - ageMs,
        tokenId = token?.let { "abcdefghijkl" },
        tokenName = token,
        method = method,
        tool = tool,
        outcome = outcome,
        transport = "relay",
    )

    @Test
    fun relativeTime() {
        assertEquals("just now", StatusText.relativeTime(0))
        assertEquals("just now", StatusText.relativeTime(-5_000))
        assertEquals("just now", StatusText.relativeTime(44_999))
        assertEquals("1 min ago", StatusText.relativeTime(45_000))
        assertEquals("1 min ago", StatusText.relativeTime(119_000))
        assertEquals("59 min ago", StatusText.relativeTime(3_599_000))
        assertEquals("1 h ago", StatusText.relativeTime(3_600_000))
        assertEquals("23 h ago", StatusText.relativeTime(86_399_000))
        assertEquals("2 d ago", StatusText.relativeTime(2 * 86_400_000L))
    }

    @Test
    fun relayLines() {
        assertEquals("Relay: stopped", StatusText.relayLine(RelayStatus.Stopped))
        assertEquals("Relay: connecting…", StatusText.relayLine(RelayStatus.Connecting))
        assertEquals("Relay: connected to relay.example.com", StatusText.relayLine(RelayStatus.Connected("https://relay.example.com/mcp")))
        assertEquals("Relay: connected", StatusText.relayLine(RelayStatus.Connected("")))
        assertEquals("Relay: offline (retry in 5 s)", StatusText.relayLine(RelayStatus.Error("offline", 4_200)))
        assertEquals("Relay: offline (retry in 2 min)", StatusText.relayLine(RelayStatus.Error("offline", 90_000)))
        assertEquals("Relay: bad access key (check settings)", StatusText.relayLine(RelayStatus.Error("bad access key", null, fatal = true)))
        assertEquals("Relay: offline", StatusText.relayLine(RelayStatus.Error("offline", null)))
    }

    @Test
    fun localLines() {
        assertEquals("Local: stopped", StatusText.localLine(LocalServerStatus.Stopped))
        assertEquals(
            "Local: 127.0.0.1:8765, 192.168.1.20:8765",
            StatusText.localLine(LocalServerStatus.Running(listOf("http://127.0.0.1:8765/mcp", "http://192.168.1.20:8765/mcp"))),
        )
        assertEquals("Local: Port in use", StatusText.localLine(LocalServerStatus.Error("Port in use")))
    }

    @Test
    fun agentFacingStates() {
        assertEquals("disabled", StatusText.relayState(enabled = false, status = RelayStatus.Stopped))
        assertEquals("stopped", StatusText.relayState(enabled = true, status = RelayStatus.Stopped))
        assertEquals("connected", StatusText.relayState(enabled = true, status = RelayStatus.Connected("https://r/mcp")))
        assertEquals("error: Relay URL: bad (check settings)", StatusText.relayState(true, RelayStatus.Error("Relay URL: bad", null, true)))
        assertEquals("disabled", StatusText.localState(enabled = false, status = LocalServerStatus.Stopped))
        assertEquals(
            "running: http://127.0.0.1:8765/mcp",
            StatusText.localState(enabled = true, status = LocalServerStatus.Running(listOf("http://127.0.0.1:8765/mcp"))),
        )
    }

    @Test
    fun lastNotableEntrySkipsProtocolChatter() {
        val toolCall = entry(ageMs = 60_000)
        val entries = listOf(entry(0, tool = null, method = "tools/list"), entry(1_000, tool = null, method = "initialize"), toolCall)
        assertEquals(toolCall, StatusText.lastNotableEntry(entries))
    }

    @Test
    fun lastNotableEntryKeepsAuthFailuresAndFallsBack() {
        val authFailure = entry(0, tool = null, method = "unknown", token = null, outcome = AuditOutcome.AUTH_FAILED)
        assertEquals(authFailure, StatusText.lastNotableEntry(listOf(authFailure, entry(5_000))))
        val onlyList = entry(0, tool = null, method = "tools/list")
        assertEquals(onlyList, StatusText.lastNotableEntry(listOf(onlyList)))
        assertNull(StatusText.lastNotableEntry(emptyList()))
    }

    @Test
    fun lastActivityLines() {
        assertEquals("Last: whatsapp_send by Claude, 3 min ago", StatusText.lastActivityLine(entry(180_000), now))
        assertEquals(
            "Last: sms_send by Claude (denied by you), just now",
            StatusText.lastActivityLine(entry(1_000, tool = "sms_send", outcome = AuditOutcome.DENIED_BY_USER), now),
        )
        assertEquals(
            "Last: rejected request (invalid token), just now",
            StatusText.lastActivityLine(entry(0, tool = null, token = null, outcome = AuditOutcome.AUTH_FAILED), now),
        )
        assertEquals(
            "Last: tools/list by unknown agent, just now",
            StatusText.lastActivityLine(entry(0, tool = null, method = "tools/list", token = " "), now),
        )
    }

    @Test
    fun activeNotificationListsEnabledTransportsAndLastActivity() {
        val content = StatusText.notification(
            paused = false,
            relay = RelayStatus.Connected("https://relay.example.com/mcp"),
            local = null,
            lastEntry = entry(120_000),
            now = now,
        )
        assertEquals("PhoneBridge active", content.title)
        assertEquals("Relay: connected to relay.example.com", content.text)
        assertEquals("Relay: connected to relay.example.com\nLast: whatsapp_send by Claude, 2 min ago", content.bigText)
        assertFalse(content.paused)
    }

    @Test
    fun pausedNotificationSaysHowToResume() {
        val content = StatusText.notification(
            paused = true,
            relay = RelayStatus.Connecting,
            local = LocalServerStatus.Running(listOf("http://127.0.0.1:8765/mcp")),
            lastEntry = null,
            now = now,
        )
        assertEquals("PhoneBridge paused", content.title)
        assertTrue(content.text.contains("Resume"))
        assertEquals(
            listOf("Agents can't use this phone until you tap Resume.", "Relay: connecting…", "Local: 127.0.0.1:8765"),
            content.bigText.lines(),
        )
        assertTrue(content.paused)
    }

    @Test
    fun notificationWithoutTransports() {
        val content = StatusText.notification(paused = false, relay = null, local = null, lastEntry = null, now = now)
        assertEquals("No connection enabled.", content.text)
        assertEquals("No connection enabled.", content.bigText)
    }

    @Test
    fun everyOutcomeExceptOkHasALabel() {
        AuditOutcome.entries.forEach { outcome ->
            val label = StatusText.outcomeLabel(outcome)
            if (outcome == AuditOutcome.OK) assertNull(label) else assertTrue("$outcome", !label.isNullOrBlank())
        }
    }
}
