package com.droidhands.android.confirm

/**
 * Sanitizes untrusted text (agent names, client names, hosts) before it appears in approval
 * prompts, so it can't hide or reorder what the user reads (pure, unit-tested).
 */
object DisplayText {
    /**
     * Removes control characters and invisible/bidi formatting characters (U+202E "right-to-left
     * override" could make "moc.evil" read as "live.com"), collapses whitespace, trims, and cuts to
     * [max] chars with an ellipsis.
     */
    fun clean(text: String?, max: Int): String {
        if (text == null) return ""
        val plain = text
            .map { if (it.isWhitespace()) ' ' else it }
            .filterNot { it.isISOControl() || it in INVISIBLE }
            .joinToString("")
            .replace(MULTI_SPACE, " ")
            .trim()
        return if (plain.length <= max) plain else plain.take((max - 1).coerceAtLeast(0)).trimEnd() + "…"
    }

    /** Zero-width, bidi embedding/override/isolate marks and the BOM. */
    private val INVISIBLE: Set<Char> = setOf(
        '\u00AD', '\u061C', '\u180E', '\u200B', '\u200C', '\u200D', '\u200E', '\u200F',
        '\u202A', '\u202B', '\u202C', '\u202D', '\u202E', '\u2060', '\u2066', '\u2067', '\u2068', '\u2069', '\uFEFF',
    )

    private val MULTI_SPACE = Regex(" {2,}")
}
