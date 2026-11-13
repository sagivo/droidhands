package dev.phonebridge.core.transport

/** A one-tap pairing request forwarded by the relay (docs/PROTOCOL.md §5.3). */
data class PairingRequest(
    val id: String,
    /** Registered OAuth client_name (sanitized by the relay), e.g. "Claude". */
    val clientName: String,
    /** Host of the OAuth redirect URI, e.g. "claude.ai" or "localhost:33418". */
    val redirectHost: String,
    /** 4-char code also shown in the user's browser. */
    val displayCode: String,
    val remoteAddress: String?,
    val userAgent: String?,
    /** Epoch millis after which the relay gives up. */
    val expiresAt: Long,
)

sealed interface PairingDecision {
    data class Approved(val accessToken: String, val tokenId: String) : PairingDecision

    /** [reason]: "denied", "timeout" or "busy". */
    data class Denied(val reason: String) : PairingDecision
}

/** Implemented by the platform (approval UI + token issuing). */
interface PairingHandler {
    /**
     * Shows the approval prompt and suspends until the user decides. The coroutine is cancelled on
     * `pair_cancel`, at [PairingRequest.expiresAt], or when the relay connection drops — the
     * implementation must then remove its prompt.
     */
    suspend fun onPairRequest(request: PairingRequest): PairingDecision

    /**
     * `pair_complete` ([completed] = true) or `pair_abandoned` ([completed] = false). On abandon the
     * implementation deletes [tokenId] (if non-null) because no agent ever received it.
     */
    fun onPairFinished(id: String, tokenId: String?, completed: Boolean)
}
