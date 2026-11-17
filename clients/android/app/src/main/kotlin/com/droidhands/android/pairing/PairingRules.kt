package com.droidhands.android.pairing

import com.droidhands.android.confirm.DisplayText
import com.droidhands.core.transport.PairingDecision
import com.droidhands.core.transport.PairingRequest

/**
 * Validation and sanitizing of `pair_request`s (from the relay or the local server) before anything
 * is shown (pure, unit-tested).
 * The relay is not trusted with what the user reads: names and hosts are cleaned of control and
 * bidi characters, and malformed requests are refused without a prompt.
 */
object PairingRules {
    /** Same cap as the relay (docs/PROTOCOL.md §5.3 step 4). */
    const val MAX_PENDING = 3

    /** Upper bound on how long a prompt stays open, whatever expiresAt says (the relay uses 180 s). */
    const val MAX_WAIT_MS = 10 * 60_000L

    const val MAX_CLIENT_NAME = 60
    const val MAX_HOST = 255
    private const val MAX_DETAIL = 120

    const val FALLBACK_CLIENT_NAME = "AI agent"
    const val FALLBACK_HOST = "unknown site"

    /** The relay's id format is `p_` + base64url; anything else could be abused in intents or ids. */
    private val ID = Regex("^[A-Za-z0-9_-]{1,128}$")

    /** 4 chars from the relay's alphabet (no 0/O, 1/I); up to 8 for forward compatibility. */
    private val DISPLAY_CODE = Regex("^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{4,8}$")

    /** [request] with every displayed field cleaned and bounded, and fallbacks for blank names. */
    fun sanitize(request: PairingRequest): PairingRequest = request.copy(
        clientName = DisplayText.clean(request.clientName, MAX_CLIENT_NAME).ifEmpty { FALLBACK_CLIENT_NAME },
        redirectHost = DisplayText.clean(request.redirectHost, MAX_HOST).lowercase().ifEmpty { FALLBACK_HOST },
        displayCode = request.displayCode.trim().uppercase(),
        remoteAddress = request.remoteAddress?.let { DisplayText.clean(it, MAX_DETAIL) }?.ifEmpty { null },
        userAgent = request.userAgent?.let { DisplayText.clean(it, MAX_DETAIL) }?.ifEmpty { null },
    )

    /**
     * [request] (already [sanitize]d) shown as [expected], the agent the user is adding in the app right now,
     * when its own name doesn't already say so: one-tap agents often register under another name (Grok Bot
     * as "Cursor", a typed-in client ID as its website). Its own name stays visible as [PairingRequest.declaredName].
     */
    fun labeled(request: PairingRequest, expected: String?): PairingRequest {
        val label = expected?.let { DisplayText.clean(it, MAX_CLIENT_NAME) }?.takeIf { it.isNotEmpty() } ?: return request
        if (request.clientName.contains(label, ignoreCase = true)) return request
        return request.copy(clientName = label, declaredName = request.clientName)
    }

    /**
     * Null if [request] (already [sanitize]d) may be shown at [now]; otherwise the immediate answer:
     * "denied" for a malformed id or code (the user could not verify it), "timeout" if already expired.
     */
    fun rejection(request: PairingRequest, now: Long): PairingDecision.Denied? = when {
        !ID.matches(request.id) -> PairingDecision.Denied(DENIED)
        !DISPLAY_CODE.matches(request.displayCode) -> PairingDecision.Denied(DENIED)
        request.expiresAt <= now -> PairingDecision.Denied(TIMEOUT)
        else -> null
    }

    /** How long to keep the prompt open: until expiresAt, at most [MAX_WAIT_MS]. */
    fun waitMillis(request: PairingRequest, now: Long): Long = (request.expiresAt - now).coerceIn(0, MAX_WAIT_MS)

    /** Protocol reasons (docs/PROTOCOL.md §5.3 step 7). */
    const val DENIED = "denied"
    const val TIMEOUT = "timeout"
    const val BUSY = "busy"
}
