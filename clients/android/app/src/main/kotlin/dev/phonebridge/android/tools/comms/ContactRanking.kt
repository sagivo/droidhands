package dev.phonebridge.android.tools.comms

import dev.phonebridge.android.tools.support.PhoneEntry
import dev.phonebridge.android.tools.support.WhatsAppEntry
import kotlin.math.min

/** Scores and ordering for contact search hits that are not name matches, plus row clean-up (pure). */
internal object ContactRanking {
    /** Added to non-exact scores of starred contacts; small enough never to cross a tier boundary. */
    const val STARRED_BOOST = 0.01

    /** Contacts the provider's own name filter returned but our matcher can't explain (nickname, phonetic name…). */
    const val PROVIDER_FLOOR = 0.80

    const val PHONE_EXACT = 1.0
    const val PHONE_SAME_NUMBER = 0.95
    const val PHONE_PARTIAL = 0.80

    const val EMAIL_EXACT = 1.0
    const val EMAIL_PREFIX = 0.90
    const val EMAIL_OTHER = 0.85

    /** Shortest digit run that counts as a partial number match ("4567"). */
    private const val PARTIAL_MIN_DIGITS = 4
    private const val NON_EXACT_MAX = 0.99

    fun boost(score: Double, starred: Boolean): Double =
        if (starred && score < NameMatching.EXACT) min(score + STARRED_BOOST, NON_EXACT_MAX) else score

    /** Score desc, then starred first, then name (case-insensitive). */
    fun <T> order(score: (T) -> Double, starred: (T) -> Boolean, name: (T) -> String): Comparator<T> =
        compareByDescending(score).thenByDescending(starred).thenBy(String.CASE_INSENSITIVE_ORDER, name)

    /** Score of a stored number for a number query that PhoneLookup did not match exactly; 0 = no match. */
    fun phoneScore(query: String, number: String?, normalized: String?): Double {
        val stored = listOfNotNull(number, normalized).filter { it.isNotBlank() }
        if (stored.any { PhoneText.numbersMatch(query, it) }) return PHONE_SAME_NUMBER
        val digits = PhoneText.comparableDigits(query)
        if (digits.length >= PARTIAL_MIN_DIGITS && stored.any { PhoneText.comparableDigits(it).contains(digits) }) return PHONE_PARTIAL
        return 0.0
    }

    fun emailScore(query: String, address: String?): Double {
        val stored = address?.trim().orEmpty()
        return when {
            stored.equals(query.trim(), ignoreCase = true) -> EMAIL_EXACT
            stored.startsWith(query.trim(), ignoreCase = true) -> EMAIL_PREFIX
            else -> EMAIL_OTHER
        }
    }

    /**
     * Removes repeated numbers (same E.164, or same digits when unknown) that aggregated contacts
     * often carry once per account. Keeps the first occurrence, but a later specific type replaces a
     * generic "other".
     */
    fun dedupePhones(phones: List<PhoneEntry>): List<PhoneEntry> {
        val byKey = LinkedHashMap<String, PhoneEntry>()
        for (phone in phones) {
            val key = phone.normalized ?: PhoneText.comparableDigits(phone.number).ifEmpty { phone.number.trim() }
            val existing = byKey[key]
            if (existing == null) byKey[key] = phone
            else if (existing.type == "other" && phone.type != "other") byKey[key] = existing.copy(type = phone.type)
        }
        return byKey.values.toList()
    }
}

/** WhatsApp's rows in ContactsContract.Data (synced by the WhatsApp apps' contact adapters). */
internal object WhatsAppRows {
    const val PACKAGE = "com.whatsapp"
    const val BUSINESS_PACKAGE = "com.whatsapp.w4b"
    const val MIMETYPE = "vnd.android.cursor.item/vnd.com.whatsapp.profile"
    const val BUSINESS_MIMETYPE = "vnd.android.cursor.item/vnd.com.whatsapp.w4b.profile"
    val MIMETYPES = listOf(MIMETYPE, BUSINESS_MIMETYPE)

    private const val USER_DOMAIN = "s.whatsapp.net"
    private const val LEGACY_USER_DOMAIN = "c.us"
    private val NUMBER_LENGTH = 6..15

    /**
     * Parses a profile row: DATA1 is the jid ("972541234567@s.whatsapp.net"). Group or non-phone
     * ids are skipped. The package comes from the raw contact's ACCOUNT_TYPE, else from the mimetype.
     */
    fun entry(data1: String?, mimetype: String?, accountType: String?): WhatsAppEntry? {
        val raw = data1?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val user = raw.substringBefore('@')
        val domain = raw.substringAfter('@', missingDelimiterValue = "")
        if (domain.isNotEmpty() && domain != USER_DOMAIN && domain != LEGACY_USER_DOMAIN) return null
        if (user.length !in NUMBER_LENGTH || !user.all { it in '0'..'9' }) return null
        val packageName = when {
            accountType == BUSINESS_PACKAGE -> BUSINESS_PACKAGE
            accountType == PACKAGE -> PACKAGE
            mimetype == BUSINESS_MIMETYPE -> BUSINESS_PACKAGE
            else -> PACKAGE
        }
        return WhatsAppEntry(number = user, jid = "$user@$USER_DOMAIN", packageName = packageName)
    }
}
