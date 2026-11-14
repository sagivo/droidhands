package dev.phonebridge.android.pairing

import dev.phonebridge.core.auth.ConfirmPolicy
import dev.phonebridge.core.auth.DeviceIdentity
import dev.phonebridge.core.auth.InMemoryTokenStore
import dev.phonebridge.core.auth.TokenManager
import dev.phonebridge.core.auth.TokenRecord
import dev.phonebridge.core.auth.TokenStore
import dev.phonebridge.core.tools.Scopes
import dev.phonebridge.core.transport.PairingDecision
import dev.phonebridge.core.transport.PairingRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PairingCoordinatorTest {
    private class FakePrompts : PairingPrompts {
        val shown = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        val nonces = mutableMapOf<String, String>()

        override fun show(entry: PendingPairing) {
            shown += entry.request.id
            nonces[entry.request.id] = entry.nonce
        }

        override fun cancel(entry: PendingPairing) {
            cancelled += entry.request.id
        }
    }

    /** Lets a test act in the middle of a token save (to provoke races). */
    private class HookedStore(private val delegate: TokenStore = InMemoryTokenStore()) : TokenStore {
        var onSave: (() -> Unit)? = null

        override fun load(): List<TokenRecord> = delegate.load()

        override fun save(tokens: List<TokenRecord>) {
            delegate.save(tokens)
            onSave?.invoke()
        }
    }

    private class Fixture(scope: TestScope, var locked: Boolean = false) {
        val prompts = FakePrompts()
        val store = HookedStore()
        val identity = DeviceIdentity.generate()
        val tokens = TokenManager(store, identity.deviceId, clock = { scope.currentTime })
        val coordinator = PairingCoordinator(
            tokens = { tokens },
            prompts = prompts,
            isLocked = { locked },
            scope = scope.backgroundScope,
            io = StandardTestDispatcher(scope.testScheduler),
            clock = { scope.currentTime },
        )

        fun request(scope: CoroutineScope, request: PairingRequest = pairingRequest()): Deferred<PairingDecision> =
            scope.async { coordinator.onPairRequest(request) }
    }

    private val assistant = PairingChoice(name = "Claude", scopes = Scopes.PRESET_ASSISTANT)

    @Test
    fun allowIssuesTheChosenTokenAndAnswersTheRelay() = runTest {
        val f = Fixture(this)
        val decision = f.request(this)
        runCurrent()
        assertEquals(listOf("p_abc123"), f.prompts.shown)
        assertEquals(listOf("p_abc123"), f.coordinator.pendingPairings.value.map { it.id })

        val choice = PairingChoice(name = " Laptop Claude ", scopes = Scopes.PRESET_READ_ONLY, policy = ConfirmPolicy.ALL_ACTIONS)
        val result = f.coordinator.approve("p_abc123", choice)
        val connected = result as PairingApproval.Connected
        assertEquals("Laptop Claude", connected.token.name)
        assertEquals(Scopes.PRESET_READ_ONLY, connected.token.scopes)
        assertEquals(ConfirmPolicy.ALL_ACTIONS, connected.token.confirmPolicy)

        val answer = decision.await() as PairingDecision.Approved
        assertEquals(connected.token.id, answer.tokenId)
        assertTrue(answer.accessToken.startsWith("pb1.${f.identity.deviceId}.${answer.tokenId}."))
        assertEquals(listOf("p_abc123"), f.prompts.cancelled)
        assertTrue(f.coordinator.pendingPairings.value.isEmpty())
        assertEquals(listOf(answer.tokenId), f.tokens.tokens.value.map { it.id })
    }

    @Test
    fun denyAnswersDeniedAndMintsNothing() = runTest {
        val f = Fixture(this)
        val decision = f.request(this)
        runCurrent()
        assertTrue(f.coordinator.deny("p_abc123"))
        assertEquals(PairingDecision.Denied("denied"), decision.await())
        assertEquals(listOf("p_abc123"), f.prompts.cancelled)
        assertTrue(f.tokens.tokens.value.isEmpty())
        assertFalse(f.coordinator.deny("p_abc123"))
    }

    @Test
    fun notificationDenyNeedsTheNonce() = runTest {
        val f = Fixture(this)
        val decision = f.request(this)
        runCurrent()
        assertFalse(f.coordinator.denyWithNonce("p_abc123", "forged"))
        assertNotNull(f.coordinator.pending("p_abc123"))
        assertTrue(f.coordinator.denyWithNonce("p_abc123", f.prompts.nonces.getValue("p_abc123")))
        assertEquals(PairingDecision.Denied("denied"), decision.await())
    }

    @Test
    fun expiryAnswersTimeoutAndRemovesThePrompt() = runTest {
        val f = Fixture(this)
        val decision = f.request(this, pairingRequest(expiresAt = 180_000))
        runCurrent()
        advanceTimeBy(180_001)
        assertEquals(PairingDecision.Denied("timeout"), decision.await())
        assertEquals(listOf("p_abc123"), f.prompts.cancelled)
        assertEquals(PairingApproval.NoLongerPending, f.coordinator.approve("p_abc123", assistant))
        assertTrue(f.tokens.tokens.value.isEmpty())
    }

    @Test
    fun relayCancellationRemovesThePrompt() = runTest {
        val f = Fixture(this)
        val decision = f.request(this)
        runCurrent()
        decision.cancel()
        runCurrent()
        assertEquals(listOf("p_abc123"), f.prompts.cancelled)
        assertNull(f.coordinator.pending("p_abc123"))
        assertEquals(PairingApproval.NoLongerPending, f.coordinator.approve("p_abc123", assistant))
    }

    @Test
    fun tokenSavedWhileTheRequestEndsIsDeletedAgain() = runTest {
        val f = Fixture(this)
        val decision = f.request(this)
        runCurrent()
        // The relay cancels (or the user denies elsewhere) while the token is being written.
        f.store.onSave = {
            f.store.onSave = null
            f.coordinator.deny("p_abc123")
        }
        assertEquals(PairingApproval.NoLongerPending, f.coordinator.approve("p_abc123", assistant))
        assertEquals(PairingDecision.Denied("denied"), decision.await())
        runCurrent()
        assertTrue("no token may exist that the relay never received", f.tokens.tokens.value.isEmpty())
    }

    @Test
    fun malformedOrExpiredRequestsAreAnsweredWithoutAPrompt() = runTest {
        val f = Fixture(this)
        assertEquals(PairingDecision.Denied("denied"), f.coordinator.onPairRequest(pairingRequest(displayCode = "??")))
        assertEquals(PairingDecision.Denied("timeout"), f.coordinator.onPairRequest(pairingRequest(expiresAt = 0)))
        assertTrue(f.prompts.shown.isEmpty())
    }

    @Test
    fun moreThanThreeOpenRequestsAreBusy() = runTest {
        val f = Fixture(this)
        val open = (1..3).map { f.request(this, pairingRequest(id = "p_$it")) }
        runCurrent()
        assertEquals(PairingDecision.Denied("busy"), f.coordinator.onPairRequest(pairingRequest(id = "p_4")))
        open.forEach { it.cancel() }
    }

    @Test
    fun lockedPhoneCannotApprove() = runTest {
        val f = Fixture(this, locked = true)
        val decision = f.request(this)
        runCurrent()
        val result = f.coordinator.approve("p_abc123", assistant) as PairingApproval.Failed
        assertTrue(result.message, result.message.contains("Unlock"))
        assertNotNull("still pending", f.coordinator.pending("p_abc123"))
        f.locked = false
        assertTrue(f.coordinator.approve("p_abc123", assistant) is PairingApproval.Connected)
        assertTrue(decision.await() is PairingDecision.Approved)
    }

    @Test
    fun choiceWithoutKnownScopesIsRefused() = runTest {
        val f = Fixture(this)
        val decision = f.request(this)
        runCurrent()
        assertTrue(f.coordinator.approve("p_abc123", assistant.copy(scopes = setOf("root"))) is PairingApproval.Failed)
        assertTrue(f.tokens.tokens.value.isEmpty())
        decision.cancel()
    }

    @Test
    fun abandonedPairingDeletesItsToken() = runTest {
        val f = Fixture(this)
        val decision = f.request(this)
        runCurrent()
        f.coordinator.approve("p_abc123", assistant)
        val tokenId = (decision.await() as PairingDecision.Approved).tokenId
        f.coordinator.onPairFinished("p_abc123", tokenId, completed = false)
        runCurrent()
        assertTrue(f.tokens.tokens.value.isEmpty())
    }

    @Test
    fun completedPairingKeepsItsTokenForGood() = runTest {
        val f = Fixture(this)
        val decision = f.request(this)
        runCurrent()
        f.coordinator.approve("p_abc123", assistant)
        val tokenId = (decision.await() as PairingDecision.Approved).tokenId
        f.coordinator.onPairFinished("p_abc123", tokenId, completed = true)
        advanceTimeBy(PairingCoordinator.EXCHANGE_GRACE_MS + 1)
        runCurrent()
        assertEquals(listOf(tokenId), f.tokens.tokens.value.map { it.id })
    }

    @Test
    fun tokenNobodyPickedUpIsDeletedAfterTheGracePeriod() = runTest {
        val f = Fixture(this)
        val decision = f.request(this)
        runCurrent()
        f.coordinator.approve("p_abc123", assistant)
        decision.await()
        advanceTimeBy(PairingCoordinator.EXCHANGE_GRACE_MS - 1)
        assertEquals(1, f.tokens.tokens.value.size)
        advanceTimeBy(2)
        runCurrent()
        assertTrue(f.tokens.tokens.value.isEmpty())
    }

    @Test
    fun tokenInUseSurvivesALostCompletion() = runTest {
        val f = Fixture(this)
        val decision = f.request(this)
        runCurrent()
        f.coordinator.approve("p_abc123", assistant)
        val answer = decision.await() as PairingDecision.Approved
        // The agent got the token and used it, but pair_complete never arrived.
        f.tokens.authenticate("Bearer ${answer.accessToken}")
        advanceTimeBy(PairingCoordinator.EXCHANGE_GRACE_MS + 1)
        runCurrent()
        assertEquals(listOf(answer.tokenId), f.tokens.tokens.value.map { it.id })
    }
}
