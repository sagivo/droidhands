package com.droidhands.android.pairing

import com.droidhands.core.transport.PairingRequest

/** A well-formed relay pair_request expiring at [expiresAt]; [viaAgent] for an agent sign-in (`"flow":"device"`). */
internal fun pairingRequest(
    id: String = "p_abc123",
    clientName: String = "Claude",
    redirectHost: String = "claude.ai",
    displayCode: String = "K7Q2",
    expiresAt: Long = 181_000L,
    remoteAddress: String? = "203.0.113.7",
    userAgent: String? = "Mozilla/5.0",
    viaAgent: Boolean = false,
) = PairingRequest(
    id = id,
    clientName = clientName,
    redirectHost = redirectHost,
    displayCode = displayCode,
    remoteAddress = remoteAddress,
    userAgent = userAgent,
    expiresAt = expiresAt,
    viaAgent = viaAgent,
)
