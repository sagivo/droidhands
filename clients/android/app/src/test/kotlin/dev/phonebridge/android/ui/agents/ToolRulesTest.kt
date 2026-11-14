package dev.phonebridge.android.ui.agents

import dev.phonebridge.core.auth.ConfirmPolicy
import dev.phonebridge.core.auth.ToolRule
import dev.phonebridge.core.tools.Risk
import dev.phonebridge.core.tools.Scopes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRulesTest {
    private val tools = listOf(
        ToolInfo("bridge_capabilities", "Capabilities", "", Risk.READ),
        ToolInfo("sms_send", "Send SMS", Scopes.MESSAGES_SEND, Risk.SENSITIVE),
        ToolInfo("whatsapp_send", "Send WhatsApp", Scopes.MESSAGES_SEND, Risk.SENSITIVE),
        ToolInfo("device_status", "Device status", Scopes.DEVICE, Risk.READ),
        ToolInfo("ui_tap", "Tap", Scopes.SCREEN_CONTROL, Risk.ACTION),
        ToolInfo("contacts_search", "Search contacts", Scopes.CONTACTS_READ, Risk.READ),
    )

    @Test
    fun groupsFollowScopesInCatalogOrder() {
        val groups = ToolRules.groups(tools, setOf(Scopes.MESSAGES_SEND, Scopes.DEVICE, Scopes.CONTACTS_READ))
        assertEquals(listOf(Scopes.DEVICE, Scopes.CONTACTS_READ, Scopes.MESSAGES_SEND), groups.map { it.scope })
        assertEquals(listOf("sms_send", "whatsapp_send"), groups.last().tools.map { it.name })
        assertTrue(groups.none { g -> g.tools.any { it.name == "bridge_capabilities" || it.name == "ui_tap" } })
        assertEquals(emptyList<ToolGroup>(), ToolRules.groups(tools, emptySet()))
    }

    @Test
    fun toolsAboveTheRiskCapAreNotOffered() {
        val all = setOf(Scopes.MESSAGES_SEND, Scopes.DEVICE, Scopes.SCREEN_CONTROL)
        assertEquals(listOf("device_status"), ToolRules.groups(tools, all, Risk.READ).flatMap { g -> g.tools.map { it.name } })
        assertEquals(listOf("device_status", "ui_tap"), ToolRules.groups(tools, all, Risk.ACTION).flatMap { g -> g.tools.map { it.name } })
        assertEquals(4, ToolRules.groups(tools, all, null).sumOf { it.tools.size })
    }

    @Test
    fun choicesMapToRules() {
        RuleChoice.entries.forEach { assertEquals(it, RuleChoice.of(it.rule)) }
        assertEquals(RuleChoice.DEFAULT, RuleChoice.of(null))
        val rules = ToolRules.withChoice(emptyMap(), "sms_send", RuleChoice.BLOCK)
        assertEquals(mapOf("sms_send" to ToolRule.BLOCK), rules)
        assertEquals(emptyMap<String, ToolRule>(), ToolRules.withChoice(rules, "sms_send", RuleChoice.DEFAULT))
        assertEquals(mapOf("sms_send" to ToolRule.ASK), ToolRules.withChoice(rules, "sms_send", RuleChoice.ASK))
    }

    @Test
    fun defaultFollowsThePolicy() {
        assertFalse(ToolRules.policyAsks(Risk.READ, ConfirmPolicy.ALL_ACTIONS))
        assertFalse(ToolRules.policyAsks(Risk.ACTION, ConfirmPolicy.SENSITIVE))
        assertTrue(ToolRules.policyAsks(Risk.ACTION, ConfirmPolicy.ALL_ACTIONS))
        assertTrue(ToolRules.policyAsks(Risk.SENSITIVE, ConfirmPolicy.SENSITIVE))
        assertFalse(ToolRules.policyAsks(Risk.SENSITIVE, ConfirmPolicy.NEVER))
        assertEquals("Default: asks you first", ToolRules.defaultMeaning(Risk.SENSITIVE, ConfirmPolicy.SENSITIVE))
        assertEquals("Default: runs without asking", ToolRules.defaultMeaning(Risk.READ, ConfirmPolicy.SENSITIVE))
        assertEquals("Hidden from the agent and refused", ToolRules.choiceMeaning(RuleChoice.BLOCK, Risk.READ, ConfirmPolicy.NEVER))
    }

    @Test
    fun summaries() {
        assertEquals("No rules: the approval setting decides", ToolRules.summary(emptyMap()))
        val rules = mapOf("a" to ToolRule.BLOCK, "b" to ToolRule.BLOCK, "c" to ToolRule.ASK, "d" to ToolRule.ALLOW, "e" to ToolRule.ALLOW)
        assertEquals("2 blocked · 1 always asks · 2 never ask", ToolRules.summary(rules))
        assertEquals("1 blocked", ToolRules.summary(rules, visibleTools = setOf("a", "zzz")))
        assertEquals("No rules: the approval setting decides", ToolRules.summary(rules, visibleTools = emptySet()))
    }

    @Test
    fun appActionRunOffersNoAllowBecauseItCannotSkipApprovals() {
        val run = ToolInfo("app_action_run", "Run an app action", Scopes.APPS, Risk.ACTION)
        assertEquals(listOf(RuleChoice.DEFAULT, RuleChoice.ASK, RuleChoice.BLOCK), ToolRules.choices(run, RuleChoice.DEFAULT))
        // An Allow rule set earlier stays visible so it can be changed.
        assertEquals(RuleChoice.entries, ToolRules.choices(run, RuleChoice.ALLOW))
        assertEquals(RuleChoice.entries, ToolRules.choices(tools[1], RuleChoice.DEFAULT))
        assertEquals("Asks you first before every app action", ToolRules.meaning(RuleChoice.ASK, run, ConfirmPolicy.NEVER))
        assertEquals("Each app action's own rule and risk decide", ToolRules.meaning(RuleChoice.ALLOW, run, ConfirmPolicy.NEVER))
        assertEquals("Always asks you first", ToolRules.meaning(RuleChoice.ASK, tools[1], ConfirmPolicy.NEVER))
    }
}
