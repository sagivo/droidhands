package dev.phonebridge.android.confirm

import dev.phonebridge.android.graph.NotificationIds
import dev.phonebridge.core.confirm.ConfirmationRequest
import dev.phonebridge.core.confirm.ConfirmationResult
import dev.phonebridge.core.tools.Risk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PendingConfirmationsTest {
    private var now = 1_000L
    private val registry = PendingConfirmations(clock = { now })

    private fun request(id: String) = ConfirmationRequest(
        id = id,
        tokenName = "Claude",
        toolName = "sms_send",
        toolTitle = "Send SMS",
        summary = "Send SMS to Tania Cohen (+972541234567): \"hi\"",
        risk = Risk.SENSITIVE,
        transport = "relay",
    )

    @Test
    fun registerPublishesRequestWithDeadlineNonceAndNotificationId() {
        val entry = registry.register(request("a"), timeoutMs = 60_000)!!
        assertEquals(listOf(request("a")), registry.requests.value)
        assertEquals(1_000L, entry.createdAt)
        assertEquals(61_000L, entry.expiresAt)
        assertTrue(entry.nonce.length >= 22)
        assertTrue(entry.notificationId >= NotificationIds.CONFIRM_BASE)
        assertTrue(entry.notificationId != NotificationIds.STATUS)
        assertEquals(entry, registry["a"])
    }

    @Test
    fun noncesAndNotificationIdsAreUnique() {
        val a = registry.register(request("a"), 60_000)!!
        val b = registry.register(request("b"), 60_000)!!
        assertNotEquals(a.nonce, b.nonce)
        assertNotEquals(a.notificationId, b.notificationId)
    }

    @Test
    fun duplicateIdFailsClosed() {
        assertNotNull(registry.register(request("a"), 60_000))
        assertNull(registry.register(request("a"), 60_000))
        assertEquals(1, registry.requests.value.size)
    }

    @Test
    fun requestsAreOldestFirst() {
        registry.register(request("first"), 60_000)
        now += 10
        registry.register(request("second"), 60_000)
        now += 10
        registry.register(request("third"), 60_000)
        assertEquals(listOf("first", "second", "third"), registry.requests.value.map { it.id })
        assertEquals(listOf("first", "second", "third"), registry.all().map { it.request.id })
    }

    @Test
    fun firstDecisionWins() = runTest {
        val entry = registry.register(request("a"), 60_000)!!
        assertTrue(registry.resolve("a", approved = true))
        assertFalse(registry.resolve("a", approved = false))
        assertEquals(ConfirmationResult.APPROVED, entry.result.await())
    }

    @Test
    fun unknownIdsAreIgnored() {
        assertFalse(registry.resolve("missing", approved = true))
        assertFalse(registry.resolveWithNonce("missing", "nonce", approved = true))
    }

    @Test
    fun wrongNonceDoesNotResolve() = runTest {
        val entry = registry.register(request("a"), 60_000)!!
        assertFalse(registry.resolveWithNonce("a", "forged", approved = true))
        assertFalse(registry.resolveWithNonce("a", "", approved = true))
        assertFalse(entry.result.isCompleted)
        assertTrue(registry.resolveWithNonce("a", entry.nonce, approved = false))
        assertEquals(ConfirmationResult.DENIED, entry.result.await())
    }

    @Test
    fun removeOnlyRemovesTheSameRegistration() {
        val first = registry.register(request("a"), 60_000)!!
        assertTrue(registry.remove(first))
        assertTrue(registry.requests.value.isEmpty())
        val second = registry.register(request("a"), 60_000)!!
        // A stale cleanup of the first registration must not drop the new one.
        assertFalse(registry.remove(first))
        assertEquals(second, registry["a"])
        assertEquals(1, registry.requests.value.size)
    }

    @Test
    fun hasPendingFollowsTheRegistrations() {
        assertFalse(registry.hasPending.value)
        val a = registry.register(request("a"), 60_000)!!
        val b = registry.register(request("b"), 60_000)!!
        assertTrue(registry.hasPending.value)
        registry.remove(a)
        assertTrue("b is still waiting", registry.hasPending.value)
        registry.remove(b)
        assertFalse(registry.hasPending.value)
    }

    @Test
    fun pendingChangesAreReportedBeforeRegisterReturns() {
        val reported = mutableListOf<Boolean>()
        val guarded = PendingConfirmations(clock = { now }, onPendingChanged = { reported += it })
        val a = guarded.register(request("a"), 60_000)!!
        // The screen-control guard is up before the prompt can be shown.
        assertEquals(listOf(true), reported)
        val b = guarded.register(request("b"), 60_000)!!
        guarded.resolve("a", approved = true)
        guarded.remove(a)
        assertEquals("b is still waiting", true, reported.last())
        guarded.remove(b)
        assertEquals(false, reported.last())
    }

    @Test
    fun denyAllDeniesEveryPendingRequest() = runTest {
        val a = registry.register(request("a"), 60_000)!!
        val b = registry.register(request("b"), 60_000)!!
        registry.resolve("b", approved = true)
        assertEquals(1, registry.denyAll())
        assertEquals(ConfirmationResult.DENIED, a.result.await())
        assertEquals(ConfirmationResult.APPROVED, b.result.await())
    }
}
