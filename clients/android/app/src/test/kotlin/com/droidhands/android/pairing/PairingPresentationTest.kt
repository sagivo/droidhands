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
        assertEquals("Claude wants to connect", PairingPresentation.notificationTitle(request))
        assertEquals("Code K7Q2 · from claude.ai", PairingPresentation.notificationText(request))
        assertEquals("wants access to this phone · claude.ai", PairingPresentation.heroLine(request))
        assertEquals("wants access to this phone", PairingPresentation.heroLine(request.copy(redirectHost = "claude")))
        val big = PairingPresentation.notificationBigText(request)
        assertTrue(big, big.contains("K7Q2") && big.contains("Requested from 203.0.113.7"))
        assertFalse(PairingPresentation.notificationBigText(request.copy(remoteAddress = null)).contains("Requested from"))
    }

    @Test
    fun agentSignInShowsNoWebsiteAndPointsAtTheAgentsCode() {
        val agent = PairingRules.sanitize(pairingRequest(clientName = "Instinct", redirectHost = "agent sign-in", viaAgent = true))
        assertEquals("Instinct wants to connect", PairingPresentation.notificationTitle(agent))
        assertEquals("Code K7Q2 · signed in from its chat", PairingPresentation.notificationText(agent))
        val big = PairingPresentation.notificationBigText(agent)
        assertTrue(big, big.startsWith("Signed in from its chat. If your agent showed you K7Q2") && !big.contains("browser"))
        assertEquals("wants access to this phone · signed in from its chat", PairingPresentation.heroLine(agent))
        assertEquals(PairingPresentation.AGENT_CODE_HINT, PairingPresentation.codeHint(agent))
        assertEquals(PairingPresentation.AGENT_SIGN_IN, PairingPresentation.clientHost(agent))
        listOf(
            PairingPresentation.notificationTitle(agent),
            PairingPresentation.notificationText(agent),
            PairingPresentation.heroLine(agent),
            PairingPresentation.codeHint(agent),
        ).forEach {
            assertFalse(it, it.contains("agent sign-in") || it.contains("browser"))
        }

        assertEquals("wants access to this phone · claude.ai", PairingPresentation.heroLine(request))
        assertEquals(PairingPresentation.CODE_HINT, PairingPresentation.codeHint(request))
        assertEquals("claude.ai", PairingPresentation.clientHost(request))
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

    @Test
    fun labeledRequestSaysWhatItCallsItself() {
        val grokBot = PairingRules.labeled(pairingRequest(clientName = "Cursor", redirectHost = "www.cursor.com"), "Grok Bot")
        assertEquals("Grok Bot wants to connect", PairingPresentation.notificationTitle(grokBot))
        assertEquals("wants access to this phone · www.cursor.com · calls itself Cursor", PairingPresentation.heroLine(grokBot))
        assertTrue(PairingPresentation.notificationBigText(grokBot).startsWith("From www.cursor.com (it calls itself Cursor). "))
        // A typed-in client ID is named after its website: no need to repeat it.
        val muse = PairingRules.labeled(pairingRequest(clientName = "agent.meta.ai", redirectHost = "agent.meta.ai"), "Muse")
        assertEquals("wants access to this phone · agent.meta.ai", PairingPresentation.heroLine(muse))
        assertEquals(null, PairingPresentation.callsItself(pairingRequest()))
    }
}
