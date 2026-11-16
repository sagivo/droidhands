package com.droidhands.android.ui.agents

import com.droidhands.android.ui.apps.AppTestData.action
import com.droidhands.android.ui.apps.AppTestData.listing
import com.droidhands.core.auth.AppAccessMode
import com.droidhands.core.auth.AppPolicy
import com.droidhands.core.auth.ConfirmPolicy
import com.droidhands.core.auth.ToolRule
import com.droidhands.core.plugins.Integration
import com.droidhands.core.tools.AppAccess
import com.droidhands.core.tools.Risk
import com.droidhands.core.tools.Scopes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionRulesTest {
    private val spotify = listing(
        "com.spotify.music", "Spotify", Integration.PLUGIN,
        action("com.spotify.music", "play", "Play music"),
        action("com.spotify.music", "search", "Search"),
        action("com.spotify.music", "open", "Open the app"),
    )
    private val whatsapp = listing(
        "com.whatsapp", "WhatsApp", Integration.PLUGIN,
        action("com.whatsapp", "send_message", "Send a message", risk = Risk.SENSITIVE, scope = Scopes.MESSAGES_SEND, builtInTool = "whatsapp_send"),
        action("com.whatsapp", "open", "Open the app"),
    )
    private val bank = listing("com.example.bank", "Bank", Integration.BASIC, action("com.example.bank", "open", "Open the app"))
    private val notes = listing("com.example.notes", "Notes", Integration.LOCAL, action("com.example.notes", "new_note", "New note", unreviewed = true))
    private val apps = listOf(bank, notes, spotify, whatsapp)
    private val all = AppAccess.of(AppPolicy(), emptySet())
    private val assistant = setOf(Scopes.APPS, Scopes.MESSAGES_SEND)

    @Test
    fun appActionsNeedTheAppsScopeAndRunnerAndACapAboveRead() {
        assertTrue(ActionRules.canRunActions(setOf(Scopes.APPS), null, emptyMap()))
        assertTrue(ActionRules.canRunActions(setOf(Scopes.APPS), Risk.ACTION, emptyMap()))
        assertFalse(ActionRules.canRunActions(setOf(Scopes.DEVICE), null, emptyMap()))
        assertFalse(ActionRules.canRunActions(setOf(Scopes.APPS), Risk.READ, emptyMap()))
        assertFalse(ActionRules.canRunActions(setOf(Scopes.APPS), null, mapOf("app_action_run" to ToolRule.BLOCK)))
        assertEquals(emptyList<ActionRuleGroup>(), ActionRules.groups(apps, setOf(Scopes.APPS), Risk.READ, emptyMap(), all))
    }

    @Test
    fun groupedByAppWithinScopesCapAndAppAccess() {
        val groups = ActionRules.groups(apps, assistant, null, mapOf("com.spotify.music/play" to ToolRule.ALLOW), all)
        assertEquals(listOf("Bank", "Notes", "Spotify", "WhatsApp"), groups.map { it.label })
        assertEquals(1, groups.first { it.label == "Spotify" }.ruleCount)
        assertEquals("3 actions · 1 rule", ActionRules.groupSubtitle(groups.first { it.label == "Spotify" }))
        assertEquals("1 action", ActionRules.groupSubtitle(groups.first { it.label == "Bank" }))

        // Without the scope (or above the cap) the WhatsApp send action isn't offered.
        val appsOnly = ActionRules.groups(apps, setOf(Scopes.APPS), null, emptyMap(), all)
        assertEquals(listOf("open"), appsOnly.first { it.label == "WhatsApp" }.actions.map { it.id })
        val capped = ActionRules.groups(apps, assistant, Risk.ACTION, emptyMap(), all)
        assertEquals(listOf("open"), capped.first { it.label == "WhatsApp" }.actions.map { it.id })

        // The never-allow list and the agent's own app access hide whole apps.
        val neverBank = ActionRules.groups(apps, assistant, null, emptyMap(), AppAccess.of(AppPolicy(), setOf("com.example.bank")))
        assertFalse(neverBank.any { it.label == "Bank" })
        val onlySpotify = ActionRules.groups(apps, assistant, null, emptyMap(), AppAccess.of(AppPolicy(AppAccessMode.ALLOWLIST, setOf("com.spotify.music")), emptySet()))
        assertEquals(listOf("Spotify"), onlySpotify.map { it.label })
        assertEquals(setOf("com.spotify.music/play", "com.spotify.music/search", "com.spotify.music/open"), ActionRules.names(onlySpotify))
    }

    @Test
    fun searchFindsAppsAndActions() {
        fun search(q: String) = ActionRules.groups(apps, assistant, null, emptyMap(), all, q).map { g -> g.label to g.actions.map { it.id } }
        assertEquals(listOf("Spotify" to listOf("play", "search", "open")), search("spotify"))
        assertEquals(listOf("Spotify" to listOf("play")), search("play mus"))
        assertEquals(listOf("WhatsApp" to listOf("send_message")), search("send_message"))
        assertEquals(listOf("Bank", "Spotify", "WhatsApp"), ActionRules.groups(apps, assistant, null, emptyMap(), all, "open").map { it.label })
        assertEquals(emptyList<Pair<String, List<String>>>(), search("nothing"))
    }

    @Test
    fun choicesAndMeaningsFollowHowTheActionRuns() {
        val play = spotify.actions.first()
        val send = whatsapp.actions.first()
        val local = notes.actions.first()
        assertEquals(RuleChoice.entries, ActionRules.choices(play))
        // A tool recipe is approved by the built-in tool's rule: only Default and Block mean something.
        assertEquals(listOf(RuleChoice.DEFAULT, RuleChoice.BLOCK), ActionRules.choices(send))
        assertEquals("Follows the whatsapp_send rule", ActionRules.meaning(RuleChoice.DEFAULT, send, ConfirmPolicy.SENSITIVE))
        assertEquals("Hidden from the agent and refused", ActionRules.meaning(RuleChoice.BLOCK, send, ConfirmPolicy.SENSITIVE))
        // Local plugin actions always ask, whatever the rule.
        assertEquals("Always asks you first (unreviewed plugin)", ActionRules.meaning(RuleChoice.ALLOW, local, ConfirmPolicy.NEVER))
        assertEquals("Default: runs without asking", ActionRules.meaning(RuleChoice.DEFAULT, play, ConfirmPolicy.SENSITIVE))
        assertEquals("Default: asks you first", ActionRules.meaning(RuleChoice.DEFAULT, play, ConfirmPolicy.ALL_ACTIONS))
        assertEquals("Never asks (still rate-limited)", ActionRules.meaning(RuleChoice.ALLOW, play, ConfirmPolicy.SENSITIVE))
    }

    @Test
    fun rulesOnActionsLiveInTheSameMapAsToolRules() {
        val draft = AgentDraft(name = "Claude")
            .withRule("com.spotify.music/play", RuleChoice.BLOCK)
            .withRule("sms_send", RuleChoice.ASK)
        assertEquals(mapOf("com.spotify.music/play" to ToolRule.BLOCK, "sms_send" to ToolRule.ASK), draft.toolRules)
        val groups = ActionRules.groups(apps, assistant, null, draft.toolRules, all)
        assertEquals("1 blocked", ToolRules.summary(draft.toolRules, ActionRules.names(groups)))
        assertEquals(emptyMap<String, ToolRule>(), draft.withRule("com.spotify.music/play", RuleChoice.DEFAULT).withRule("sms_send", RuleChoice.DEFAULT).toolRules)
    }
}
