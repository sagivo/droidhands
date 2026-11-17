package com.droidhands.core.transport

/**
 * A one-tap pairing request, forwarded by the relay (docs/PROTOCOL.md §5.3) or started on the phone's
 * own local server ([com.droidhands.core.oauth.LocalOAuth], §4.1). Ids of both kinds are `p_` +
 * random base64url and never collide.
 */
data class PairingRequest(
    val id: String,
    /** Registered OAuth client_name (sanitized by the relay or local server), e.g. "Claude". */
    val clientName: String,
    /**
     * Host of the OAuth redirect URI, e.g. "claude.ai" or "localhost:33418". For [viaAgent] requests
     * there is no website: the relay sends the placeholder "agent sign-in", which isn't shown as a host.
     */
    val redirectHost: String,
    /** 4-char code also shown in the user's browser, or by the agent in chat when [viaAgent]. */
    val displayCode: String,
    val remoteAddress: String?,
    val userAgent: String?,
    /**
     * Epoch millis after which the relay (or local server) gives up. In a request handed to [PairingHandler.onPairRequest]
     * this is on the PHONE's clock (`System.currentTimeMillis()` in production): the relay's `expiresAt`
     * (relay time) converted with the clock offset from the relay's `welcome.serverTime`, and at most
     * 10 minutes from now; local pairings use the phone's clock directly. It is when the prompt is
     * cancelled, so compare it with the local clock for countdowns and expiry checks.
     */
    val expiresAt: Long,
    /**
     * True for an agent sign-in (`"flow":"device"`, OAuth device authorization, docs/PROTOCOL.md §5.3):
     * the agent started it without a browser and showed the user the code in its chat. False for the
     * browser flow (relay or local server).
     */
    val viaAgent: Boolean = false,
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
     * `pair_cancel`, at [PairingRequest.expiresAt], when the relay connection drops or the local
     * server stops — the implementation must then remove its prompt.
     */
    suspend fun onPairRequest(request: PairingRequest): PairingDecision

    /**
     * `pair_complete` ([completed] = true) or `pair_abandoned` ([completed] = false), or the local
     * server's equivalent (token delivered / code expired, failed exchange, response not delivered).
     * On abandon the implementation deletes [tokenId] (if non-null) because no agent ever received it.
     */
    fun onPairFinished(id: String, tokenId: String?, completed: Boolean)
}
