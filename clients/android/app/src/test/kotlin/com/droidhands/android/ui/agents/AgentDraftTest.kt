package com.droidhands.android.ui.agents

import com.droidhands.android.ui.tokens.ExpiryOption
import com.droidhands.core.auth.ActiveHours
import com.droidhands.core.auth.AppAccessMode
import com.droidhands.core.auth.AppPolicy
import com.droidhands.core.auth.ConfirmPolicy
import com.droidhands.core.auth.TokenRecord
import com.droidhands.core.auth.ToolRule
import com.droidhands.core.tools.Risk
import com.droidhands.core.tools.Scopes
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
            .copy(name = "  Claude laptop ", expiry = ExpiryOption.DAYS_7)
            .withRule("sms_send", RuleChoice.BLOCK)
            .copy(activeHours = ActiveHours(480, 1380))
        assertTrue(edited.differsFrom(record))
        val applied = edited.applyTo(record, now)
        assertEquals("Claude laptop", applied.name)
        assertEquals(now + 7 * day, applied.expiresAt)
        assertEquals(mapOf("sms_send" to ToolRule.BLOCK), applied.toolRules)
        assertEquals(ActiveHours(480, 1380), applied.activeHours)
        // Everything else, access included, is left alone.
        assertEquals(record.copy(name = applied.name, expiresAt = applied.expiresAt, toolRules = applied.toolRules, activeHours = applied.activeHours), applied)
    }

    @Test
    fun savingNeverOverwritesAccessChangedMeanwhile() {
        val draft = AgentDraft.from(record).copy(name = "Renamed")
        // The user saved a new grant on the Access page while this draft was open.
        val regranted = record.copy(
            scopes = setOf(Scopes.LOCATION), appPolicy = AppPolicy(AppAccessMode.ALLOWLIST, setOf("com.whatsapp")),
            confirmPolicy = ConfirmPolicy.NEVER, maxRisk = Risk.ACTION,
        )
        val saved = draft.applyTo(regranted, now)
        assertEquals("Renamed", saved.name)
        assertEquals(regranted.copy(name = "Renamed"), saved)
        // And a grant change alone doesn't make the draft dirty.
        assertFalse(AgentDraft.from(record).differsFrom(regranted))
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
    fun validation() {
        val draft = AgentDraft.from(record)
        assertNotNull(draft.copy(name = "  ").nameError)
        assertNotNull(draft.copy(name = "x".repeat(41)).nameError)
        assertNotNull(draft.copy(activeHours = ActiveHours(600, 600)).activeHoursError)
        assertFalse(draft.copy(activeHours = ActiveHours(600, 600)).canSave)
        assertNull(AgentDraft.nameError("Claude"))
    }
}
