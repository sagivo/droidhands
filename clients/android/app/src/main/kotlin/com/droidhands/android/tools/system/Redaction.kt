package com.droidhands.android.tools.system

import com.droidhands.core.tools.CallContext
import com.droidhands.core.util.OneTimeCodes

/** Masks sensitive parts of a text, e.g. [OneTimeCodes.redact]. Must be pure and thread-safe. */
typealias Redactor = (String) -> String

/** A text after redaction, and whether anything in it was masked. */
data class Redacted(val text: String, val changed: Boolean)

/**
 * "Hide one-time codes" (docs/SECURITY.md "Advanced controls") for tools that return message or
 * notification text. Pure apart from [forCall]'s use of the core [OneTimeCodes].
 */
object Redaction {
    /** Result flag set on items in which something was masked. */
    const val FLAG = "codes_hidden"

    /** Sentence for tool descriptions. */
    const val DESCRIPTION =
        "When the user hides one-time codes (the default), verification/2FA codes appear as \"${OneTimeCodes.MASK}\" and " +
            "the item has $FLAG=true: ask the user to read the code on the phone if it is really needed."

    /** The redactor for this call: [OneTimeCodes.redact] when the user hides one-time codes, else null. */
    fun forCall(ctx: CallContext): Redactor? = if (ctx.redactOneTimeCodes) OneTimeCodes::redact else null

    /** [text] passed through [redactor] (unchanged when it is null). */
    fun apply(text: String, redactor: Redactor?): Redacted {
        if (redactor == null || text.isEmpty()) return Redacted(text, changed = false)
        val masked = redactor(text)
        return Redacted(masked, changed = masked != text)
    }

    /**
     * Whether a text filter matches what the agent will see. With redaction on, a filter must also
     * hold on the masked text: otherwise repeated queries ("code is 1", "code is 12"…) would reveal a
     * hidden code one character at a time. Case-insensitive.
     */
    fun visibleMatch(visibleText: String, query: String?): Boolean =
        query.isNullOrEmpty() || visibleText.contains(query, ignoreCase = true)
}
