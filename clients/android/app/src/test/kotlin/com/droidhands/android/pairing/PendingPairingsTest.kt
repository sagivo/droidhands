package com.droidhands.android.pairing

import com.droidhands.android.graph.NotificationIds
import com.droidhands.core.transport.PairingDecision
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingPairingsTest {
    private val registry = PendingPairings(maxPending = 3)

    @Test
    fun registerPublishesWithNonceAndOwnNotificationRange() {
        val entry = registry.register(pairingRequest(id = "p_1"))!!
        assertEquals(listOf("p_1"), registry.requests.value.map { it.id })
        assertTrue(entry.nonce.length >= 22)
        assertTrue(entry.notificationId in NotificationIds.PAIRING_BASE until NotificationIds.PAIRING_BASE + NotificationIds.PAIRING_RANGE)
        assertEquals(entry, registry["p_1"])
    }

    @Test
    fun pendingChangesAreReportedBeforeRegisterReturns() {
        val reported = mutableListOf<Boolean>()
        val guarded = PendingPairings(maxPending = 3, onPendingChanged = { reported += it })
        val a = guarded.register(pairingRequest(id = "p_1"))!!
        // The screen-control guard is up before the prompt can be shown.
        assertEquals(listOf(true), reported)
        val b = guarded.register(pairingRequest(id = "p_2"))!!
        guarded.remove(a)
        assertEquals("p_2 is still waiting", true, reported.last())
        guarded.remove(b)
        assertEquals(false, reported.last())
    }

    @Test
    fun duplicateIdsAndOverflowFailClosed() {
        assertNotNull(registry.register(pairingRequest(id = "p_1")))
        assertNull(registry.register(pairingRequest(id = "p_1")))
        assertNotNull(registry.register(pairingRequest(id = "p_2")))
        assertNotNull(registry.register(pairingRequest(id = "p_3")))
        assertNull("at most 3 open prompts", registry.register(pairingRequest(id = "p_4")))
        assertEquals(listOf("p_1", "p_2", "p_3"), registry.requests.value.map { it.id })
    }

    @Test
    fun uniqueNoncesAndIds() {
        val a = registry.register(pairingRequest(id = "p_a"))!!
        val b = registry.register(pairingRequest(id = "p_b"))!!
        assertNotEquals(a.nonce, b.nonce)
        assertNotEquals(a.notificationId, b.notificationId)
    }

    @Test
    fun firstDecisionWins() = runTest {
        val entry = registry.register(pairingRequest())!!
        assertTrue(entry.decide(PairingDecision.Denied("denied")))
        assertFalse(entry.decide(PairingDecision.Approved("pb1.x", "tok")))
        assertEquals(PairingDecision.Denied("denied"), entry.await())
        assertEquals(PairingDecision.Denied("denied"), entry.outcome)
    }

    @Test
    fun nonceMustMatch() {
        val entry = registry.register(pairingRequest(id = "p_n"))!!
        assertFalse(registry.decideWithNonce("p_n", "wrong", PairingDecision.Denied("denied")))
        assertFalse(registry.decideWithNonce("p_unknown", entry.nonce, PairingDecision.Denied("denied")))
        assertNull(entry.outcome)
        assertTrue(registry.decideWithNonce("p_n", entry.nonce, PairingDecision.Denied("denied")))
    }

    @Test
    fun approvalClaimIsExclusive() {
        val entry = registry.register(pairingRequest())!!
        assertTrue(entry.tryBeginApproval())
        assertFalse(entry.tryBeginApproval())
        assertTrue(entry.isApproving)
        entry.endApproval()
        assertTrue(entry.tryBeginApproval())
    }

    @Test
    fun removeOnlyTheRegisteredEntry() {
        val entry = registry.register(pairingRequest(id = "p_r"))!!
        assertTrue(registry.remove(entry))
        assertFalse(registry.remove(entry))
        assertTrue(registry.requests.value.isEmpty())
        assertNotNull("the id can be reused after removal", registry.register(pairingRequest(id = "p_r")))
    }
}
