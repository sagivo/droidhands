package dev.phonebridge.android.pairing

import dev.phonebridge.core.transport.PairingDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingRulesTest {
    private val now = 1_000L

    @Test
    fun wellFormedRequestIsShown() {
        assertNull(PairingRules.rejection(PairingRules.sanitize(pairingRequest()), now))
    }

    @Test
    fun malformedIdsAndCodesAreDeniedWithoutAPrompt() {
        listOf(
            pairingRequest(id = ""),
            pairingRequest(id = "p_x/../../y"),
            pairingRequest(id = "p_" + "a".repeat(200)),
            pairingRequest(displayCode = ""),
            pairingRequest(displayCode = "K7Q"),
            pairingRequest(displayCode = "K7Q2K7Q2K"),
            pairingRequest(displayCode = "K0O1"),
            pairingRequest(displayCode = "K7 Q2"),
        ).forEach { request ->
            assertEquals(request.toString(), PairingDecision.Denied("denied"), PairingRules.rejection(PairingRules.sanitize(request), now))
        }
    }

    @Test
    fun lowercaseCodeIsNormalized() {
        val clean = PairingRules.sanitize(pairingRequest(displayCode = " k7q2 "))
        assertEquals("K7Q2", clean.displayCode)
        assertNull(PairingRules.rejection(clean, now))
    }

    @Test
    fun expiredRequestTimesOut() {
        assertEquals(PairingDecision.Denied("timeout"), PairingRules.rejection(pairingRequest(expiresAt = now), now))
        assertEquals(PairingDecision.Denied("timeout"), PairingRules.rejection(pairingRequest(expiresAt = now - 5), now))
    }

    @Test
    fun waitIsBoundedByExpiryAndCap() {
        assertEquals(180_000L, PairingRules.waitMillis(pairingRequest(expiresAt = now + 180_000), now))
        assertEquals(PairingRules.MAX_WAIT_MS, PairingRules.waitMillis(pairingRequest(expiresAt = now + 86_400_000), now))
        assertEquals(0L, PairingRules.waitMillis(pairingRequest(expiresAt = now - 1), now))
    }

    @Test
    fun displayedFieldsAreCleaned() {
        val rlo = '\u202E'
        val clean = PairingRules.sanitize(
            pairingRequest(
                clientName = "  Evil$rlo\u0007 Agent\n\n2 ",
                redirectHost = " Claude.AI$rlo ",
                remoteAddress = "  ",
                userAgent = "x".repeat(500),
            ),
        )
        assertEquals("Evil Agent 2", clean.clientName)
        assertEquals("claude.ai", clean.redirectHost)
        assertNull(clean.remoteAddress)
        assertEquals(120, clean.userAgent!!.length)
    }

    @Test
    fun blankNamesGetFallbacks() {
        val clean = PairingRules.sanitize(pairingRequest(clientName = "\u200B ", redirectHost = ""))
        assertEquals(PairingRules.FALLBACK_CLIENT_NAME, clean.clientName)
        assertEquals(PairingRules.FALLBACK_HOST, clean.redirectHost)
    }

    @Test
    fun longClientNameIsCut() {
        val clean = PairingRules.sanitize(pairingRequest(clientName = "A".repeat(100)))
        assertEquals(PairingRules.MAX_CLIENT_NAME, clean.clientName.length)
        assertEquals('…', clean.clientName.last())
    }
}
