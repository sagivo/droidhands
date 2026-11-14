package dev.phonebridge.core.transport

import dev.phonebridge.core.transport.RelayProtocol.string
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * One-tap pairing frames on one relay connection (docs/PROTOCOL.md §5.3). Thread-safe.
 *
 * * `pair_request` → [PairingHandler.onPairRequest] in a coroutine; its decision is sent back as
 *   `pair_response`. Without a handler, or with too many prompts open, the answer is
 *   `{approved:false, reason:"busy"}` right away.
 * * The prompt is cancelled, and nothing is sent, on `pair_cancel`, at the request's `expiresAt`
 *   (relay time, see [relayNow]), or when the connection ends ([cancelAll]).
 * * An approved token that cannot be delivered (connection closed, or the prompt was cancelled as
 *   the user tapped Allow) is reported to [PairingHandler.onPairFinished] as abandoned, so the
 *   platform deletes a token no agent will ever receive.
 * * `pair_complete` / `pair_abandoned` → [PairingHandler.onPairFinished] (off the socket thread).
 *
 * @param send sends a text frame on this connection; false if it could not be queued.
 * @param launch starts a coroutine for this connection (null once the connection is stale).
 * @param relayNow the relay's clock (local clock corrected by the welcome's `serverTime`).
 */
internal class RelayPairings(
    private val handler: PairingHandler?,
    private val send: (String) -> Boolean,
    private val launch: (CoroutineStart, suspend CoroutineScope.() -> Unit) -> Job?,
    private val relayNow: () -> Long,
) {
    private val pending = ConcurrentHashMap<String, Job>()

    fun onRequest(frame: JsonObject) {
        val request = RelayProtocol.pairingRequest(frame, relayNow()) ?: return
        val id = request.id
        if (handler == null) {
            send(RelayProtocol.pairResponse(id, PairingDecision.Denied(REASON_BUSY)).toString())
            return
        }
        if (pending.containsKey(id)) return // Duplicate frame: the first prompt is still open.
        if (pending.size >= MAX_PENDING) {
            send(RelayProtocol.pairResponse(id, PairingDecision.Denied(REASON_BUSY)).toString())
            return
        }
        val remainingMs = (request.expiresAt - relayNow()).coerceAtMost(MAX_PROMPT_MS)
        if (remainingMs <= 0) return // Already expired at the relay: it reports that itself.

        val job = launch(CoroutineStart.LAZY) { prompt(handler, request, remainingMs) } ?: return
        if (pending.putIfAbsent(id, job) != null) {
            job.cancel()
            return
        }
        job.invokeOnCompletion { pending.remove(id, job) }
        job.start()
    }

    fun onCancel(frame: JsonObject) {
        val id = frame.string("id") ?: return
        pending.remove(id)?.cancel()
    }

    /** `pair_complete` ([completed] = true) or `pair_abandoned`. */
    fun onFinished(frame: JsonObject, completed: Boolean) {
        val id = frame.string("id") ?: return
        val tokenId = frame.string("tokenId")?.takeIf { it.isNotBlank() }
        if (!completed) pending.remove(id)?.cancel()
        val h = handler ?: return
        launch(CoroutineStart.DEFAULT) { finished(h, id, tokenId, completed) }
    }

    /** Cancels every open prompt (the connection ended). */
    fun cancelAll() {
        val jobs = pending.values.toList()
        pending.clear()
        jobs.forEach { it.cancel() }
    }

    /** Open prompts (tests and diagnostics). */
    val pendingCount: Int get() = pending.size

    private suspend fun prompt(handler: PairingHandler, request: PairingRequest, timeoutMs: Long) {
        val decision = try {
            withTimeoutOrNull(timeoutMs) { handler.onPairRequest(request) } ?: return
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PairingDecision.Denied(REASON_BUSY)
        }
        val stillWanted = currentCoroutineContext().isActive
        val delivered = stillWanted && send(RelayProtocol.pairResponse(request.id, decision).toString())
        if (!delivered && decision is PairingDecision.Approved) finished(handler, request.id, decision.tokenId, completed = false)
    }

    private fun finished(handler: PairingHandler, id: String, tokenId: String?, completed: Boolean) {
        try {
            handler.onPairFinished(id, tokenId, completed)
        } catch (e: Exception) {
            // The platform's bookkeeping failed; nothing useful to tell the relay.
        }
    }

    companion object {
        const val REASON_BUSY = "busy"

        /** The relay allows 3 pending pairings per device; more here means something is wrong. */
        const val MAX_PENDING = 5

        /** Upper bound on one prompt, whatever `expiresAt` says (the relay's TTL is 180 s). */
        const val MAX_PROMPT_MS = 10 * 60_000L
    }
}
