package com.droidhands.android.ui.connect

import com.droidhands.core.auth.TokenRecord
import com.droidhands.core.tools.Scopes
import com.droidhands.core.transport.PairingRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ConnectLogicTest {
    private val url = "https://relay.example.com/d/mmg42klgyqzwneiskrelxms3j4/mcp"

    private fun token(id: String, origin: String = "pairing", revoked: Boolean = false) =
        TokenRecord(id = id, name = "Claude", secretHash = "00", scopes = Scopes.PRESET_ASSISTANT, createdAt = 0, origin = origin, revoked = revoked)

    private fun pairing(name: String = "Claude", host: String = "claude.ai", expiresAt: Long = 0, viaAgent: Boolean = false) =
        PairingRequest("p_1", name, host, "K7Q2", null, null, expiresAt, viaAgent)

    @Test
    fun oneMessageForEveryAgent() {
        val message = AgentPrompt.signIn(url)
        assertEquals(
            "Add a new connector named DroidHands with the URL $url and follow the instructions at " +
                "https://droidhands.com/connect.md. No password or token is needed: I'll tap Allow in the DroidHands app on my phone.",
            message,
        )
        assertTrue("one line", '\n' !in message)
    }

    @Test
    fun tokenMessageCarriesTheTokenAndAnyExtraStep() {
        val message = AgentPrompt.withToken(url, "pb1.abc", extraStep = "Run adb forward first.")
        assertTrue(message.startsWith("Add a new connector named DroidHands with the URL $url and follow the instructions at "))
        assertTrue(message.endsWith("Use this token as the bearer token: pb1.abc Run adb forward first."))
        assertFalse(message.contains("No password"))
        assertTrue(AgentPrompt.withToken(url, "pb1.abc").endsWith("bearer token: pb1.abc"))
    }

    @Test
    fun theInstructionsTheMessageLinksToShipWithTheWebsite() {
        // Unit tests run in the module directory; the website serves website/public at its root.
        val page = File("../../../website/public/connect.md")
        assertTrue(page.path, page.isFile)
        assertTrue(AgentPrompt.INSTRUCTIONS_URL.endsWith("/" + page.name))
        assertTrue(page.readText().contains("named DroidHands"))
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
        assertEquals("Instinct wants to connect", PairingWatch.title(pairing(name = "Instinct", host = "agent sign-in", viaAgent = true)))
        assertTrue(PairingWatch.cardHint(pairing()).endsWith("your browser shows the same code."))
        assertTrue(PairingWatch.cardHint(pairing(viaAgent = true)).endsWith("your agent showed you the same code."))
        assertEquals("2:31 left", PairingWatch.timeLeft(expiresAt = 151_000, now = 0))
        assertEquals("0:01 left", PairingWatch.timeLeft(expiresAt = 500, now = 0))
        assertEquals("Expired", PairingWatch.timeLeft(expiresAt = 0, now = 10))
        assertEquals("K 7 Q 2", PairingWatch.spokenCode("K7Q2"))
    }
}
