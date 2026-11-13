package dev.phonebridge.android.ui.tokens

import dev.phonebridge.core.auth.ConfirmPolicy
import dev.phonebridge.core.auth.TokenRecord
import dev.phonebridge.core.tools.Scopes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenPresentationTest {
    private val now = 1_731_500_000_000L
    private val day = 86_400_000L

    private fun record(revoked: Boolean = false, expiresAt: Long? = null) = TokenRecord(
        id = "abcdefghijkl", name = "Claude", secretHash = "00", scopes = Scopes.PRESET_ASSISTANT,
        createdAt = now - day, expiresAt = expiresAt, revoked = revoked,
    )

    @Test
    fun state() {
        assertEquals(TokenState.ACTIVE, TokenPresentation.state(record(), now))
        assertEquals(TokenState.ACTIVE, TokenPresentation.state(record(expiresAt = now + 1), now))
        assertEquals(TokenState.EXPIRED, TokenPresentation.state(record(expiresAt = now), now))
        assertEquals(TokenState.REVOKED, TokenPresentation.state(record(revoked = true, expiresAt = now - 1), now))
    }

    @Test
    fun scopeSummary() {
        assertEquals("Read-only", TokenPresentation.scopeSummary(Scopes.PRESET_READ_ONLY))
        assertEquals("Assistant", TokenPresentation.scopeSummary(Scopes.PRESET_ASSISTANT))
        assertEquals("Full control", TokenPresentation.scopeSummary(Scopes.ALL))
        assertEquals("No scopes", TokenPresentation.scopeSummary(emptySet()))
        assertEquals(
            "contacts.read, messages.send",
            TokenPresentation.scopeSummary(setOf(Scopes.MESSAGES_SEND, Scopes.CONTACTS_READ)),
        )
        assertEquals("Custom · 4 scopes", TokenPresentation.scopeSummary(setOf("device", "apps", "calls", "location")))
    }

    @Test
    fun orderedScopesKeepsCatalogOrderAndUnknownLast() {
        assertEquals(
            listOf(Scopes.DEVICE, Scopes.CALLS, "x.unknown"),
            TokenPresentation.orderedScopes(setOf("x.unknown", Scopes.CALLS, Scopes.DEVICE)),
        )
    }

    @Test
    fun expiryAndUsage() {
        assertEquals("Never expires", TokenPresentation.expiryText(null, now))
        assertEquals("Expires in 7 days", TokenPresentation.expiryText(now + 7 * day, now))
        assertEquals("Expired 2 days ago", TokenPresentation.expiryText(now - 2 * day, now))
        assertEquals("Never used", TokenPresentation.lastUsedText(null, now))
        assertEquals("Used just now", TokenPresentation.lastUsedText(now - 1000, now))
        assertEquals("Created 1 day ago", TokenPresentation.createdText(now - day, now))
    }

    @Test
    fun defaultName() {
        assertEquals("Claude", TokenPresentation.defaultName(emptyList()))
        assertEquals("Claude 2", TokenPresentation.defaultName(listOf("claude ")))
        assertEquals("Claude 4", TokenPresentation.defaultName(listOf("Claude", "Claude 2", "Claude 3")))
    }

    @Test
    fun policyTexts() {
        ConfirmPolicy.entries.forEach {
            assertTrue(TokenPresentation.policyLabel(it).isNotBlank())
            assertTrue(TokenPresentation.policyDescription(it).isNotBlank())
        }
    }

    @Test
    fun expiryOptions() {
        assertNull(ExpiryOption.NEVER.ttlMillis)
        assertEquals(day, ExpiryOption.DAY_1.ttlMillis)
        assertEquals(90 * day, ExpiryOption.DAYS_90.ttlMillis)
    }

    @Test
    fun draft() {
        val d = TokenDraft()
        assertEquals("Claude", d.name)
        assertEquals(ConfirmPolicy.SENSITIVE, d.policy)
        assertEquals("Assistant", d.selectedPreset)
        assertTrue(d.canCreate)
        assertFalse(d.copy(name = "  ").canCreate)
        assertFalse(d.copy(name = "x".repeat(TokenDraft.MAX_NAME_LENGTH + 1)).canCreate)
        assertFalse(d.copy(scopes = emptySet()).canCreate)

        val custom = d.toggle(Scopes.SCREEN_CONTROL)
        assertNull(custom.selectedPreset)
        assertEquals(setOf(Scopes.SCREEN_CONTROL), custom.highRiskScopes)
        assertEquals(d.scopes, custom.toggle(Scopes.SCREEN_CONTROL).scopes)
        assertEquals("Full control", custom.withPreset("Full control").selectedPreset)
        assertEquals(custom, custom.withPreset("nope"))
    }
}
