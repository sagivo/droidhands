package com.droidhands.android.ui.connect

import com.droidhands.android.ui.agents.AgentPresentation
import com.droidhands.core.auth.TokenRecord
import com.droidhands.core.transport.PairingRequest

/** Pure helpers for the "Waiting for your agent…" indicator and pairing cards (unit-tested). */
object PairingWatch {
    /**
     * The first agent that connected through one-tap pairing since the page opened: a pairing
     * token whose id wasn't in [baselineIds] and that is still usable. Null while waiting.
     */
    fun newlyPaired(tokens: List<TokenRecord>, baselineIds: Set<String>): TokenRecord? =
        tokens.firstOrNull { it.origin == AgentPresentation.ORIGIN_PAIRING && it.id !in baselineIds && !it.revoked }

    /** "Claude (claude.ai) wants to connect"; an agent sign-in has no host: "Instinct wants to connect". */
    fun title(request: PairingRequest): String {
        val name = request.clientName.trim().ifEmpty { "An AI agent" }
        val host = if (request.viaAgent) "" else request.redirectHost.trim()
        return if (host.isEmpty() || host.equals(name, ignoreCase = true)) "$name wants to connect" else "$name ($host) wants to connect"
    }

    /** Under the code: allow only if you are connecting right now and the code matches the browser's or the agent's. */
    fun cardHint(request: PairingRequest): String =
        "Only allow it if you are connecting this agent right now and " +
            if (request.viaAgent) "your agent showed you the same code." else "your browser shows the same code."

    /** "2:31 left", "Expired" — whole seconds, never negative. */
    fun timeLeft(expiresAt: Long, now: Long): String {
        val seconds = (expiresAt - now + 999) / 1000
        if (seconds <= 0) return "Expired"
        return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')} left"
    }

    /** Spaced out so TalkBack reads the code letter by letter ("K 7 Q 2"). */
    fun spokenCode(code: String): String = code.toCharArray().joinToString(" ")
}
