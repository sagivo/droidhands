package com.droidhands.android.tools.system

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import java.util.Locale

// Small pure helpers shared by the system tools (no Android imports; unit-tested on the JVM).

private val WHITESPACE = Regex("\\s+")
private val ENUM_SEPARATORS = Regex("[\\s-]+")

/** Shortens [text] to at most [max] characters, ending with an ellipsis when cut. Never splits a surrogate pair. */
fun truncate(text: String, max: Int): String {
    require(max >= 1) { "max must be >= 1" }
    if (text.length <= max) return text
    var end = max - 1
    if (end > 0 && Character.isHighSurrogate(text[end - 1])) end--
    return text.substring(0, end).trimEnd() + "…"
}

/** Free text as a quoted one-liner for confirmation prompts: whitespace collapsed, truncated. */
fun quoteForSummary(text: String, max: Int = 80): String = "\"" + truncate(text.replace(WHITESPACE, " ").trim(), max) + "\""

/** For Tool.describeCall: [block]'s summary, or [fallback] when the arguments are malformed. Never throws. */
inline fun describeOr(fallback: String, block: () -> String): String = runCatching(block).getOrDefault(fallback)

/** Normalizes an enum-like argument: trimmed, lowercase, spaces/hyphens to underscores ("Play-Pause" -> "play_pause"). */
fun normalizeEnumArg(raw: String): String = raw.trim().lowercase(Locale.ROOT).replace(ENUM_SEPARATORS, "_")

/** Android package name syntax (at least two segments, Java identifiers). */
private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

fun isValidPackageName(value: String): Boolean = value.length <= 255 && PACKAGE_NAME.matches(value)

fun JsonObjectBuilder.putIfNotNull(key: String, value: String?) {
    if (value != null) put(key, value)
}

fun JsonObjectBuilder.putIfNotNull(key: String, value: Number?) {
    if (value != null) put(key, value)
}

fun JsonObjectBuilder.putIfNotNull(key: String, value: Boolean?) {
    if (value != null) put(key, value)
}
