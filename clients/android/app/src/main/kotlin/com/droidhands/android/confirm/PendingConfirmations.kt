package com.droidhands.android.confirm

import com.droidhands.android.graph.NotificationIds
import com.droidhands.core.confirm.ConfirmationRequest
import com.droidhands.core.confirm.ConfirmationResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** A confirmation prompt that is waiting for the user. */
class PendingConfirmation internal constructor(
    val request: ConfirmationRequest,
    /** Random secret carried only by this prompt's PendingIntents; checked before resolving. */
    val nonce: String,
    val notificationId: Int,
    val createdAt: Long,
    /** When the server stops waiting (createdAt + timeout). */
    val expiresAt: Long,
    internal val result: CompletableDeferred<ConfirmationResult>,
    internal val sequence: Long,
)

/**
 * Thread-safe bookkeeping of pending confirmations (pure Kotlin, unit-tested). The first decision
 * wins; later or unknown ones are ignored. [requests] lists pending requests oldest first.
 */
class PendingConfirmations(
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    /**
     * Called with [hasPending]'s new value, synchronously and in order, whenever it is published: by the
     * time [register] returns (before the prompt is shown) it has been told `true`.
     */
    private val onPendingChanged: (Boolean) -> Unit = {},
) {
    private val entries = ConcurrentHashMap<String, PendingConfirmation>()
    private val sequence = AtomicLong()
    private val _requests = MutableStateFlow<List<ConfirmationRequest>>(emptyList())
    private val _hasPending = MutableStateFlow(false)

    val requests: StateFlow<List<ConfirmationRequest>> = _requests.asStateFlow()

    /** True while at least one request waits for the user (published together with [requests]). */
    val hasPending: StateFlow<Boolean> = _hasPending.asStateFlow()

    /** Registers [request]; returns null (fail closed) if a request with the same id is already pending. */
    fun register(request: ConfirmationRequest, timeoutMs: Long): PendingConfirmation? {
        val seq = sequence.incrementAndGet()
        val now = clock()
        val entry = PendingConfirmation(
            request = request,
            nonce = newNonce(),
            notificationId = NotificationIds.CONFIRM_BASE + ((seq - 1) % NotificationIds.CONFIRM_RANGE).toInt(),
            createdAt = now,
            expiresAt = now + timeoutMs,
            result = CompletableDeferred(),
            sequence = seq,
        )
        if (entries.putIfAbsent(request.id, entry) != null) return null
        publish()
        return entry
    }

    operator fun get(id: String): PendingConfirmation? = entries[id]

    /** Pending entries, oldest first. */
    fun all(): List<PendingConfirmation> = entries.values.sortedBy { it.sequence }

    /** Completes [id] with the user's decision. False if unknown or already decided. */
    fun resolve(id: String, approved: Boolean): Boolean = entries[id]?.let { complete(it, approved) } ?: false

    /** Like [resolve], but only if [nonce] matches the prompt's nonce (constant-time comparison). */
    fun resolveWithNonce(id: String, nonce: String, approved: Boolean): Boolean {
        val entry = entries[id] ?: return false
        if (!MessageDigest.isEqual(entry.nonce.toByteArray(), nonce.toByteArray())) return false
        return complete(entry, approved)
    }

    /** Denies every pending request (e.g. the user paused access). Returns how many were denied. */
    fun denyAll(): Int = all().count { complete(it, approved = false) }

    /** Removes [entry] if it is still the registered one for its id. */
    fun remove(entry: PendingConfirmation): Boolean {
        val removed = entries.remove(entry.request.id, entry)
        if (removed) publish()
        return removed
    }

    private fun complete(entry: PendingConfirmation, approved: Boolean): Boolean =
        entry.result.complete(if (approved) ConfirmationResult.APPROVED else ConfirmationResult.DENIED)

    // Recomputed from the map under a lock, so the last publisher always reflects the latest state.
    private fun publish() = synchronized(this) {
        val pending = all().map { it.request }
        _requests.value = pending
        _hasPending.value = pending.isNotEmpty()
        onPendingChanged(pending.isNotEmpty())
    }

    private fun newNonce(): String {
        val bytes = ByteArray(NONCE_BYTES).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private companion object {
        const val NONCE_BYTES = 16
    }
}
