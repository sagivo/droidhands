package dev.phonebridge.android.notify

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Minimal identity of a MessagingStyle `Person`, for "is this message mine?" checks. */
data class PersonRef(val key: String?, val name: String?)

/** Pure text helpers for notification mapping (no Android types; unit-tested). */
object NotificationText {
    const val MAX_TITLE = 300
    const val MAX_TEXT = 1000
    const val MAX_MESSAGE_TEXT = 500
    const val MAX_ACTION_TITLE = 100

    /** Most recent MessagingStyle messages returned per notification. */
    const val MAX_MESSAGES = 10

    private val ISO_SECONDS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")

    /** Trimmed string, or null when [value] is null or blank. */
    fun clean(value: CharSequence?): String? = value?.toString()?.trim()?.takeIf { it.isNotEmpty() }

    /** First non-blank of conversation title, `EXTRA_TITLE`, `EXTRA_TITLE_BIG`. */
    fun title(conversationTitle: String?, title: String?, bigTitle: String?): String? =
        sequenceOf(conversationTitle, title, bigTitle).firstOrNull { !it.isNullOrBlank() }

    /**
     * Body text: `EXTRA_BIG_TEXT` over `EXTRA_TEXT`, combined with InboxStyle `EXTRA_TEXT_LINES`
     * (joined by newlines). The primary text is dropped when the lines already contain it.
     */
    fun body(bigText: String?, text: String?, lines: List<String>): String? {
        val primary = bigText?.takeIf { it.isNotBlank() } ?: text?.takeIf { it.isNotBlank() }
        val joined = lines.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n").ifEmpty { null }
        return when {
            joined == null -> primary
            primary == null -> joined
            joined.contains(primary) -> joined
            else -> "$primary\n$joined"
        }
    }

    /**
     * Cuts [value] to at most [max] chars (never splitting a surrogate pair) and says so, so the
     * agent knows the text is incomplete.
     */
    fun truncate(value: String, max: Int): String {
        if (value.length <= max) return value
        var end = max
        if (end > 0 && Character.isHighSurrogate(value[end - 1])) end--
        return value.substring(0, end).trimEnd() + "… [truncated, ${value.length} chars total]"
    }

    /** Short form for one-line summaries (confirmation prompts): plain ellipsis, no annotation. */
    fun ellipsize(value: String, max: Int): String {
        val singleLine = value.replace(Regex("\\s+"), " ").trim()
        if (singleLine.length <= max) return singleLine
        var end = max - 1
        if (end > 0 && Character.isHighSurrogate(singleLine[end - 1])) end--
        return singleLine.substring(0, end).trimEnd() + "…"
    }

    /**
     * Whether a MessagingStyle message was sent by the device's user. By platform convention a
     * message without a sender person is the user's own; otherwise compare keys when both exist,
     * falling back to the display name.
     */
    fun isFromUser(sender: PersonRef?, user: PersonRef?): Boolean {
        if (sender == null) return true
        if (user == null) return false
        if (sender.key != null && user.key != null) return sender.key == user.key
        return sender.name != null && sender.name == user.name
    }

    /** Placeholder text for a message that only carries data (image, audio…). */
    fun attachmentPlaceholder(mimeType: String): String {
        val kind = mimeType.substringBefore('/').trim().lowercase().ifEmpty { "file" }
        return "[$kind]"
    }

    /** ISO-8601 local time with offset, second precision, e.g. `2026-11-13T09:41:05+02:00`. */
    fun isoTime(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        ISO_SECONDS.format(Instant.ofEpochMilli(epochMillis).truncatedTo(ChronoUnit.SECONDS).atZone(zone))
}
