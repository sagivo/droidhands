package dev.phonebridge.android.pairing

import dev.phonebridge.android.graph.NotificationIds
import dev.phonebridge.core.transport.PairingDecision
import dev.phonebridge.core.transport.PairingRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** A pairing prompt that is waiting for the user. */
class PendingPairing internal constructor(
    /** Already sanitized ([PairingRules.sanitize]). */
    val request: PairingRequest,
    /** Random secret carried only by this prompt's PendingIntents; checked before denying from a notification. */
    val nonce: String,
    val notificationId: Int,
    internal val sequence: Long,
) {
    private val decision = AtomicReference<PairingDecision?>(null)
    private val decided = CompletableDeferred<PairingDecision>()
    private val approving = AtomicBoolean(false)

    /** The decision, once made (the first one wins). */
    val outcome: PairingDecision? get() = decision.get()

    /** True while a token is being issued for this request. */
    val isApproving: Boolean get() = approving.get()

    /** Records [value] if no decision was made yet. Returns false if one already was. */
    fun decide(value: PairingDecision): Boolean {
        if (!decision.compareAndSet(null, value)) return false
        decided.complete(value)
        return true
    }

    /** Suspends until [decide] is called. */
    suspend fun await(): PairingDecision = decided.await()

    /** Claims the right to issue a token, so a double tap can't issue two. */
    internal fun tryBeginApproval(): Boolean = approving.compareAndSet(false, true)

    internal fun endApproval() = approving.set(false)
}

/**
 * Thread-safe bookkeeping of pending pairing prompts (pure Kotlin, unit-tested). At most
 * [maxPending] at a time (the relay allows 3 per device); [requests] lists them oldest first.
 */
class PendingPairings(
    private val maxPending: Int = PairingRules.MAX_PENDING,
    private val random: SecureRandom = SecureRandom(),
    /**
     * Called with "at least one request is pending", synchronously and in order, whenever [requests] is
     * published: by the time [register] returns (before the prompt is shown) it has been told `true`.
     */
    private val onPendingChanged: (Boolean) -> Unit = {},
) {
    private val entries = ConcurrentHashMap<String, PendingPairing>()
    private val sequence = AtomicLong()
    private val _requests = MutableStateFlow<List<PairingRequest>>(emptyList())

    val requests: StateFlow<List<PairingRequest>> = _requests.asStateFlow()

    /** Registers [request]; null if its id is already pending or [maxPending] prompts are open (fail closed). */
    fun register(request: PairingRequest): PendingPairing? = synchronized(this) {
        if (entries.size >= maxPending || entries.containsKey(request.id)) return null
        val seq = sequence.incrementAndGet()
        val entry = PendingPairing(
            request = request,
            nonce = newNonce(),
            notificationId = NotificationIds.PAIRING_BASE + ((seq - 1) % NotificationIds.PAIRING_RANGE).toInt(),
            sequence = seq,
        )
        entries[request.id] = entry
        publish()
        entry
    }

    operator fun get(id: String): PendingPairing? = entries[id]

    /** Pending entries, oldest first. */
    fun all(): List<PendingPairing> = entries.values.sortedBy { it.sequence }

    /** Decides [id] only if [nonce] matches its prompt's nonce (constant-time comparison). */
    fun decideWithNonce(id: String, nonce: String, decision: PairingDecision): Boolean {
        val entry = entries[id] ?: return false
        if (!MessageDigest.isEqual(entry.nonce.toByteArray(), nonce.toByteArray())) return false
        return entry.decide(decision)
    }

    /** Removes [entry] if it is still the registered one for its id. */
    fun remove(entry: PendingPairing): Boolean = synchronized(this) {
        val removed = entries.remove(entry.request.id, entry)
        if (removed) publish()
        removed
    }

    /** Must hold the monitor. */
    private fun publish() {
        val pending = all().map { it.request }
        _requests.value = pending
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
