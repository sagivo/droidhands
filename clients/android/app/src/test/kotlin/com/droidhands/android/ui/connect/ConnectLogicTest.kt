package com.droidhands.android.ui.connect

import com.droidhands.core.auth.TokenRecord
import com.droidhands.core.tools.Scopes
import com.droidhands.core.transport.PairingRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun everyGuideIsShortWithOneMainAction() {
        AgentClient.entries.forEach { client ->
            val guide = ClientInstructions.guide(client, url)
            assertTrue(client.name, guide.steps.size in 1..2)
            assertTrue(client.name, guide.appliesTo.isNotBlank())
            // One main action at most: a link that does the setup, or a command to copy.
            assertFalse(client.name, guide.open != null && guide.command != null)
        }
    }

    @Test
    fun claudeOpensItsPrefilledAddConnectorDialog() {
        val open = ClientInstructions.guide(AgentClient.CLAUDE, url).open!!
        assertEquals(
            "https://claude.ai/customize/connectors?modal=add-custom-connector&connectorName=DroidHands" +
                "&connectorUrl=https%3A%2F%2Frelay.example.com%2Fd%2Fmmg42klgyqzwneiskrelxms3j4%2Fmcp",
            open.url,
        )
        assertFalse(open.copyUrlFirst)
        assertTrue(ClientInstructions.claudeAddLink("https://h/a b?c=d&e").endsWith("connectorUrl=https%3A%2F%2Fh%2Fa%20b%3Fc%3Dd%26e"))
    }

    @Test
    fun chatGptCopiesTheUrlBeforeOpeningItsSettings() {
        val guide = ClientInstructions.guide(AgentClient.CHATGPT, url)
        assertTrue(guide.open!!.copyUrlFirst)
        assertTrue(guide.steps.first().contains("Developer mode"))
        assertFalse(guide.onComputer)
    }

    @Test
    fun computerClientsGetACopyableCommandWithoutAToken() {
        val guide = ClientInstructions.guide(AgentClient.CLAUDE_CODE, url)
        assertEquals("claude mcp add --transport http phone $url", guide.command)
        assertTrue(guide.steps.first().contains("/mcp"))
        assertTrue(guide.onComputer)
        assertEquals("claude mcp add --transport http phone 'https://h/a b'", ClientInstructions.claudeCodeCommand("https://h/a b"))
        assertEquals("codex mcp add phone --url $url", ClientInstructions.guide(AgentClient.CODEX, url).command)
        assertEquals("gemini mcp add --transport http -s user phone $url", ClientInstructions.guide(AgentClient.GEMINI_CLI, url).command)
        assertNull(ClientInstructions.guide(AgentClient.CLAUDE, url).command)
        assertTrue(ClientInstructions.shareCommand(guide).endsWith(guide.command!!))
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
