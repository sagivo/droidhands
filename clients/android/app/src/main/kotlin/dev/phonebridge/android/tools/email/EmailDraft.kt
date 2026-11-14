package dev.phonebridge.android.tools.email

import dev.phonebridge.android.access.TextNorm
import java.io.ByteArrayOutputStream

/** The email the user approves; [subject] may be empty, [body] is not. */
data class EmailDraft(
    val to: List<EmailRecipient>,
    val cc: List<EmailRecipient>,
    val subject: String,
    val body: String,
) {
    /** `Tania Cohen <tania@x.com>, bob@y.org`. */
    val toText: String get() = to.joinToString(", ") { it.display }

    val ccText: String get() = cc.joinToString(", ") { it.display }

    /**
     * What the user approves: `Send email to <recipients> (cc: …): "<subject>" — <first 120 chars of body>`.
     * Whitespace in the body preview is collapsed so it fits a notification.
     */
    fun confirmationSummary(): String = buildString {
        append("Send email to ").append(toText)
        if (cc.isNotEmpty()) append(" (cc: ").append(ccText).append(')')
        append(": \"").append(subject.ifBlank { "(no subject)" }).append("\" — ")
        append(TextNorm.truncate(TextNorm.clean(body).replace(WHITESPACE, " ").trim(), BODY_PREVIEW_CHARS))
    }

    /** Short phrase for the unlock prompt: "send an email to Tania Cohen" / "… to 3 people". */
    fun unlockAction(): String {
        val who = if (to.size == 1) (to.single().name ?: to.single().address) else "${to.size} people"
        return "send an email to $who"
    }

    companion object {
        const val BODY_PREVIEW_CHARS = 120
        const val MAX_BODY_CHARS = 20_000
        const val MAX_SUBJECT_CHARS = 1_000
        private val WHITESPACE = Regex("\\s+")

        /** Subjects are one line: line breaks become spaces. */
        fun cleanSubject(raw: String?): String = raw.orEmpty().replace(Regex("[\\r\\n]+"), " ").trim()
    }
}

/** RFC 6068 `mailto:` URIs (pure). */
object Mailto {
    /** `mailto:a@x.com,b@y.org?cc=c%40z.io&subject=Hi%20there&body=Line%201%0ALine%202`. Empty parts are left out. */
    fun uri(to: List<String>, cc: List<String>, subject: String, body: String): String = buildString {
        append("mailto:")
        append(to.joinToString(",") { encode(it, keepAt = true) })
        val query = buildList {
            if (cc.isNotEmpty()) add("cc=" + cc.joinToString(",") { encode(it, keepAt = true) })
            if (subject.isNotEmpty()) add("subject=" + encode(subject))
            if (body.isNotEmpty()) add("body=" + encode(body))
        }
        if (query.isNotEmpty()) append('?').append(query.joinToString("&"))
    }

    /**
     * Percent-encodes UTF-8 [s], keeping only RFC 3986 unreserved characters (and '@' in addresses), so '+', '&',
     * '#', '?' and spaces survive every parser ("+" is never read as a space this way).
     */
    fun encode(s: String, keepAt: Boolean = false): String {
        val bytes = s.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream(bytes.size)
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            val keep = (c in 'a'.code..'z'.code) || (c in 'A'.code..'Z'.code) || (c in '0'.code..'9'.code) ||
                c == '-'.code || c == '.'.code || c == '_'.code || c == '~'.code || (keepAt && c == '@'.code)
            if (keep) {
                out.write(c)
            } else {
                out.write('%'.code)
                out.write(HEX[c shr 4].code)
                out.write(HEX[c and 0x0F].code)
            }
        }
        return out.toString(Charsets.US_ASCII.name())
    }

    private const val HEX = "0123456789ABCDEF"
}
