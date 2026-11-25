package com.droidhands.android.ui.grant

import com.droidhands.android.ui.apps.AppListing
import com.droidhands.core.auth.Ability
import com.droidhands.core.auth.AppAccessMode
import com.droidhands.core.auth.AppPolicy
import com.droidhands.core.auth.ConfirmPolicy
import com.droidhands.core.auth.TokenRecord
import com.droidhands.core.plugins.Integration
import com.droidhands.core.tools.Risk
import com.droidhands.core.tools.Scopes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GrantPresentationTest {
    private val labels = mapOf("com.whatsapp" to "WhatsApp", "com.google.android.gm" to "Gmail", "com.spotify.music" to "Spotify")

    private fun record(
        scopes: Set<String> = Scopes.ALL,
        apps: AppPolicy = AppPolicy(),
        policy: ConfirmPolicy = ConfirmPolicy.NEVER,
        maxRisk: Risk? = null,
    ) = TokenRecord(id = "abcdefghijkl", name = "Claude", secretHash = "00", scopes = scopes, confirmPolicy = policy, createdAt = 1, appPolicy = apps, maxRisk = maxRisk)

    @Test
    fun screenTexts() {
        assertEquals("Give Claude access", GrantPresentation.title(" Claude "))
        assertEquals("Give this agent access", GrantPresentation.title(""))
        assertTrue(GrantPresentation.everythingDescription(phoneUse = true).contains("without asking you first"))
        assertTrue(GrantPresentation.everythingDescription(phoneUse = true).contains("the screen"))
        assertTrue(GrantPresentation.everythingDescription(phoneUse = false).contains("without asking you first"))
        assertFalse(GrantPresentation.everythingDescription(phoneUse = false).contains("screen"))
        assertEquals("Ask me before it sends messages, calls or pays", GrantPresentation.ASK_SWITCH)
        assertTrue(GrantPresentation.neverAllowLine(3).startsWith("3 apps on your never-allow list"))
        assertTrue(GrantPresentation.neverAllowLine(3).endsWith("stay off-limits, even with Everything."))
        assertTrue(GrantPresentation.neverAllowLine(0).contains("empty"))
    }

    @Test
    fun abilitiesAreShortAndPlain() {
        Ability.entries.forEach {
            assertTrue(it.label, it.label.length <= 32)
            assertTrue(it.label, it.description.length <= 120)
            assertFalse("no scope ids in ${it.label}", Scopes.ALL.any { s -> s.contains('.') && it.description.contains(s) })
        }
    }

    @Test
    fun shortSummaries() {
        assertEquals("Everything · Never asks", GrantPresentation.shortSummary(record()))
        assertEquals("Everything · Asks before messages, calls, payments", GrantPresentation.shortSummary(record(policy = ConfirmPolicy.SENSITIVE)))
        val chosen = record(
            scopes = Ability.SEND_MESSAGES.scopes + Ability.USE_APPS.scopes,
            apps = AppPolicy(AppAccessMode.ALLOWLIST, setOf("com.whatsapp", "com.google.android.gm")),
        )
        assertEquals("Only Gmail, WhatsApp · 2 abilities · Never asks", GrantPresentation.shortSummary(chosen, labels))
        assertEquals(
            "No apps · 1 ability · Never asks",
            GrantPresentation.shortSummary(record(scopes = Ability.LOCATION.scopes, apps = AppPolicy(AppAccessMode.ALLOWLIST)), labels),
        )
        assertEquals("All apps except 1 · 1 ability · Asks before every action",
            GrantPresentation.shortSummary(record(scopes = Ability.LOCATION.scopes, apps = AppPolicy(AppAccessMode.BLOCKLIST, setOf("x")), policy = ConfirmPolicy.ALL_ACTIONS)))
    }

    @Test
    fun detailsShowOlderTokensInTheSameTerms() {
        // The old Assistant preset with "ask for sensitive actions".
        val assistant = GrantPresentation.details(record(scopes = Scopes.PRESET_ASSISTANT, policy = ConfirmPolicy.SENSITIVE), labels)
        assertEquals("Apps: All apps", assistant[0])
        assertTrue(assistant[1], assistant[1].startsWith("Can: Read messages & notifications"))
        assertTrue(assistant[1], assistant[1].endsWith("Use apps (partly)"))
        assertEquals("Can't: Start any app screen (advanced)", assistant[2])
        assertEquals("Asks before messages, calls, payments.", assistant.last())

        val readOnly = GrantPresentation.details(record(scopes = Scopes.PRESET_READ_ONLY, policy = ConfirmPolicy.SENSITIVE, maxRisk = Risk.READ), labels)
        assertTrue(readOnly.toString(), readOnly.any { it.startsWith("Read-only (set earlier)") })

        assertEquals(
            listOf("Everything: every app and everything DroidHands can do.", "Never asks."),
            GrantPresentation.details(record(), labels),
        )
    }

    @Test
    fun partlyLineNamesWhatTheAgentHas() {
        assertEquals("Partly: List and open apps, URLs and chats; run app actions", GrantPresentation.partlyLine(Ability.USE_APPS, Scopes.PRESET_ASSISTANT))
    }

    @Test
    fun olderLimitsHaveNotices() {
        assertTrue(GrantPresentation.capNotice(Risk.READ)!!.startsWith("Read-only"))
        assertNull(GrantPresentation.capNotice(null))
        assertEquals("All apps except 2 apps you blocked (set earlier)", GrantPresentation.blocklistOption(2))
        assertEquals("Allowed: Gmail, WhatsApp", GrantPresentation.chosenAppsLine(setOf("com.whatsapp", "com.google.android.gm"), labels))
        assertEquals("No apps chosen yet.", GrantPresentation.chosenAppsLine(emptySet(), labels))
    }

    @Test
    fun pickerSectionsAndLockedApps() {
        val apps = listOf(
            AppListing("com.bank", "Bank", Integration.BASIC),
            AppListing("com.spotify.music", "Spotify", Integration.PLUGIN, pluginName = "Spotify"),
            AppListing("com.whatsapp", "WhatsApp", Integration.PLUGIN),
            AppListing("com.notes", "Notes", Integration.BASIC),
        )
        val sections = GrantApps.sections(apps, selected = setOf("com.whatsapp", "com.bank", "gone.app"), neverAllow = setOf("com.bank"))
        assertEquals(listOf("integrations", "all", "missing"), sections.map { it.key })
        assertEquals(listOf("com.spotify.music", "com.whatsapp"), sections[0].rows.map { it.packageName })
        assertTrue(sections[0].rows[1].selected)
        assertTrue(sections[0].rows[1].badge!!.text.startsWith("Integration"))
        val bank = sections[1].rows.single { it.packageName == "com.bank" }
        assertTrue(bank.locked)
        assertFalse("never-allow apps can't be chosen", bank.selected)
        assertEquals(listOf("gone.app"), sections[2].rows.map { it.packageName })

        val results = GrantApps.sections(apps, emptySet(), emptySet(), query = "whats")
        assertEquals(listOf("com.whatsapp"), results.single().rows.map { it.packageName })
        assertEquals(mapOf("com.bank" to "Bank", "com.spotify.music" to "Spotify", "com.whatsapp" to "WhatsApp", "com.notes" to "Notes"), GrantApps.labels(apps))
    }

    @Test
    fun `without SMS permissions reading messages means notifications only`() {
        val store = GrantPresentation.abilityDescription(Ability.READ_MESSAGES, phoneUse = false, smsAndCallLog = false)
        assertFalse(store, store.contains("SMS") || store.contains("call"))
        assertTrue(GrantPresentation.abilityDescription(Ability.READ_MESSAGES, phoneUse = true, smsAndCallLog = true).contains("SMS"))
        val send = GrantPresentation.abilityDescription(Ability.SEND_MESSAGES, phoneUse = false, smsAndCallLog = false)
        assertFalse(send, send.contains("Send SMS"))
        assertEquals(Ability.CALLS.description, GrantPresentation.abilityDescription(Ability.CALLS, phoneUse = false, smsAndCallLog = false))
    }
}
