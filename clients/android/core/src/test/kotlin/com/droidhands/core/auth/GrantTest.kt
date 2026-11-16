package com.droidhands.core.auth

import com.droidhands.core.tools.Risk
import com.droidhands.core.tools.Scopes
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GrantTest {
    private fun record(
        scopes: Set<String>,
        apps: AppPolicy = AppPolicy(),
        policy: ConfirmPolicy = ConfirmPolicy.NEVER,
        maxRisk: Risk? = null,
        rules: Map<String, ToolRule> = emptyMap(),
    ) = TokenRecord(
        id = "aaaaaaaaaaaa", name = "Claude", secretHash = "", scopes = scopes, confirmPolicy = policy, createdAt = 1,
        appPolicy = apps, maxRisk = maxRisk, toolRules = rules, activeHours = ActiveHours(8 * 60, 23 * 60),
    )

    @Test
    fun abilitiesCoverEveryScopeExactlyOnce() {
        val all = Ability.entries.flatMap { it.scopes }
        assertEquals(all.size, all.toSet().size, "a scope is in two abilities")
        assertEquals(Scopes.ALL, all.toSet())
        Scopes.ALL.forEach { assertNotNull(Ability.of(it), it) }
        assertNull(Ability.of(""))
        assertNull(Ability.of("nope"))
    }

    @Test
    fun abilityToScopeMapping() {
        assertEquals(setOf(Scopes.MESSAGES_READ, Scopes.NOTIFICATIONS_READ), Ability.READ_MESSAGES.scopes)
        assertEquals(setOf(Scopes.CONTACTS_READ, Scopes.CALENDAR_READ), Ability.READ_CONTACTS_CALENDAR.scopes)
        assertEquals(setOf(Scopes.LOCATION), Ability.LOCATION.scopes)
        assertEquals(setOf(Scopes.SCREEN_READ), Ability.SEE_SCREEN.scopes)
        assertEquals(setOf(Scopes.APPS, Scopes.SCREEN_CONTROL), Ability.USE_APPS.scopes)
        assertEquals(setOf(Scopes.MESSAGES_SEND, Scopes.NOTIFICATIONS_ACT), Ability.SEND_MESSAGES.scopes)
        assertEquals(setOf(Scopes.CALLS), Ability.CALLS.scopes)
        assertEquals(setOf(Scopes.CALENDAR_WRITE), Ability.CHANGE_CALENDAR.scopes)
        assertEquals(setOf(Scopes.DEVICE), Ability.DEVICE.scopes)
        assertEquals(setOf(Scopes.INTENTS), Ability.ADVANCED.scopes)
    }

    @Test
    fun newAgentDefaultsToEverythingWithoutPrompts() {
        val grant = GrantDraft.forNewAgent()
        assertEquals(GrantMode.EVERYTHING, grant.mode)
        assertFalse(grant.asksBeforeSensitive)
        assertEquals(GrantSettings(Scopes.ALL, AppPolicy(), ConfirmPolicy.NEVER, null), grant.settings())
        assertNull(grant.error)
    }

    @Test
    fun everythingMapsToAllScopesAllAppsNoCap() {
        val grant = GrantDraft.forNewAgent().withAsk(true)
        assertEquals(GrantSettings(Scopes.ALL, AppPolicy(), ConfirmPolicy.SENSITIVE, null), grant.settings())
        // Even when the hidden "chosen" part is narrow.
        val narrow = GrantDraft(GrantMode.EVERYTHING, setOf(Scopes.LOCATION), AppPolicy(AppAccessMode.ALLOWLIST, setOf("a")), maxRisk = Risk.READ)
        assertEquals(GrantSettings(Scopes.ALL, AppPolicy(), ConfirmPolicy.NEVER, null), narrow.settings())
    }

    @Test
    fun chosenMapsAbilitiesAndAllowlist() {
        val grant = GrantDraft.forNewAgent().copy(mode = GrantMode.CHOSEN, scopes = emptySet())
            .toggle(Ability.SEND_MESSAGES)
            .toggle(Ability.USE_APPS)
            .withAppMode(AppAccessMode.ALLOWLIST)
            .toggleApp("com.whatsapp")
            .toggleApp("com.google.android.gm")
        val s = grant.settings()
        assertEquals(setOf(Scopes.MESSAGES_SEND, Scopes.NOTIFICATIONS_ACT, Scopes.APPS, Scopes.SCREEN_CONTROL), s.scopes)
        assertEquals(AppPolicy(AppAccessMode.ALLOWLIST, setOf("com.whatsapp", "com.google.android.gm")), s.appPolicy)
        assertEquals(ConfirmPolicy.NEVER, s.confirmPolicy, "only what I choose: no prompts either")
        assertNull(s.maxRisk)
        assertEquals(setOf(Ability.SEND_MESSAGES, Ability.USE_APPS), grant.abilities)
    }

    @Test
    fun chosenDefaultsAreLookingAndUsingApps() {
        val grant = GrantDraft.forNewAgent().copy(mode = GrantMode.CHOSEN)
        assertEquals(Ability.DEFAULT_CHOSEN, grant.abilities)
        assertFalse(Scopes.MESSAGES_SEND in grant.settings().scopes)
        assertFalse(Scopes.CALLS in grant.settings().scopes)
    }

    @Test
    fun askSwitchMapsToSensitive() {
        val grant = GrantDraft.forNewAgent().copy(mode = GrantMode.CHOSEN).withAsk(true)
        assertEquals(ConfirmPolicy.SENSITIVE, grant.settings().confirmPolicy)
        assertEquals(ConfirmPolicy.NEVER, grant.withAsk(false).settings().confirmPolicy)
    }

    @Test
    fun allAppsDropsChosenPackagesOnSaveButKeepsThemInTheDraft() {
        val grant = GrantDraft(GrantMode.CHOSEN, Ability.USE_APPS.scopes, AppPolicy(AppAccessMode.ALLOWLIST, setOf("a", "b")))
            .withAppMode(AppAccessMode.ALL)
        assertEquals(AppPolicy(), grant.settings().appPolicy)
        assertEquals(setOf("a", "b"), grant.apps.packages)
        assertEquals(AppPolicy(AppAccessMode.ALLOWLIST, setOf("a", "b")), grant.withAppMode(AppAccessMode.ALLOWLIST).settings().appPolicy)
    }

    @Test
    fun aBlocklistNeverBecomesAnAllowlist() {
        val legacy = GrantDraft.from(record(Scopes.ALL, apps = AppPolicy(AppAccessMode.BLOCKLIST, setOf("com.bank"))))
        assertEquals(AppPolicy(AppAccessMode.ALLOWLIST), legacy.withAppMode(AppAccessMode.ALLOWLIST).apps)
        assertEquals(AppPolicy(), legacy.withAppMode(AppAccessMode.ALL).settings().appPolicy)
        assertSame(legacy, legacy.withAppMode(AppAccessMode.BLOCKLIST))
    }

    @Test
    fun validation() {
        val empty = GrantDraft(GrantMode.CHOSEN, emptySet())
        assertEquals("Choose at least one thing it can do.", empty.error)
        assertFalse(empty.isValid)
        val noApps = GrantDraft(GrantMode.CHOSEN, Ability.USE_APPS.scopes, AppPolicy(AppAccessMode.ALLOWLIST))
        assertTrue(noApps.isValid)
        assertNotNull(noApps.warning)
        assertNull(GrantDraft(GrantMode.EVERYTHING, emptySet()).error)
    }

    @Test
    fun applyToChangesOnlyAccessFields() {
        val before = record(Scopes.ALL, rules = mapOf("sms_send" to ToolRule.ASK))
        val after = GrantDraft.from(before).copy(mode = GrantMode.CHOSEN, scopes = Ability.LOCATION.scopes).applyTo(before)
        assertEquals(setOf(Scopes.LOCATION), after.scopes)
        assertEquals(before.copy(scopes = after.scopes), after, "name, rules, hours, expiry untouched")
    }

    @Test
    fun grantNeverCreatesToolRules() {
        val before = record(Scopes.ALL)
        val after = GrantDraft.forNewAgent().copy(mode = GrantMode.CHOSEN).withAsk(true).applyTo(before)
        assertTrue(after.toolRules.isEmpty())
    }

    @Test
    fun existingTokensRoundTripUnchanged() {
        val records = listOf(
            record(Scopes.ALL),
            record(Scopes.ALL, policy = ConfirmPolicy.SENSITIVE),
            record(Scopes.PRESET_READ_ONLY, policy = ConfirmPolicy.SENSITIVE, maxRisk = Risk.READ),
            record(Scopes.PRESET_ASSISTANT, policy = ConfirmPolicy.ALL_ACTIONS),
            record(setOf(Scopes.MESSAGES_READ), apps = AppPolicy(AppAccessMode.BLOCKLIST, setOf("com.bank"))),
            record(Scopes.ALL, apps = AppPolicy(AppAccessMode.ALL, setOf("leftover"))),
            record(setOf(Scopes.APPS), apps = AppPolicy(AppAccessMode.ALLOWLIST, emptySet())),
        )
        for (r in records) {
            val draft = GrantDraft.from(r)
            assertSame(r.appPolicy, draft.applyTo(r).appPolicy)
            assertEquals(r, draft.applyTo(r), "no silent migration of $r")
            assertFalse(draft.differsFrom(r))
        }
    }

    @Test
    fun existingTokensShowInGrantTerms() {
        assertEquals(GrantMode.EVERYTHING, GrantDraft.from(record(Scopes.ALL, policy = ConfirmPolicy.SENSITIVE)).mode)
        assertTrue(GrantDraft.from(record(Scopes.ALL, policy = ConfirmPolicy.SENSITIVE)).asksBeforeSensitive)

        // The old Assistant preset: apps without screen control is a partly granted "Use apps".
        val assistant = GrantDraft.from(record(Scopes.PRESET_ASSISTANT, policy = ConfirmPolicy.SENSITIVE))
        assertEquals(GrantMode.CHOSEN, assistant.mode)
        assertEquals(setOf(Ability.USE_APPS), assistant.partialAbilities)
        assertFalse(Ability.USE_APPS in assistant.abilities)
        assertTrue(Ability.SEND_MESSAGES in assistant.abilities)

        // Read-only keeps its cap until removed.
        val readOnly = GrantDraft.from(record(Scopes.PRESET_READ_ONLY, policy = ConfirmPolicy.SENSITIVE, maxRisk = Risk.READ))
        assertEquals(Risk.READ, readOnly.settings().maxRisk)
        assertNull(readOnly.withoutRiskCap().settings().maxRisk)

        // "Ask for every action" is not Everything, and stays until the switch changes.
        val allActions = GrantDraft.from(record(Scopes.ALL, policy = ConfirmPolicy.ALL_ACTIONS))
        assertEquals(GrantMode.CHOSEN, allActions.mode)
        assertEquals(ConfirmPolicy.ALL_ACTIONS, allActions.settings().confirmPolicy)
        assertEquals(ConfirmPolicy.NEVER, allActions.withAsk(false).settings().confirmPolicy)

        // Allowlists and blocklists are not Everything.
        assertEquals(GrantMode.CHOSEN, GrantDraft.from(record(Scopes.ALL, apps = AppPolicy(AppAccessMode.BLOCKLIST, setOf("x")))).mode)
    }

    @Test
    fun tickingAPartialAbilityGrantsAllOfIt() {
        val assistant = GrantDraft.from(record(Scopes.PRESET_ASSISTANT))
        val ticked = assistant.toggle(Ability.USE_APPS)
        assertTrue(Ability.USE_APPS in ticked.abilities)
        assertFalse(Ability.USE_APPS in ticked.toggle(Ability.USE_APPS).abilities)
        assertTrue(ticked.toggle(Ability.USE_APPS).scopes.none { it in Ability.USE_APPS.scopes })
    }

    @Test
    fun choosingEverythingClearsOlderLimits() {
        val r = record(Scopes.PRESET_READ_ONLY, apps = AppPolicy(AppAccessMode.BLOCKLIST, setOf("x")), policy = ConfirmPolicy.ALL_ACTIONS, maxRisk = Risk.READ)
        val after = GrantDraft.from(r).withMode(GrantMode.EVERYTHING).applyTo(r)
        assertEquals(Scopes.ALL, after.scopes)
        assertEquals(AppPolicy(), after.appPolicy)
        assertNull(after.maxRisk)
        // The switch stays on, and now means what it says: ask before messages, calls and payments.
        assertEquals(ConfirmPolicy.SENSITIVE, after.confirmPolicy)
        assertEquals(ConfirmPolicy.NEVER, GrantDraft.from(record(Scopes.PRESET_ASSISTANT)).withMode(GrantMode.EVERYTHING).applyTo(r).confirmPolicy)
    }
}
