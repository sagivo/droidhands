package com.droidhands.android.ui.deeplink

import com.droidhands.core.transport.PairingRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApproveLinksTest {
    private fun request(id: String, code: String, expiresAt: Long = 10_000) =
        PairingRequest(id = id, clientName = "Agent", redirectHost = "agent sign-in", displayCode = code, remoteAddress = null, userAgent = null, expiresAt = expiresAt)

    @Test
    fun readsTheCodeOfApproveLinksOnly() {
        assertEquals("K7Q2", ApproveLinks.codeOf("droidhands://approve?code=k7q2"))
        assertEquals("", ApproveLinks.codeOf("droidhands://approve"))
        assertEquals("", ApproveLinks.codeOf("droidhands://approve?code=%3Cb%3E"))
        assertNull(ApproveLinks.codeOf("droidhands://configure?relay=x"))
        assertNull(ApproveLinks.codeOf("https://connect.droidhands.com/approve?code=K7Q2"))
        assertNull(ApproveLinks.codeOf(null))
    }

    @Test
    fun picksTheLiveRequestWithTheCodeElseTheNewest() {
        val pairings = listOf(request("a", "AAAA"), request("b", "BBBB"), request("old", "CCCC", expiresAt = 1))
        assertEquals("a", ApproveLinks.pick(pairings, "AAAA", now = 5)?.id)
        assertEquals("b", ApproveLinks.pick(pairings, "", now = 5)?.id)
        // A code that matches nothing (or only an expired request) opens nothing, never another agent's prompt.
        assertNull(ApproveLinks.pick(pairings, "CCCC", now = 5))
        assertNull(ApproveLinks.pick(pairings, "ZZZZ", now = 5))
    }
}
