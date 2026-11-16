package dev.phonebridge.android.pairing

import dev.phonebridge.android.confirm.DisplayText
import dev.phonebridge.core.auth.GrantDraft
import dev.phonebridge.core.transport.PairingRequest
import java.util.Locale

/** What the user chose for a new agent on the grant screen. */
data class PairingChoice(
    /** Agent name shown in PhoneBridge; blank means the client's own name. */
    val name: String,
    /** Its access: Everything, or chosen apps and abilities; approvals (docs/SECURITY.md "Access"). */
    val grant: GrantDraft = GrantDraft.forNewAgent(),
) {
    companion object {
        /** The client's name, Everything, no prompts (what the screen starts with). */
        fun defaults(request: PairingRequest) = PairingChoice(request.clientName)
    }
}

/** Pure text for the pairing prompt (notification, [PairingActivity], Home card). */
object PairingPresentation {
    const val CODE_HINT = "Make sure your browser shows the same code."

    const val ALLOW_HINT = "Only allow if you just started connecting this agent yourself."

    /** "Claude (claude.ai) wants to connect · code K7Q2". */
    fun notificationTitle(request: PairingRequest): String =
        "${request.clientName} (${request.redirectHost}) wants to connect · code ${request.displayCode}"

    /** Collapsed text: repeats the code first, in case the title is cut off. */
    fun notificationText(request: PairingRequest): String = "Code ${request.displayCode} · Tap to review and choose its access"

    fun notificationBigText(request: PairingRequest): String = buildString {
        append("Check that your browser shows code ").append(request.displayCode)
        append(", then tap to give ").append(request.clientName).append(" access to this phone.")
        originLine(request)?.let { append("\n\n").append(it) }
    }

    /** "Requested from 203.0.113.7", or null when the relay didn't say. */
    fun originLine(request: PairingRequest): String? = request.remoteAddress?.let { "Requested from $it" }

    /** "K 7 Q 2", so screen readers spell the code instead of reading it as a word. */
    fun spokenCode(code: String): String = code.toList().joinToString(" ")

    /** The agent name to store: the edited [input], or the client's name when blank. */
    fun tokenName(input: String, request: PairingRequest): String =
        DisplayText.clean(input, PairingRules.MAX_CLIENT_NAME).ifEmpty { request.clientName }
            .ifEmpty { PairingRules.FALLBACK_CLIENT_NAME }

    /** Whole seconds left until [expiresAt], rounded up, never negative. */
    fun secondsRemaining(expiresAt: Long, now: Long): Long {
        val ms = expiresAt - now
        return if (ms <= 0) 0 else (ms + 999) / 1000
    }

    /** "Expires in 2:45", or "Expired". */
    fun countdownLabel(secondsRemaining: Long): String =
        if (secondsRemaining <= 0) "Expired" else String.format(Locale.ROOT, "Expires in %d:%02d", secondsRemaining / 60, secondsRemaining % 60)

    /** Toast after a successful approval. */
    fun connectedMessage(name: String): String = "$name is connected. Manage it in PhoneBridge > Agents."

    const val EXPIRED_MESSAGE = "This request is no longer open. Start connecting again from your agent."
}
