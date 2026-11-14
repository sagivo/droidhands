package dev.phonebridge.android.ui.agents

import dev.phonebridge.android.ui.tokens.ExpiryOption
import dev.phonebridge.core.auth.ActiveHours
import dev.phonebridge.core.auth.AppAccessMode
import dev.phonebridge.core.auth.AppPolicy
import dev.phonebridge.core.auth.ConfirmPolicy
import dev.phonebridge.core.auth.TokenRecord
import dev.phonebridge.core.auth.ToolRule
import dev.phonebridge.core.tools.Scopes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentDraftTest {
    private val now = 1_731_500_000_000L
    private val day = 86_400_000L
    private val record = TokenRecord(
        id = "abcdefghijkl", name = "Claude", secretHash = "00", scopes = Scopes.PRESET_ASSISTANT,
        createdAt = now - day, expiresAt = now + 3 * day, lastUsedAt = now - 1000, origin = "pairing", clientHost = "claude.ai",
    )

    @Test
    fun untouchedDraftDoesNotDiffer() {
        val draft = AgentDraft.from(record)
        assertFalse(draft.differsFrom(record))
        assertFalse(draft.differsFrom(record.copy(lastUsedAt = now)))
        assertEquals(record, draft.applyTo(record, now))
        assertTrue(draft.canSave)
    }

    @Test
    fun appliesEveryEditableField() {
        val edited = AgentDraft.from(record)
            .copy(name = "  Claude laptop ", policy = ConfirmPolicy.ALL_ACTIONS, expiry = ExpiryOption.DAYS_7)
            .withLevel(AccessLevel.READ_ONLY)
            .withRule("sms_send", RuleChoice.BLOCK)
            .withAppMode(AppAccessMode.ALLOWLIST)
            .toggleApp("com.whatsapp")
            .copy(activeHours = ActiveHours(480, 1380))
        assertTrue(edited.differsFrom(record))
        val applied = edited.applyTo(record, now)
        assertEquals("Claude laptop", applied.name)
        assertEquals(Scopes.PRESET_READ_ONLY, applied.scopes)
        assertEquals(ConfirmPolicy.ALL_ACTIONS, applied.confirmPolicy)
        assertEquals(now + 7 * day, applied.expiresAt)
        assertEquals(mapOf("sms_send" to ToolRule.BLOCK), applied.toolRules)
        assertEquals(AppPolicy(AppAccessMode.ALLOWLIST, setOf("com.whatsapp")), applied.appPolicy)
        assertEquals(ActiveHours(480, 1380), applied.activeHours)
        // Immutable fields are left alone.
        assertEquals(record.copy(name = applied.name, scopes = applied.scopes, confirmPolicy = applied.confirmPolicy,
            expiresAt = applied.expiresAt, toolRules = applied.toolRules, appPolicy = applied.appPolicy, activeHours = applied.activeHours), applied)
    }

    @Test
    fun expiryChoices() {
        val draft = AgentDraft.from(record)
        assertNull(draft.copy(expiry = ExpiryOption.NEVER).applyTo(record, now).expiresAt)
        assertEquals(now + day, draft.copy(expiry = ExpiryOption.DAY_1).applyTo(record, now).expiresAt)
        assertEquals(record.expiresAt, draft.applyTo(record, now).expiresAt)
        assertTrue(draft.copy(expiry = ExpiryOption.NEVER).differsFrom(record))
        assertEquals(Long.MAX_VALUE, draft.copy(expiry = ExpiryOption.DAYS_90).applyTo(record, Long.MAX_VALUE - 5).expiresAt)
    }

    @Test
    fun appModeSwitchesKeepSelectionButAllStoresNoPackages() {
        val allow = AgentDraft.from(record).withAppMode(AppAccessMode.ALLOWLIST).toggleApp("a").toggleApp("b").toggleApp("a")
        assertEquals(setOf("b"), allow.appPolicy.packages)
        val back = allow.withAppMode(AppAccessMode.ALL)
        assertEquals(setOf("b"), back.appPolicy.packages)
        assertFalse(back.differsFrom(record))
        assertEquals(AppPolicy(), back.applyTo(record, now).appPolicy)
        assertEquals(AppPolicy(AppAccessMode.ALLOWLIST, setOf("b")), back.withAppMode(AppAccessMode.ALLOWLIST).applyTo(record, now).appPolicy)
    }

    @Test
    fun validation() {
        val draft = AgentDraft.from(record)
        assertNotNull(draft.copy(name = "  ").nameError)
        assertNotNull(draft.copy(name = "x".repeat(41)).nameError)
        assertNotNull(draft.copy(scopes = emptySet()).scopesError)
        assertNotNull(draft.copy(activeHours = ActiveHours(600, 600)).activeHoursError)
        assertFalse(draft.copy(scopes = emptySet()).canSave)
        assertFalse(draft.copy(activeHours = ActiveHours(600, 600)).canSave)
        assertNull(AgentDraft.nameError("Claude"))
        assertNotNull(draft.withAppMode(AppAccessMode.ALLOWLIST).appPolicyWarning)
        assertNull(draft.withAppMode(AppAccessMode.BLOCKLIST).appPolicyWarning)
    }

    @Test
    fun levelsAndScopes() {
        val draft = AgentDraft.from(record)
        assertEquals(AccessLevel.ASSISTANT, draft.accessLevel)
        assertEquals(AccessLevel.FULL, draft.withLevel(AccessLevel.FULL).accessLevel)
        assertEquals(draft, draft.withLevel(AccessLevel.CUSTOM))
        val custom = draft.toggleScope(Scopes.CALLS)
        assertEquals(AccessLevel.CUSTOM, custom.accessLevel)
        assertEquals(AccessLevel.ASSISTANT, custom.toggleScope(Scopes.CALLS).accessLevel)
    }
}
