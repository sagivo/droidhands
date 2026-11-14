package dev.phonebridge.android.pairing

import dev.phonebridge.android.confirm.DisplayText
import dev.phonebridge.core.auth.ConfirmPolicy
import dev.phonebridge.core.tools.Scopes
import dev.phonebridge.core.transport.PairingRequest
import java.util.Locale

/** One of the access levels offered when approving a new agent ([Scopes.PRESETS]). */
data class AccessLevel(
    /** Preset name, e.g. "Assistant". */
    val name: String,
    val scopes: Set<String>,
    /** One short plain-language sentence. */
    val description: String,
    val recommended: Boolean,
)

/** What the user chose for a new agent. */
data class PairingChoice(
    /** Agent name shown in PhoneBridge; blank means the client's own name. */
    val name: String,
    val scopes: Set<String>,
    val policy: ConfirmPolicy = ConfirmPolicy.SENSITIVE,
) {
    companion object {
        /** The recommended choice: the client's name, Assistant, ask before sensitive actions. */
        fun defaults(request: PairingRequest) = PairingChoice(
            name = request.clientName,
            scopes = PairingPresentation.level(PairingPresentation.DEFAULT_LEVEL).scopes,
            policy = ConfirmPolicy.SENSITIVE,
        )
    }
}

/** Pure text and choices for the pairing prompt (notification, [PairingActivity], Home card). */
object PairingPresentation {
    const val DEFAULT_LEVEL = "Assistant"

    /** The [Scopes.PRESETS], in order, with descriptions. */
    val ACCESS_LEVELS: List<AccessLevel> = Scopes.PRESETS.map { (name, scopes) ->
        AccessLevel(name, scopes, levelDescription(name), recommended = name == DEFAULT_LEVEL)
    }

    /** Confirmation policies in the order offered (recommended first). */
    val POLICIES: List<ConfirmPolicy> = listOf(ConfirmPolicy.SENSITIVE, ConfirmPolicy.ALL_ACTIONS, ConfirmPolicy.NEVER)

    fun level(name: String): AccessLevel = ACCESS_LEVELS.firstOrNull { it.name == name } ?: ACCESS_LEVELS.first { it.recommended }

    fun levelDescription(name: String): String = when (name) {
        "Read-only" -> "Can look but not act: read messages, notifications, contacts, calendar, location and the screen."
        "Assistant" -> "Read-only, plus send messages, make calls, answer notifications and add calendar events."
        "Full control" -> "Everything, including tapping and typing in any app. Only for agents you fully trust."
        else -> "A custom set of permissions."
    }

    fun policyLabel(policy: ConfirmPolicy): String = when (policy) {
        ConfirmPolicy.SENSITIVE -> "Ask me before sensitive actions"
        ConfirmPolicy.ALL_ACTIONS -> "Ask me before every action"
        ConfirmPolicy.NEVER -> "Never ask me"
    }

    fun policyDescription(policy: ConfirmPolicy): String = when (policy) {
        ConfirmPolicy.SENSITIVE -> "Recommended. You approve messages, calls and replies on the phone."
        ConfirmPolicy.ALL_ACTIONS -> "You also approve taps, typing and opening apps. Reading never asks."
        ConfirmPolicy.NEVER -> "The agent acts without asking you."
    }

    const val NEVER_POLICY_WARNING =
        "Without approvals, an agent tricked by a message or web page it reads can send messages and place calls as you."

    const val CODE_HINT = "Make sure your browser shows the same code."

    const val ALLOW_HINT = "Only allow if you just started connecting this agent yourself."

    /** "Claude (claude.ai) wants to connect · code K7Q2". */
    fun notificationTitle(request: PairingRequest): String =
        "${request.clientName} (${request.redirectHost}) wants to connect · code ${request.displayCode}"

    /** Collapsed text: repeats the code first, in case the title is cut off. */
    fun notificationText(request: PairingRequest): String = "Code ${request.displayCode} · Tap to review and choose what it may do"

    fun notificationBigText(request: PairingRequest): String = buildString {
        append("Check that your browser shows code ").append(request.displayCode)
        append(", then tap to choose what ").append(request.clientName).append(" may do on this phone.")
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
