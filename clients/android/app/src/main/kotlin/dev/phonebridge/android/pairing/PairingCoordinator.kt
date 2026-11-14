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
 * [PairingHandler] that the relay client calls for `pair_request` (and the local server's
 * [dev.phonebridge.core.oauth.LocalOAuth] for local pairings, §4.1), shows the prompt through
 * [prompts], and mints the agent's token when the user allows it.
 *
 * * [onPairRequest] suspends until the user decides, the request expires, or the relay cancels
 *   it (coroutine cancellation); the prompt is removed in every case. Malformed or already expired
 *   requests are refused without a prompt, and beyond [PairingRules.MAX_PENDING] open prompts the
 *   answer is "busy".
 * * [approve] issues the token (name, scopes, risk cap and confirmation policy chosen by the user,
 *   `origin = "pairing"`, `clientHost` = the redirect host) only while the request is open and the
 *   phone is unlocked. If the request ends while the token is being saved, the token is deleted
 *   again, so no token exists that the relay never received.
 * * `pair_abandoned` ([onPairFinished] with completed = false) deletes the issued token if it is
 *   still unused. As a safety net for a lost `pair_complete`/`pair_abandoned` (e.g. the relay
 *   connection dropped), a paired token that is still unused [exchangeGraceMs] after approval is
 *   deleted too. Both notices are honoured only for the token issued for that pairing id, and only
 *   until it was picked up or cleaned up: the relay sees every token id it routes and must not be
 *   able to delete other agents' tokens.
 * * [onPendingChanged] hears whether any request is pending, before its prompt is shown (it feeds
 *   the guard that keeps screen control off the screen while an approval is possible).
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
    onPendingChanged: (Boolean) -> Unit = {},
) : PairingHandler {
    private val registry = PendingPairings(onPendingChanged = onPendingChanged)

    /** A delivered token waiting for `pair_complete`/`pair_abandoned`, and the job deleting it if unused. */
    private class Exchange(val pairingId: String, val job: Job)

    /** tokenId → the pairing it was issued for. */
    private val awaitingExchange = ConcurrentHashMap<String, Exchange>()

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
                if (final == answer) watchExchange(clean.id, final.tokenId) else discardToken(final.tokenId)
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
        // Only the token issued for this pairing, and only once.
        val exchange = awaitingExchange[tokenId]
        if (exchange == null || exchange.pairingId != id || !awaitingExchange.remove(tokenId, exchange)) {
            log("Ignored a pairing notice for a token that was not issued for that pairing", null)
            return
        }
        exchange.job.cancel()
        if (!completed) scope.launch(io) { deleteIfUnused(tokenId) }
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
            return saveFailed(e)
        }
        // Capped before the token is handed out, so nobody ever holds it without the cap.
        val record = try {
            choice.maxRisk?.let { cap -> tokens().update(issued.record.id) { it.copy(maxRisk = cap) } } ?: issued.record
        } catch (e: Exception) {
            log("Limiting the paired agent's token failed", e)
            deleteNow(issued.record.id)
            return saveFailed(e)
        }
        if (entry.decide(PairingDecision.Approved(issued.plaintext, record.id))) {
            return PairingApproval.Connected(record)
        }
        // The request ended while the token was being saved: nobody will ever receive it.
        deleteNow(record.id)
        return PairingApproval.NoLongerPending
    }

    private fun saveFailed(e: Exception) =
        PairingApproval.Failed("Couldn't save the new agent (${e.message ?: e.javaClass.simpleName}). Try again.")

    private fun watchExchange(pairingId: String, tokenId: String) {
        lateinit var exchange: Exchange
        // Lazy: in the map before it can run (and remove itself).
        val job = scope.launch(io, start = CoroutineStart.LAZY) {
            delay(exchangeGraceMs)
            if (awaitingExchange.remove(tokenId, exchange)) deleteIfUnused(tokenId)
        }
        exchange = Exchange(pairingId, job)
        awaitingExchange.put(tokenId, exchange)?.job?.cancel()
        job.start()
    }

    private fun discardToken(tokenId: String) {
        awaitingExchange.remove(tokenId)?.job?.cancel()
        scope.launch(io) { deleteNow(tokenId) }
    }

    /** Deletes a paired token nobody has used yet; one an agent already uses stays. */
    private fun deleteIfUnused(tokenId: String) {
        val record = tokens().tokens.value.firstOrNull { it.id == tokenId } ?: return
        if (record.origin != ORIGIN_PAIRING || record.lastUsedAt != null) return
        log("Deleting a paired token the agent never picked up", null)
        deleteNow(tokenId)
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

        /** [dev.phonebridge.android.tools.support.ApprovalGuard] source: a pairing request is pending. */
        const val GUARD_SOURCE = "pairing"

        /**
         * How long a paired token may stay unused without `pair_complete` before it is deleted:
         * the relay's code TTL (120 s) after the prompt's 180 s, with a wide margin.
         */
        const val EXCHANGE_GRACE_MS = 15 * 60_000L
    }
}
