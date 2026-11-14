package dev.phonebridge.android.tools.notifications

import dev.phonebridge.android.notify.NotificationInfo
import dev.phonebridge.android.tools.system.Redaction
import dev.phonebridge.android.tools.system.Redactor

/**
 * One-time-code masking for notifications (pure). Covers every text an agent can see: title
 * (the conversation title for chats), body, message texts and action titles (OTP notifications
 * often carry a "Copy 123456" button). Sender names are left alone: they are names, not message text.
 *
 * The texts are masked all together first, because apps often split a code from its keyword: title
 * "Verification code" or "One-time password", body "482913 (valid for 5 minutes)". Together, a
 * keyword in one text reaches a code in the next (a line break is not a sentence end for
 * [dev.phonebridge.core.util.OneTimeCodes]), and a code found anywhere is masked wherever it repeats,
 * e.g. in an action title. Then each text is masked on its own as well, so the extra context can never
 * hide less than masking the texts separately.
 */
object NotificationRedaction {
    /** Joins the texts for the combined pass: a line break, plus a form feed that makes the joint unique. */
    private const val JOINT = "\n\u000C\n"

    /** [info] with codes masked and [NotificationInfo.codesHidden] set when anything changed; [info] itself otherwise. */
    fun redact(info: NotificationInfo, redactor: Redactor?): NotificationInfo {
        if (redactor == null) return info
        val original = texts(info)
        val masked = (together(original, redactor) ?: original).map { Redaction.apply(it, redactor).text }
        if (masked == original) return info
        var i = 0
        fun next(): String = masked[i++]
        // Same order as texts().
        val title = next().takeIf { info.title != null }
        val text = next().takeIf { info.text != null }
        return info.copy(
            title = title,
            text = text,
            messages = info.messages.map { it.copy(text = next()) },
            actions = info.actions.map { it.copy(title = next()) },
            codesHidden = true,
        )
    }

    /** Every agent-visible text of [info] in a fixed order; absent title/text as "". */
    private fun texts(info: NotificationInfo): List<String> =
        listOf(info.title.orEmpty(), info.text.orEmpty()) + info.messages.map { it.text } + info.actions.map { it.title }

    /**
     * [texts] masked as one text and split back, or null if that can't be done reliably (a text
     * contains the joint's form feed, or the redactor touched a joint).
     */
    private fun together(texts: List<String>, redactor: Redactor): List<String>? {
        if (texts.count { it.isNotEmpty() } < 2 || texts.any { '\u000C' in it }) return null
        return redactor(texts.joinToString(JOINT)).split(JOINT).takeIf { it.size == texts.size }
    }
}
