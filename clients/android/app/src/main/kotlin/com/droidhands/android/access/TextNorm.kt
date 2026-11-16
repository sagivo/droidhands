package com.droidhands.android.access

import java.util.Locale

/**
 * Text normalization for matching on-screen strings. Apps (WhatsApp in particular) wrap phone
 * numbers and RTL text in invisible bidi/zero-width marks and use non-breaking spaces, so raw
 * comparisons fail. Pure.
 */
object TextNorm {
    // Zero-width chars, LRM/RLM, bidi embeddings/isolates, word joiner, BOM.
    private val INVISIBLE = Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2064\\u2066-\\u2069\\uFEFF]")
    private val ODD_SPACES = Regex("[\\u00A0\\u2007\\u202F]")
    private val WHITESPACE = Regex("\\s+")

    /** Removes invisible marks and odd spaces but keeps case and line breaks (for display/compare). */
    fun clean(s: String?): String {
        if (s.isNullOrEmpty()) return ""
        return ODD_SPACES.replace(INVISIBLE.replace(s, ""), " ").replace("\r\n", "\n").replace('\r', '\n')
    }

    /** Matching key: [clean], whitespace collapsed, trimmed, lower-cased (locale-neutral). */
    fun key(s: String?): String = WHITESPACE.replace(clean(s), " ").trim().lowercase(Locale.ROOT)

    /** Digits only (phone-number comparisons). */
    fun digits(s: String?): String = s?.filter { it in '0'..'9' } ?: ""

    /** Truncates to at most [max] chars (never splitting a surrogate pair), appending an ellipsis. */
    fun truncate(s: String, max: Int): String {
        if (s.length <= max) return s
        var end = max
        if (end > 0 && Character.isHighSurrogate(s[end - 1])) end--
        return s.substring(0, end) + "…"
    }
}

/** How well a candidate string matches a query. Ordered: higher is better. */
enum class MatchLevel { NONE, CONTAINS, PREFIX, EXACT }

/** Compares already-[TextNorm.key]ed strings. */
fun matchLevel(queryKey: String, candidateKey: String): MatchLevel = when {
    queryKey.isEmpty() || candidateKey.isEmpty() -> MatchLevel.NONE
    candidateKey == queryKey -> MatchLevel.EXACT
    candidateKey.startsWith(queryKey) -> MatchLevel.PREFIX
    candidateKey.contains(queryKey) -> MatchLevel.CONTAINS
    else -> MatchLevel.NONE
}
