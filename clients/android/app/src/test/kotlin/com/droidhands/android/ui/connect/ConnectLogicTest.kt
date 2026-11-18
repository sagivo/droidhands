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
    fun theListIsTheAgentsPeopleUseThenOther() {
        assertEquals(
            listOf("Claude", "ChatGPT", "Muse", "Grok", "Grok Bot", "Other agent"),
            AgentClient.entries.map { it.label },
        )
        // Every agent but Other has its own app; each app once.
        assertEquals(listOf(AgentClient.OTHER), AgentClient.entries.filter { it.packageName == null })
        val packages = AgentClient.entries.mapNotNull { it.packageName }
        assertEquals(packages.size, packages.toSet().size)
    }

    @Test
    fun everyGuideIsOneActionAndAtMostTwoSteps() {
        AgentClient.entries.forEach { client ->
            val guide = ClientInstructions.guide(client, url)
            assertTrue(client.name, guide.steps.size in 1..2)
            assertEquals(client.name, 1, listOfNotNull(guide.open, guide.message).size)
        }
    }

    @Test
    fun chatAgentsAndOtherGetTheSameMessage() {
        val message = ClientInstructions.agentMessage(url)
        // Agents that add connectors from chat add the URL; the others open it for sign-in instructions.
        assertTrue(message.contains("add $url as a custom connector") && message.contains("it shows how to sign in yourself"))
        // Clients that need a client ID typed in (Muse) get the relay's public one.
        assertTrue(message.contains("client ID, use droidhands"))
        assertTrue("one line", '\n' !in message)
        listOf(AgentClient.MUSE, AgentClient.GROK_BOT, AgentClient.OTHER).forEach { client ->
            assertEquals(client.name, message, ClientInstructions.guide(client, url).message)
        }
        val muse = ClientInstructions.guide(AgentClient.MUSE, url)
        assertEquals("droidhands", muse.clientId)
        assertTrue(muse.steps.any { it.contains("Client ID, paste it (droidhands") })
        assertTrue(ClientInstructions.guide(AgentClient.GROK_BOT, url).steps.first().contains("Authorize"))
        assertNull(ClientInstructions.guide(AgentClient.GROK_BOT, url).clientId)
    }

    @Test
    fun grokCopiesTheUrlAndOpensItsConnectorsInTheBrowser() {
        val guide = ClientInstructions.guide(AgentClient.GROK, url)
        assertEquals(ClientInstructions.GROK_CONNECTORS, guide.open!!.url)
        assertTrue(guide.open!!.copyUrlFirst)
        assertTrue(guide.open!!.inBrowser)
        assertTrue(guide.steps.first().contains("Custom"))
        assertTrue(guide.steps.first().contains("paid"))
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
        // The Claude app opens it when it can; the card offers the browser if it didn't.
        assertFalse(open.inBrowser)
        assertTrue(ClientInstructions.claudeAddLink("https://h/a b?c=d&e").endsWith("connectorUrl=https%3A%2F%2Fh%2Fa%20b%3Fc%3Dd%26e"))
    }

    @Test
    fun chatGptCopiesTheUrlBeforeOpeningItsSettings() {
        val guide = ClientInstructions.guide(AgentClient.CHATGPT, url)
        assertTrue(guide.open!!.copyUrlFirst)
        // Custom MCP servers are added on chatgpt.com only, not in the ChatGPT app.
        assertTrue(guide.open!!.inBrowser)
        assertEquals("https://chatgpt.com/plugins", guide.open!!.url)
        assertTrue(guide.steps.first().contains("Add custom MCP server"))
    }

    @Test
    fun theManifestCanSeeEveryAgentApp() {
        // Unit tests run in the module directory.
        val manifest = File("src/main/AndroidManifest.xml").readText()
        AgentClient.entries.mapNotNull { it.packageName }.forEach { packageName ->
            assertTrue(packageName, manifest.contains("<package android:name=\"$packageName\" />"))
        }
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
