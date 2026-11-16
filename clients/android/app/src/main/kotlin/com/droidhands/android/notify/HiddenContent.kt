package com.droidhands.android.notify

import java.util.Locale

/**
 * Detects notifications whose content Android hid from DroidHands (pure).
 *
 * Since Android 15, notifications the system classifies as sensitive (one-time codes, and often
 * Messages / Phone notifications) reach notification listeners that aren't trusted system
 * components with their title replaced by the app name, their text replaced by a framework
 * placeholder ("Sensitive notification content hidden"), and their action titles blanked (or the
 * actions removed). The reply action, when present, still works.
 */
object HiddenContent {
    /** AOSP English text of the framework string `redacted_notification_message`. */
    const val ENGLISH_PLACEHOLDER = "Sensitive notification content hidden"

    /** True if [text] is exactly one of the [placeholders] (case and surrounding whitespace ignored). */
    fun isPlaceholder(text: String?, placeholders: Collection<String>): Boolean {
        val normalized = text?.let(::normalize)?.takeIf { it.isNotEmpty() } ?: return false
        return placeholders.any { normalize(it) == normalized }
    }

    /**
     * True if the body text or any message of a notification is Android's placeholder. [placeholders]
     * should hold the device-language framework string plus [ENGLISH_PLACEHOLDER].
     */
    fun isHidden(text: String?, messageTexts: List<String>, placeholders: Collection<String>): Boolean =
        isPlaceholder(text, placeholders) || messageTexts.any { isPlaceholder(it, placeholders) }

    private fun normalize(value: String): String = value.trim().trimEnd('.', '…').trim().lowercase(Locale.ROOT)
}
