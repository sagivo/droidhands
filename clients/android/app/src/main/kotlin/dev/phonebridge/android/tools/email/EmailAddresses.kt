package dev.phonebridge.android.tools.email

import dev.phonebridge.core.tools.ToolException
import java.util.Locale

/** One email recipient: [address] is the bare address; [name] the display name when known. */
data class EmailRecipient(val address: String, val name: String? = null) {
    /** `Tania Cohen <tania@example.com>`, or the bare address. */
    val display: String get() = if (name.isNullOrBlank()) address else "$name <$address>"

    /** Case-insensitive identity (domains are case-insensitive; local parts are in practice). */
    val key: String get() = address.lowercase(Locale.ROOT)
}

/** Parsing and validation of email address arguments (pure). */
object EmailAddresses {
    const val MAX_RECIPIENTS = 50
    private const val MAX_ADDRESS_LENGTH = 254

    // Pragmatic addr-spec: dot-atom local part and a dotted domain with a 2+ letter TLD (Unicode letters allowed).
    private const val ATOM = "[\\p{L}\\p{N}!#$%&'*+/=?^_`{|}~-]+"
    private const val LABEL = "[\\p{L}\\p{N}](?:[\\p{L}\\p{N}-]{0,61}[\\p{L}\\p{N}])?"
    private val ADDRESS = Regex("^$ATOM(?:\\.$ATOM)*@(?:$LABEL\\.)+\\p{L}{2,63}$")

    /** Finds address-like substrings in free text (on-screen chips, fields). */
    val FIND = Regex("$ATOM(?:\\.$ATOM)*@(?:$LABEL\\.)+\\p{L}{2,63}")

    private val NAMED = Regex("^(.*?)<([^<>]+)>$")

    fun isValid(address: String): Boolean = address.length <= MAX_ADDRESS_LENGTH && ADDRESS.matches(address)

    /** True if [s] contains an '@' and so is meant as an address rather than a contact name. */
    fun looksLikeAddress(s: String): Boolean = s.contains('@')

    /**
     * Parses a comma/semicolon separated list such as `a@x.com, Tania Cohen <t@y.org>; "Levi, Bob" <b@z.io>`.
     * Duplicates (case-insensitive) are dropped. Throws a ToolException naming [argName] and every invalid entry.
     */
    fun parseList(raw: String, argName: String): List<EmailRecipient> {
        val entries = split(raw)
        if (entries.isEmpty()) throw ToolException("'$argName' has no email address. Pass one or more addresses separated by commas.")
        val invalid = mutableListOf<String>()
        val out = LinkedHashMap<String, EmailRecipient>()
        for (entry in entries) {
            val recipient = parseEntry(entry)
            if (recipient == null) invalid += entry else out.putIfAbsent(recipient.key, recipient)
        }
        if (invalid.isNotEmpty()) {
            throw ToolException(
                "'$argName' contains ${if (invalid.size == 1) "an invalid email address" else "invalid email addresses"}: " +
                    invalid.joinToString(", ") { "\"$it\"" } + ". Use addresses like name@example.com, separated by commas" +
                    (if (argName == "to") ", or pass a person's name as contact." else "."),
            )
        }
        if (out.size > MAX_RECIPIENTS) throw ToolException("Too many recipients in '$argName' (${out.size}, max $MAX_RECIPIENTS).")
        return out.values.toList()
    }

    /** `addr`, `mailto:addr`, `Name <addr>` or `"Name" <addr>` → recipient; null if the address is invalid. */
    fun parseEntry(entry: String): EmailRecipient? {
        val trimmed = entry.trim()
        val named = NAMED.matchEntire(trimmed)
        val (name, rawAddress) = if (named != null) {
            named.groupValues[1].trim().trim('"', '\'').trim().ifEmpty { null } to named.groupValues[2]
        } else {
            null to trimmed
        }
        val address = rawAddress.trim().removePrefix("mailto:").trim()
        return if (isValid(address)) EmailRecipient(address, name) else null
    }

    /** Splits on ',' and ';' outside double quotes and angle brackets; blank entries are dropped. */
    fun split(raw: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var angle = false
        for (ch in raw) {
            when {
                ch == '"' -> { quoted = !quoted; current.append(ch) }
                ch == '<' && !quoted -> { angle = true; current.append(ch) }
                ch == '>' && !quoted -> { angle = false; current.append(ch) }
                (ch == ',' || ch == ';') && !quoted && !angle -> { out += current.toString(); current.clear() }
                else -> current.append(ch)
            }
        }
        out += current.toString()
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }
}
