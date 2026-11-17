package com.droidhands.android.pairing

import com.droidhands.core.auth.Ability
import com.droidhands.core.auth.AppAccessMode
import com.droidhands.core.auth.AppPolicy
import com.droidhands.core.auth.ConfirmPolicy
import com.droidhands.core.auth.DeviceIdentity
import com.droidhands.core.auth.GrantDraft
import com.droidhands.core.auth.GrantMode
import com.droidhands.core.auth.InMemoryTokenStore
import com.droidhands.core.auth.TokenManager
import com.droidhands.core.auth.TokenRecord
import com.droidhands.core.auth.TokenStore
import com.droidhands.core.tools.Scopes
import com.droidhands.core.transport.PairingDecision
import com.droidhands.core.transport.PairingRequest
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
        val pendingReports = mutableListOf<Boolean>()
        val coordinator = PairingCoordinator(
            tokens = { tokens },
            prompts = prompts,
            isLocked = { locked },
            scope = scope.backgroundScope,
            io = StandardTestDispatcher(scope.testScheduler),
            clock = { scope.currentTime },
            onPendingChanged = { pendingReports += it },
        )

        fun request(scope: CoroutineScope, request: PairingRequest = pairingRequest()): Deferred<PairingDecision> =
            scope.async { coordinator.onPairRequest(request) }
    }

    private val assistant = PairingChoice(name = "Claude")

    /** "Only what I choose": send messages and use apps, WhatsApp only, asking before sensitive actions. */
    private val chosen = PairingChoice(
        name = " Laptop Claude ",
        grant = GrantDraft.forNewAgent().withMode(GrantMode.CHOSEN).copy(scopes = emptySet())
            .toggle(Ability.SEND_MESSAGES).toggle(Ability.USE_APPS)
            .withAppMode(AppAccessMode.ALLOWLIST).toggleApp("com.whatsapp")
            .withAsk(true),
    )

    /** Approves a fresh request with [choice]; returns the token the relay received. */
    private suspend fun TestScope.paired(f: Fixture, id: String = "p_abc123", choice: PairingChoice = assistant): PairingDecision.Approved {
        val decision = f.request(this, pairingRequest(id = id))
        runCurrent()
        f.coordinator.approve(id, choice)
        return decision.await() as PairingDecision.Approved
    }

    @Test
    fun allowIssuesTheChosenTokenAndAnswersTheRelay() = runTest {
        val f = Fixture(this)
        val decision = f.request(this)
        runCurrent()
        assertEquals(listOf("p_abc123"), f.prompts.shown)
        assertEquals(listOf("p_abc123"), f.coordinator.pendingPairings.value.map { it.id })

        val result = f.coordinator.approve("p_abc123", chosen)
        val connected = result as PairingApproval.Connected
        assertEquals("Laptop Claude", connected.token.name)
        assertEquals(setOf(Scopes.MESSAGES_SEND, Scopes.NOTIFICATIONS_ACT, Scopes.APPS, Scopes.SCREEN_CONTROL), connected.token.scopes)
        assertEquals(AppPolicy(AppAccessMode.ALLOWLIST, setOf("com.whatsapp")), connected.token.appPolicy)
        assertEquals(ConfirmPolicy.SENSITIVE, connected.token.confirmPolicy)
        assertNull(connected.token.maxRisk)
        assertTrue("the grant never creates tool rules", connected.token.toolRules.isEmpty())

        val answer = decision.await() as PairingDecision.Approved
        assertEquals(connected.token.id, answer.tokenId)
        assertTrue(answer.accessToken.startsWith("pb1.${f.identity.deviceId}.${answer.tokenId}."))
        assertEquals(listOf("p_abc123"), f.prompts.cancelled)
        assertTrue(f.coordinator.pendingPairings.value.isEmpty())
        assertEquals(listOf(answer.tokenId), f.tokens.tokens.value.map { it.id })
    }

    @Test
    fun agentSignInIsStoredWithoutTheRelaysPlaceholderHost() = runTest {
        val f = Fixture(this)
        val browser = f.request(this, pairingRequest(id = "p_browser"))
        val agent = f.request(this, pairingRequest(id = "p_agent", clientName = "Instinct", redirectHost = "agent sign-in", viaAgent = true))
        runCurrent()
        val web = (f.coordinator.approve("p_browser", assistant) as PairingApproval.Connected).token
        val chat = (f.coordinator.approve("p_agent", assistant) as PairingApproval.Connected).token
        assertEquals("claude.ai", web.clientHost)
        assertEquals(PairingPresentation.AGENT_SIGN_IN, chat.clientHost)
        assertTrue(browser.await() is PairingDecision.Approved)
        assertTrue(agent.await() is PairingDecision.Approved)
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
        val nothing = assistant.copy(grant = assistant.grant.withMode(GrantMode.CHOSEN).copy(scopes = setOf("root")))
        val failed = f.coordinator.approve("p_abc123", nothing) as PairingApproval.Failed
        assertEquals("Choose at least one thing it can do.", failed.message)
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
    fun pendingRequestsAreReportedBeforeThePromptAndClearedAfter() = runTest {
        val f = Fixture(this)
        val decision = f.request(this)
        runCurrent()
        assertEquals("guard up while the prompt is open", listOf(true), f.pendingReports)
        f.coordinator.deny("p_abc123")
        decision.await()
        assertEquals(false, f.pendingReports.last())
    }

    @Test
    fun everythingIsTheDefaultAndNeverAsks() = runTest {
        val f = Fixture(this)
        val answer = paired(f, choice = PairingChoice.defaults(pairingRequest()))
        val token = f.tokens.tokens.value.single { it.id == answer.tokenId }
        assertEquals(Scopes.ALL, token.scopes)
        assertEquals(AppPolicy(), token.appPolicy)
        assertEquals(ConfirmPolicy.NEVER, token.confirmPolicy)
        assertNull(token.maxRisk)
        assertEquals("pairing", token.origin)
    }

    @Test
    fun theGrantIsSavedWithTheTokenInOneWrite() = runTest {
        val f = Fixture(this)
        val decision = f.request(this)
        runCurrent()
        var saves = 0
        f.store.onSave = { saves++ }
        assertTrue(f.coordinator.approve("p_abc123", chosen) is PairingApproval.Connected)
        assertEquals("nobody ever holds the token without its app limits", 1, saves)
        decision.await()
    }

    @Test
    fun failingToSaveHandsNothingOut() = runTest {
        val f = Fixture(this)
        val decision = f.request(this)
        runCurrent()
        f.store.onSave = { throw java.io.IOException("disk full") }
        assertTrue(f.coordinator.approve("p_abc123", chosen) is PairingApproval.Failed)
        f.store.onSave = null
        assertTrue(f.tokens.tokens.value.isEmpty())
        assertNotNull("still pending: the user can try again", f.coordinator.pending("p_abc123"))
        decision.cancel()
    }

    @Test
    fun abandonedNoticeForAnotherTokenIsIgnored() = runTest {
        val f = Fixture(this)
        val manual = f.tokens.issue("Laptop", Scopes.PRESET_ASSISTANT, ConfirmPolicy.SENSITIVE, ttlMillis = null)
        val answer = paired(f)
        // A relay naming tokens it has seen (but were not issued for this pairing) deletes nothing.
        f.coordinator.onPairFinished("p_abc123", manual.record.id, completed = false)
        f.coordinator.onPairFinished("p_other", answer.tokenId, completed = false)
        f.coordinator.onPairFinished("p_other", manual.record.id, completed = false)
        runCurrent()
        assertEquals(setOf(manual.record.id, answer.tokenId), f.tokens.tokens.value.map { it.id }.toSet())
        // The real notice still works.
        f.coordinator.onPairFinished("p_abc123", answer.tokenId, completed = false)
        runCurrent()
        assertEquals(listOf(manual.record.id), f.tokens.tokens.value.map { it.id })
    }

    @Test
    fun abandonedAfterCompletionIsIgnored() = runTest {
        val f = Fixture(this)
        val answer = paired(f)
        f.coordinator.onPairFinished("p_abc123", answer.tokenId, completed = true)
        f.coordinator.onPairFinished("p_abc123", answer.tokenId, completed = false)
        runCurrent()
        assertEquals(listOf(answer.tokenId), f.tokens.tokens.value.map { it.id })
    }

    @Test
    fun completionForAnotherPairingDoesNotStopTheCleanup() = runTest {
        val f = Fixture(this)
        val answer = paired(f)
        f.coordinator.onPairFinished("p_other", answer.tokenId, completed = true)
        advanceTimeBy(PairingCoordinator.EXCHANGE_GRACE_MS + 1)
        runCurrent()
        assertTrue("the unused token is still cleaned up", f.tokens.tokens.value.isEmpty())
    }

    @Test
    fun abandonedNoticeKeepsATokenAlreadyInUse() = runTest {
        val f = Fixture(this)
        val answer = paired(f)
        f.tokens.authenticate("Bearer ${answer.accessToken}")
        f.coordinator.onPairFinished("p_abc123", answer.tokenId, completed = false)
        runCurrent()
        assertEquals(listOf(answer.tokenId), f.tokens.tokens.value.map { it.id })
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
