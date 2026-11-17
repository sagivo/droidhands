package com.droidhands.android.ui.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AgentAppsTest {
    private val url = "https://relay.example.com/d/mmg42klgyqzwneiskrelxms3j4/mcp"

    private fun app(client: AgentClient) = AgentApps.KNOWN.single { it.client == client }

    private fun tap(client: AgentClient) = AgentApps.oneTap(app(client), ClientInstructions.guide(client, url))

    @Test
    fun installedKeepsTheListOrder() {
        val here = setOf("ai.x.grok", "com.facebook.aura", "com.anthropic.claude", "com.example.notes")
        assertEquals(
            listOf(AgentClient.CLAUDE, AgentClient.MUSE, AgentClient.GROK),
            AgentApps.installed { it in here }.map { it.client },
        )
        assertTrue(AgentApps.installed { false }.isEmpty())
    }

    @Test
    fun everyAgentAppHasOneTapAndOnePackage() {
        AgentApps.KNOWN.forEach { app -> assertNotNull(app.client.name, tap(app.client)) }
        assertEquals(AgentApps.KNOWN.size, AgentApps.KNOWN.map { it.packageName }.toSet().size)
        assertEquals(AgentApps.KNOWN.size, AgentApps.KNOWN.map { it.client }.toSet().size)
    }

    @Test
    fun chatAgentsGetTheMessageInTheirOwnApp() {
        listOf(AgentClient.MUSE to "com.facebook.aura", AgentClient.GROK_BOT to "ai.x.grok.bot").forEach { (client, packageName) ->
            assertEquals(OneTap.Send(packageName, ClientInstructions.agentMessage(url)), tap(client))
            assertEquals("Opens ${client.label} with a message to send", AgentApps.summary(app(client), tap(client)!!))
        }
    }

    @Test
    fun linkAgentsOpenTheirSetupLink() {
        val claude = tap(AgentClient.CLAUDE) as OneTap.Open
        assertEquals(ClientInstructions.claudeAddLink(url), claude.link.url)
        assertFalse(claude.link.inBrowser)
        assertEquals("Opens Claude with DroidHands filled in", AgentApps.summary(app(AgentClient.CLAUDE), claude))

        val chatGpt = tap(AgentClient.CHATGPT) as OneTap.Open
        assertTrue(chatGpt.link.copyUrlFirst && chatGpt.link.inBrowser)
        assertEquals("Copies your URL and opens chatgpt.com", AgentApps.summary(app(AgentClient.CHATGPT), chatGpt))
        assertEquals("Copies your URL and opens grok.com", AgentApps.summary(app(AgentClient.GROK), tap(AgentClient.GROK)!!))
    }

    @Test
    fun guidesWithoutALinkOrMessageHaveNoOneTap() {
        val guide = ClientInstructions.guide(AgentClient.CLAUDE_CODE, url)
        assertEquals(null, AgentApps.oneTap(AgentApp("com.example.cli", AgentClient.CLAUDE_CODE), guide))
    }

    @Test
    fun theManifestCanSeeEveryAgentApp() {
        // Unit tests run in the module directory.
        val manifest = File("src/main/AndroidManifest.xml").readText()
        AgentApps.KNOWN.forEach { app ->
            assertTrue(app.packageName, manifest.contains("<package android:name=\"${app.packageName}\" />"))
        }
    }
}
