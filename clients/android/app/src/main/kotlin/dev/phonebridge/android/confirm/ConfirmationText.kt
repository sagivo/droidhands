package dev.phonebridge.android.confirm

import dev.phonebridge.core.confirm.ConfirmationRequest
import dev.phonebridge.core.tools.Risk

/** Pure text helpers for confirmation prompts (notification and [ConfirmationActivity]). */
object ConfirmationText {
    /** e.g. "Claude wants to: Send WhatsApp message". */
    fun title(request: ConfirmationRequest): String {
        val who = request.tokenName.trim().ifEmpty { "An agent" }
        val what = request.toolTitle.trim().ifEmpty { request.toolName }
        return "$who wants to: $what"
    }

    fun riskLabel(risk: Risk): String = when (risk) {
        Risk.READ -> "Read only"
        Risk.ACTION -> "Changes something on this phone"
        Risk.SENSITIVE -> "Sensitive: acts on your behalf"
    }

    fun transportLabel(transport: String): String = when (transport) {
        "relay" -> "via relay"
        "local" -> "via local connection"
        else -> "via $transport"
    }

    /** Expanded notification text: the exact action, then risk and transport. */
    fun notificationBody(request: ConfirmationRequest): String =
        "${request.summary.trim()}\n\n${riskLabel(request.risk)} · ${transportLabel(request.transport)}"

    /** Whole seconds left until [expiresAt], rounded up, never negative. */
    fun secondsRemaining(expiresAt: Long, now: Long): Long {
        val ms = expiresAt - now
        return if (ms <= 0) 0 else (ms + 999) / 1000
    }

    fun countdownLabel(secondsRemaining: Long): String =
        if (secondsRemaining <= 0) "Expired" else "Expires in $secondsRemaining s"
}
