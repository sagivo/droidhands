package dev.phonebridge.android.tools.notifications

import dev.phonebridge.android.notify.NotificationInfo
import dev.phonebridge.android.tools.system.Redaction
import dev.phonebridge.android.tools.system.Redactor

/**
 * One-time-code masking for notifications (pure). Covers every text an agent can see: title,
 * body, message texts and action titles (OTP notifications often carry a "Copy 123456" button).
 * Sender names are left alone: they are names, not message text.
 */
object NotificationRedaction {
    /** [info] with codes masked and [NotificationInfo.codesHidden] set when anything changed; [info] itself otherwise. */
    fun redact(info: NotificationInfo, redactor: Redactor?): NotificationInfo {
        if (redactor == null) return info
        var changed = false
        fun mask(text: String): String = Redaction.apply(text, redactor).also { if (it.changed) changed = true }.text
        val masked = info.copy(
            title = info.title?.let(::mask),
            text = info.text?.let(::mask),
            messages = info.messages.map { it.copy(text = mask(it.text)) },
            actions = info.actions.map { it.copy(title = mask(it.title)) },
        )
        return if (changed) masked.copy(codesHidden = true) else info
    }
}
