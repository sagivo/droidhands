package dev.phonebridge.android.ui.tokens

import dev.phonebridge.core.auth.ConfirmPolicy
import dev.phonebridge.core.auth.GrantMode
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
    fun expiryOptions() {
        assertNull(ExpiryOption.NEVER.ttlMillis)
        assertEquals(day, ExpiryOption.DAY_1.ttlMillis)
        assertEquals(90 * day, ExpiryOption.DAYS_90.ttlMillis)
    }

    @Test
    fun draft() {
        val d = TokenDraft()
        assertEquals("Claude", d.name)
        // The same grant as pairing: Everything, no prompts, until the user chooses otherwise.
        assertEquals(GrantMode.EVERYTHING, d.grant.mode)
        assertEquals(ConfirmPolicy.NEVER, d.grant.settings().confirmPolicy)
        assertEquals(Scopes.ALL, d.grant.settings().scopes)
        assertTrue(d.canCreate)
        assertFalse(d.copy(name = "  ").canCreate)
        assertFalse(d.copy(name = "x".repeat(TokenDraft.MAX_NAME_LENGTH + 1)).canCreate)
        assertFalse(d.copy(grant = d.grant.withMode(GrantMode.CHOSEN).copy(scopes = emptySet())).canCreate)
        assertTrue(d.copy(grant = d.grant.withMode(GrantMode.CHOSEN)).canCreate)
    }
}
