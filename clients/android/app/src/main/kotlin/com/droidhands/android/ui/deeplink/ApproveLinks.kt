package com.droidhands.android.ui.deeplink

import com.droidhands.core.transport.PairingRequest
import java.net.URI
import java.net.URISyntaxException

/**
 * `droidhands://approve[?code=K7Q2]`, the button on the relay's `/approve` page (docs/PROTOCOL.md §5.6):
 * an agent signing in sends the person that page, and the link opens the matching connection prompt.
 * It only opens the prompt; the person still taps Allow there. Pure (unit-tested).
 */
object ApproveLinks {
    /** The link's code, uppercased; "" for a link without one; null if [uri] isn't an approve link. */
    fun codeOf(uri: String?): String? {
        val parsed = try {
            URI(uri ?: return null)
        } catch (_: URISyntaxException) {
            return null
        }
        if (!parsed.scheme.equals("droidhands", ignoreCase = true) || !parsed.host.equals("approve", ignoreCase = true)) return null
        val code = parsed.rawQuery.orEmpty().split('&')
            .firstOrNull { it.startsWith("code=") }?.removePrefix("code=").orEmpty()
        return code.uppercase().takeIf { CODE.matches(it) }.orEmpty()
    }

    /** The open request the link is for: the one showing [code], else (no code) the newest. */
    fun pick(pairings: List<PairingRequest>, code: String, now: Long): PairingRequest? {
        val live = pairings.filter { it.expiresAt > now }
        return if (code.isEmpty()) live.lastOrNull() else live.lastOrNull { it.displayCode.equals(code, ignoreCase = true) }
    }

    private val CODE = Regex("[A-Z0-9]{4}")
}
