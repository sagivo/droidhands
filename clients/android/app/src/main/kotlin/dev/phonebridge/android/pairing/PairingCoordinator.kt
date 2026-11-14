package dev.phonebridge.android.pairing

import dev.phonebridge.core.auth.TokenManager
import dev.phonebridge.core.auth.TokenRecord
import dev.phonebridge.core.tools.Scopes
import dev.phonebridge.core.transport.PairingDecision
import dev.phonebridge.core.transport.PairingHandler
import dev.phonebridge.core.transport.PairingRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/** Shows and removes the user-facing prompt for a pending pairing (production: [PairingNotifications]). */
interface PairingPrompts {
    fun show(entry: PendingPairing)

    fun cancel(entry: PendingPairing)
}

/** Result of [PairingCoordinator.approve]. */
sealed interface PairingApproval {
    /** A token was issued and handed to the relay. */
    data class Connected(val token: TokenRecord) : PairingApproval

    /** The request expired, was cancelled by the relay, or was already decided. */
    data object NoLongerPending : PairingApproval

    /** Nothing changed; [message] tells the user what to do. */
    data class Failed(val message: String) : PairingApproval
}

/**
 * One-tap agent pairing on the phone (docs/PROTOCOL.md §5.3 step 7): implements the core
 * [PairingHandler] that the relay client calls for `pair_request`, shows the prompt through
 * [prompts], and mints the agent's token when the user allows it.
 *
 * * [onPairRequest] suspends until the user decides, the request expires, or the relay cancels
 *   it (coroutine cancellation); the prompt is removed in every case. Malformed or already expired
 *   requests are refused without a prompt, and beyond [PairingRules.MAX_PENDING] open prompts the
 *   answer is "busy".
 * * [approve] issues the token (name, scopes and confirmation policy chosen by the user,
 *   `origin = "pairing"`, `clientHost` = the redirect host) only while the request is open and the
 *   phone is unlocked. If the request ends while the token is being saved, the token is deleted
 *   again, so no token exists that the relay never received.
 * * `pair_abandoned` ([onPairFinished] with completed = false) deletes the issued token. As a
 *   safety net for a lost `pair_complete`/`pair_abandoned` (e.g. the relay connection dropped), a
 *   paired token that is still unused [exchangeGraceMs] after approval is deleted too.
 *
 * Thread-safe. Token store I/O runs on [io], inside [scope], so a cancelled caller (an activity
 * being destroyed) can never interrupt an approval half-way.
 */
class PairingCoordinator(
    private val tokens: () -> TokenManager,
    private val prompts: PairingPrompts,
    /** True while the keyguard is showing: approvals then fail (Allow requires an unlocked phone). */
    private val isLocked: () -> Boolean,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val exchangeGraceMs: Long = EXCHANGE_GRACE_MS,
    private val log: (message: String, error: Throwable?) -> Unit = { _, _ -> },
) : PairingHandler {
    private val registry = PendingPairings()

    /** tokenId → job deleting the token if the agent never picks it up. */
    private val awaitingExchange = ConcurrentHashMap<String, Job>()

    /** Open pairing requests, oldest first, already sanitized (for the Home card and [PairingActivity]). */
    val pendingPairings: StateFlow<List<PairingRequest>> get() = registry.requests

    /** The open request with [id], or null. */
    fun pending(id: String): PairingRequest? = registry[id]?.request

    /** True while a token is being issued for [id] (the UI shows progress). */
    fun isApproving(id: String): Boolean = registry[id]?.isApproving == true

    override suspend fun onPairRequest(request: PairingRequest): PairingDecision {
        val clean = PairingRules.sanitize(request)
        PairingRules.rejection(clean, clock())?.let { return it }
        val entry = registry.register(clean) ?: return PairingDecision.Denied(PairingRules.BUSY)
        var answer: PairingDecision? = null
        try {
            try {
                prompts.show(entry)
            } catch (e: RuntimeException) {
                // Still pending: the Home card lists it, and it times out like any other request.
                log("Could not show the pairing prompt", e)
            }
            answer = withTimeoutOrNull(PairingRules.waitMillis(clean, clock())) { entry.await() }
                ?: PairingDecision.Denied(PairingRules.TIMEOUT)
            return answer
        } finally {
            // Close the request first, so an approve() still saving its token can't succeed after this.
            entry.decide(PairingDecision.Denied(PairingRules.TIMEOUT))
            val final = entry.outcome
            if (final is PairingDecision.Approved) {
                if (final == answer) watchExchange(final.tokenId) else discardToken(final.tokenId)
            }
            registry.remove(entry)
            try {
                prompts.cancel(entry)
            } catch (e: RuntimeException) {
                log("Could not remove the pairing prompt", e)
            }
        }
    }

    override fun onPairFinished(id: String, tokenId: String?, completed: Boolean) {
        // A finished flow has nothing left to decide; closes a prompt the relay forgot to cancel.
        registry[id]?.decide(PairingDecision.Denied(PairingRules.TIMEOUT))
        if (tokenId == null) return
        awaitingExchange.remove(tokenId)?.cancel()
        if (!completed) discardToken(tokenId)
    }

    /**
     * Approves [id] with [choice]: issues the token and answers the relay. Requires an unlocked phone.
     * Safe to call from the main thread; the work continues even if the caller is cancelled.
     */
    suspend fun approve(id: String, choice: PairingChoice): PairingApproval {
        val entry = registry[id] ?: return PairingApproval.NoLongerPending
        if (isLocked()) return PairingApproval.Failed("Unlock your phone, then tap Allow again.")
        val scopes = choice.scopes.filterTo(LinkedHashSet()) { it in Scopes.ALL }
        if (scopes.isEmpty()) return PairingApproval.Failed("Choose what this agent may do.")
        if (!entry.tryBeginApproval()) return PairingApproval.Failed("Already connecting this agent…")
        return scope.async(io) {
            try {
                issueFor(entry, choice.copy(scopes = scopes))
            } finally {
                entry.endApproval()
            }
        }.await()
    }

    /** Denies [id] (in-app UI). Returns false if it is no longer pending. */
    fun deny(id: String): Boolean = registry[id]?.decide(PairingDecision.Denied(PairingRules.DENIED)) == true

    /** Denies [id] only if [nonce] matches its notification ([PairingReceiver]). */
    fun denyWithNonce(id: String, nonce: String): Boolean =
        registry.decideWithNonce(id, nonce, PairingDecision.Denied(PairingRules.DENIED))

    private fun issueFor(entry: PendingPairing, choice: PairingChoice): PairingApproval {
        val request = entry.request
        if (entry.outcome != null || clock() >= request.expiresAt) return PairingApproval.NoLongerPending
        val issued = try {
            tokens().issue(
                name = PairingPresentation.tokenName(choice.name, request),
                scopes = choice.scopes,
                confirmPolicy = choice.policy,
                ttlMillis = null,
                origin = ORIGIN_PAIRING,
                clientHost = request.redirectHost,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Issuing the paired agent's token failed", e)
            return PairingApproval.Failed("Couldn't save the new agent (${e.message ?: e.javaClass.simpleName}). Try again.")
        }
        if (entry.decide(PairingDecision.Approved(issued.plaintext, issued.record.id))) {
            return PairingApproval.Connected(issued.record)
        }
        // The request ended while the token was being saved: nobody will ever receive it.
        deleteNow(issued.record.id)
        return PairingApproval.NoLongerPending
    }

    private fun watchExchange(tokenId: String) {
        // Lazy: in the map before it can run (and remove itself).
        val job = scope.launch(io, start = CoroutineStart.LAZY) {
            delay(exchangeGraceMs)
            awaitingExchange.remove(tokenId)
            val record = tokens().tokens.value.firstOrNull { it.id == tokenId }
            if (record != null && record.lastUsedAt == null) {
                log("Deleting a paired token the agent never picked up", null)
                deleteNow(tokenId)
            }
        }
        awaitingExchange.put(tokenId, job)?.cancel()
        job.start()
    }

    private fun discardToken(tokenId: String) {
        awaitingExchange.remove(tokenId)?.cancel()
        scope.launch(io) { deleteNow(tokenId) }
    }

    private fun deleteNow(tokenId: String) {
        try {
            tokens().delete(tokenId)
        } catch (e: Exception) {
            log("Could not delete an unused paired token", e)
        }
    }

    companion object {
        const val ORIGIN_PAIRING = "pairing"

        /**
         * How long a paired token may stay unused without `pair_complete` before it is deleted:
         * the relay's code TTL (120 s) after the prompt's 180 s, with a wide margin.
         */
        const val EXCHANGE_GRACE_MS = 15 * 60_000L
    }
}
