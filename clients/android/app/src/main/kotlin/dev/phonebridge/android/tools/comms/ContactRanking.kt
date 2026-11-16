package dev.phonebridge.android.tools.comms

import dev.phonebridge.android.tools.support.PhoneEntry
import dev.phonebridge.android.tools.support.ContactAppEntry
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

/**
 * Rows apps add to contacts (pure): a Data row of a raw contact whose account type is an installed app's package, with a
 * mimetype that isn't one of Android's standard kinds, is that app's entry for the contact (the Contacts app shows them
 * as "Message on …", "Voice call …"). Works for any app; nothing here names one.
 */
internal object ContactAppRows {
    /** The provider's own data kinds (names, phones, emails…): never an app entry. */
    val STANDARD_MIMETYPES: List<String> = listOf(
        "vnd.android.cursor.item/name", "vnd.android.cursor.item/phone_v2", "vnd.android.cursor.item/email_v2",
        "vnd.android.cursor.item/photo", "vnd.android.cursor.item/organization", "vnd.android.cursor.item/im",
        "vnd.android.cursor.item/nickname", "vnd.android.cursor.item/note", "vnd.android.cursor.item/postal-address_v2",
        "vnd.android.cursor.item/group_membership", "vnd.android.cursor.item/website", "vnd.android.cursor.item/contact_event",
        "vnd.android.cursor.item/relation", "vnd.android.cursor.item/sip_address", "vnd.android.cursor.item/identity",
    )

    /** At most this many entries per app. */
    const val MAX_ENTRIES = 6
    private const val MAX_ENTRY_LENGTH = 80

    /** One Data row: the raw contact's account type and the row's summary/detail columns. */
    data class Row(val accountType: String?, val mimetype: String?, val data2: String?, val data3: String?)

    /**
     * The app entries of one contact's [rows]: per account type that [isApp] accepts (an installed app's package), the
     * rows' detail text (DATA3, else DATA2), de-duplicated. [label] names the app.
     */
    fun entries(rows: List<Row>, isApp: (String) -> Boolean, label: (String) -> String?): List<ContactAppEntry> {
        val byApp = LinkedHashMap<String, LinkedHashSet<String>>()
        for (row in rows) {
            val pkg = row.accountType?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            if (row.mimetype == null || row.mimetype in STANDARD_MIMETYPES || !isApp(pkg)) continue
            val text = sequenceOf(row.data3, row.data2).mapNotNull { it?.replace(WHITESPACE, " ")?.trim()?.takeIf { t -> t.isNotEmpty() } }
                .firstOrNull()?.take(MAX_ENTRY_LENGTH)
            val set = byApp.getOrPut(pkg) { LinkedHashSet() }
            if (text != null && set.size < MAX_ENTRIES) set += text
        }
        return byApp.map { (pkg, texts) -> ContactAppEntry(pkg, label(pkg), texts.toList()) }
    }

    private val WHITESPACE = Regex("\\s+")
}
