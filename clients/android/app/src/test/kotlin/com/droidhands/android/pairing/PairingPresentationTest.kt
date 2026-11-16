package com.droidhands.android.pairing

import com.droidhands.core.auth.AppPolicy
import com.droidhands.core.auth.ConfirmPolicy
import com.droidhands.core.auth.GrantDraft
import com.droidhands.core.auth.GrantMode
import com.droidhands.core.auth.GrantSettings
import com.droidhands.core.tools.Scopes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingPresentationTest {
    private val request = PairingRules.sanitize(pairingRequest())

    @Test
    fun defaultsAreEverythingWithoutPrompts() {
        val choice = PairingChoice.defaults(request)
        assertEquals("Claude", choice.name)
        assertEquals(GrantMode.EVERYTHING, choice.grant.mode)
        assertEquals(GrantSettings(Scopes.ALL, AppPolicy(), ConfirmPolicy.NEVER, null), choice.grant.settings())
        assertTrue(choice.grant.toolRulesUntouched())
    }

    /** The grant screen never creates per-tool rules: [GrantDraft] has no field for them. */
    private fun GrantDraft.toolRulesUntouched() = GrantDraft::class.java.declaredFields.none { it.name.contains("rule", ignoreCase = true) }

    @Test
    fun notificationShowsClientHostAndCode() {
        assertEquals("Claude (claude.ai) wants to connect · code K7Q2", PairingPresentation.notificationTitle(request))
        assertTrue(PairingPresentation.notificationText(request).startsWith("Code K7Q2"))
        val big = PairingPresentation.notificationBigText(request)
        assertTrue(big, big.contains("K7Q2") && big.contains("Requested from 203.0.113.7"))
        assertFalse(PairingPresentation.notificationBigText(request.copy(remoteAddress = null)).contains("Requested from"))
    }

    @Test
    fun tokenNameDefaultsToTheClientName() {
        assertEquals("Claude", PairingPresentation.tokenName("   ", request))
        assertEquals("Work laptop", PairingPresentation.tokenName("  Work   laptop ", request))
        assertEquals(PairingRules.MAX_CLIENT_NAME, PairingPresentation.tokenName("n".repeat(80), request).length)
    }

    @Test
    fun countdown() {
        assertEquals(180L, PairingPresentation.secondsRemaining(expiresAt = 181_000, now = 1_000))
        assertEquals(1L, PairingPresentation.secondsRemaining(expiresAt = 1_001, now = 1_000))
        assertEquals(0L, PairingPresentation.secondsRemaining(expiresAt = 1_000, now = 2_000))
        assertEquals("Expires in 3:00", PairingPresentation.countdownLabel(180))
        assertEquals("Expires in 0:09", PairingPresentation.countdownLabel(9))
        assertEquals("Expired", PairingPresentation.countdownLabel(0))
    }

    @Test
    fun codeIsSpelledForScreenReaders() {
        assertEquals("K 7 Q 2", PairingPresentation.spokenCode("K7Q2"))
    }
}
