package dev.phonebridge.android.ui.connect

import dev.phonebridge.core.auth.TokenRecord
import dev.phonebridge.core.tools.Scopes
import dev.phonebridge.core.transport.PairingRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectLogicTest {
    private val url = "https://relay.example.com/d/mmg42klgyqzwneiskrelxms3j4/mcp"

    private fun token(id: String, origin: String = "pairing", revoked: Boolean = false) =
        TokenRecord(id = id, name = "Claude", secretHash = "00", scopes = Scopes.PRESET_ASSISTANT, createdAt = 0, origin = origin, revoked = revoked)

    private fun pairing(name: String = "Claude", host: String = "claude.ai", expiresAt: Long = 0) =
        PairingRequest("p_1", name, host, "K7Q2", null, null, expiresAt)

    @Test
    fun everyGuideEndsWithApprovingOnThePhone() {
        AgentClient.entries.forEach { client ->
            val guide = ClientInstructions.guide(client, url)
            val last = (guide.steps + guide.afterCommand).last()
            assertEquals(client.name, ClientInstructions.APPROVE_STEP, last)
            assertTrue(guide.appliesTo.isNotBlank())
        }
    }

    @Test
    fun claudeCodeGetsACopyableCommandWithoutAToken() {
        val guide = ClientInstructions.guide(AgentClient.CLAUDE_CODE, url)
        assertEquals("claude mcp add --transport http phone $url", guide.command)
        assertTrue(guide.afterCommand.first().contains("/mcp"))
        assertEquals("claude mcp add --transport http phone 'https://h/a b'", ClientInstructions.claudeCodeCommand("https://h/a b"))
        assertNull(ClientInstructions.guide(AgentClient.CLAUDE, url).command)
    }

    @Test
    fun guidesNameTheRightSettings() {
        assertTrue(ClientInstructions.guide(AgentClient.CLAUDE, url).steps.first().contains("Connectors → Add custom connector"))
        assertTrue(ClientInstructions.guide(AgentClient.CHATGPT, url).steps.first().contains("Developer mode"))
        assertTrue(ClientInstructions.shareText(url).endsWith(url))
    }

    @Test
    fun newlyPairedIgnoresBaselineManualAndRevoked() {
        val baseline = setOf("old")
        assertNull(PairingWatch.newlyPaired(listOf(token("old")), baseline))
        assertNull(PairingWatch.newlyPaired(listOf(token("manual", origin = "manual")), baseline))
        assertNull(PairingWatch.newlyPaired(listOf(token("rev", revoked = true)), baseline))
        assertEquals("new", PairingWatch.newlyPaired(listOf(token("new"), token("old")), baseline)?.id)
    }

    @Test
    fun pairingTexts() {
        assertEquals("Claude (claude.ai) wants to connect", PairingWatch.title(pairing()))
        assertEquals("claude.ai wants to connect", PairingWatch.title(pairing(name = "claude.ai")))
        assertEquals("An AI agent (localhost:33418) wants to connect", PairingWatch.title(pairing(name = " ", host = "localhost:33418")))
        assertEquals("Cursor wants to connect", PairingWatch.title(pairing(name = "Cursor", host = "")))
        assertEquals("2:31 left", PairingWatch.timeLeft(expiresAt = 151_000, now = 0))
        assertEquals("0:01 left", PairingWatch.timeLeft(expiresAt = 500, now = 0))
        assertEquals("Expired", PairingWatch.timeLeft(expiresAt = 0, now = 10))
        assertEquals("K 7 Q 2", PairingWatch.spokenCode("K7Q2"))
    }
}
