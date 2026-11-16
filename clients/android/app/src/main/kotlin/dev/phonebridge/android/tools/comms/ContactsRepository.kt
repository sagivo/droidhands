package dev.phonebridge.android.tools.comms

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.Contacts
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.PhoneLookup
import android.provider.ContactsContract.RawContacts
import android.util.Log
import dev.phonebridge.android.tools.support.ContactMatch
import dev.phonebridge.android.tools.support.PhoneEntry
import dev.phonebridge.android.tools.support.ContactAppEntry
import dev.phonebridge.core.tools.ToolException
import kotlin.math.max

/**
 * ContactsContract queries behind [dev.phonebridge.android.tools.support.ContactLookup].
 *
 * Every method blocks on ContentResolver IPC: call from Dispatchers.IO. The caller has checked
 * READ_CONTACTS; a SecurityException (permission revoked meanwhile) propagates.
 */
internal class ContactsRepository(private val context: Context) {
    private val resolver: ContentResolver = context.contentResolver

    private class Ranked(val row: ContactRow, val score: Double)

    /**
     * Phone-number queries use PhoneLookup (then a partial-number scan), email queries the email
     * filter, and names the provider's name filter plus our fuzzy/transliterating matcher over
     * every display name and nickname (prepared once and cached by [ContactIndexCache]). Hits are
     * merged by contact, ranked, and the top [limit] get their phones, emails and WhatsApp ids
     * loaded in batched queries.
     */
    fun search(query: String, limit: Int): List<ContactMatch> {
        val q = query.trim()
        if (q.isEmpty()) throw ToolException("Argument 'query' must not be empty.")
        val hits = HashMap<Long, Double>()
        val rows = when {
            PhoneText.looksLikePhoneNumber(q) -> {
                addPhoneHits(q, hits)
                loadRows(hits.keys)
            }
            '@' in q -> {
                addEmailHits(q, hits)
                loadRows(hits.keys)
            }
            else -> addNameHits(q, hits)
        }
        val ranked = hits.mapNotNull { (id, score) -> rows[id]?.let { Ranked(it, ContactRanking.boost(score, it.starred)) } }
            .sortedWith(ContactRanking.order({ it.score }, { it.row.starred }, { it.row.name }))
            .take(limit)
        return withDetails(ranked)
    }

    /** The contact that has [number] (PhoneLookup's loose match), with details, or null. */
    fun findByNumber(number: String): ContactMatch? {
        val id = lookupIds(number).firstOrNull() ?: return null
        val row = loadRows(listOf(id))[id] ?: return null
        return withDetails(listOf(Ranked(row, ContactRanking.PHONE_EXACT))).firstOrNull()
    }

    /** Display name of the contact that has [number], or null. */
    fun lookupName(number: String): String? =
        resolver.query(phoneLookupUri(number), arrayOf(PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.stringOrNull(0)?.takeIf { it.isNotBlank() } else null
        }

    // --- Hits ---------------------------------------------------------------------------------------

    private fun addPhoneHits(query: String, hits: MutableMap<Long, Double>) {
        for (id in lookupIds(query)) hits[id] = ContactRanking.PHONE_EXACT
        if (hits.isNotEmpty()) return
        // Partial or oddly formatted numbers ("4567", "054-12"): compare with every stored number.
        resolver.query(Phone.CONTENT_URI, arrayOf(Phone.CONTACT_ID, Phone.NUMBER, Phone.NORMALIZED_NUMBER), null, null, null)
            ?.use { c ->
                while (c.moveToNext()) {
                    val score = ContactRanking.phoneScore(query, c.stringOrNull(1), c.stringOrNull(2))
                    if (score > 0.0) hits.merge(c.getLong(0), score) { a, b -> max(a, b) }
                }
            }
    }

    private fun addEmailHits(query: String, hits: MutableMap<Long, Double>) {
        val uri = Uri.withAppendedPath(Email.CONTENT_FILTER_URI, Uri.encode(query))
        resolver.query(uri, arrayOf(Email.CONTACT_ID, Email.ADDRESS), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                hits.merge(c.getLong(0), ContactRanking.emailScore(query, c.stringOrNull(1))) { a, b -> max(a, b) }
            }
        }
    }

    /** Scores every contact by name; returns all rows (needed for ranking anyway). */
    private fun addNameHits(query: String, hits: MutableMap<Long, Double>): Map<Long, ContactRow> {
        val index = ContactIndexCache.get(context, ::loadNameIndex)
        val rows = index.rows
        val providerIds = providerNameMatches(query)
        val queryKey = NameKey.of(query)
        if (queryKey.isEmpty) {
            // Only symbols/emoji: trust the provider alone.
            for (id in providerIds) if (id in rows) hits[id] = ContactRanking.PROVIDER_FLOOR
            return rows
        }
        for (row in rows.values) {
            var score = NameMatching.bestScore(queryKey, index.keysOf(row.id))
            if (row.id in providerIds) score = max(score, ContactRanking.PROVIDER_FLOOR)
            if (score >= NameMatching.FUZZY_MIN) hits[row.id] = score
        }
        return rows
    }

    /** Every contact's names, prepared for matching (two provider queries plus the preparation). */
    private fun loadNameIndex(): ContactNameIndex = ContactNameIndex.build(readRows(selection = null, args = null), loadNicknames())

    /** Contact ids from ContactsContract's own name filter (token prefixes, nicknames, phonetic names). */
    private fun providerNameMatches(query: String): Set<Long> {
        val ids = HashSet<Long>()
        val uri = Uri.withAppendedPath(Contacts.CONTENT_FILTER_URI, Uri.encode(query))
        try {
            resolver.query(uri, arrayOf(Contacts._ID), null, null, null)?.use { c ->
                while (ids.size < MAX_PROVIDER_HITS && c.moveToNext()) ids += c.getLong(0)
            }
        } catch (e: SecurityException) {
            throw e
        } catch (e: RuntimeException) {
            // Best effort: our own matcher still runs. Don't log the query (personal data).
            Log.w(TAG, "Contacts name filter failed: ${e.javaClass.simpleName}")
        }
        return ids
    }

    private fun lookupIds(number: String): List<Long> {
        val ids = ArrayList<Long>()
        resolver.query(phoneLookupUri(number), arrayOf(PhoneLookup.CONTACT_ID), null, null, null)?.use { c ->
            while (c.moveToNext()) if (!c.isNull(0)) ids += c.getLong(0)
        }
        return ids.distinct()
    }

    private fun phoneLookupUri(number: String): Uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))

    // --- Rows and details ---------------------------------------------------------------------------

    private fun loadRows(ids: Collection<Long>): Map<Long, ContactRow> {
        val rows = HashMap<Long, ContactRow>()
        forEachIdChunk(ids) { placeholders, args -> rows += readRows("${Contacts._ID} IN ($placeholders)", args) }
        return rows
    }

    private fun readRows(selection: String?, args: Array<String>?): Map<Long, ContactRow> {
        val rows = LinkedHashMap<Long, ContactRow>()
        val projection = arrayOf(Contacts._ID, Contacts.DISPLAY_NAME_PRIMARY, Contacts.DISPLAY_NAME_ALTERNATIVE, Contacts.STARRED)
        resolver.query(Contacts.CONTENT_URI, projection, selection, args, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val alternative = c.stringOrNull(2)?.trim()?.takeIf { it.isNotEmpty() }
                val name = c.stringOrNull(1)?.trim()?.takeIf { it.isNotEmpty() } ?: alternative.orEmpty()
                rows[id] = ContactRow(id, name, alternative, c.intOr(3, 0) != 0)
            }
        }
        return rows
    }

    private fun loadNicknames(): Map<Long, List<String>> {
        val nicknames = HashMap<Long, MutableList<String>>()
        resolver.query(
            Data.CONTENT_URI, arrayOf(Data.CONTACT_ID, Nickname.NAME), "${Data.MIMETYPE} = ?", arrayOf(Nickname.CONTENT_ITEM_TYPE), null,
        )?.use { c ->
            while (c.moveToNext()) {
                val nickname = c.stringOrNull(1)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                nicknames.getOrPut(c.getLong(0)) { ArrayList(1) } += nickname
            }
        }
        return nicknames
    }

    private fun withDetails(ranked: List<Ranked>): List<ContactMatch> {
        if (ranked.isEmpty()) return emptyList()
        val ids = ranked.map { it.row.id }
        val phones = loadPhones(ids)
        val emails = loadEmails(ids)
        val apps = loadApps(ids)
        return ranked.map { ranking ->
            val row = ranking.row
            val contactPhones = phones[row.id].orEmpty()
            val contactEmails = emails[row.id].orEmpty()
            ContactMatch(
                id = row.id,
                name = row.name.ifEmpty { contactPhones.firstOrNull()?.number ?: contactEmails.firstOrNull() ?: "(no name)" },
                phones = contactPhones,
                emails = contactEmails,
                apps = apps[row.id].orEmpty(),
                starred = row.starred,
                score = ranking.score,
            )
        }
    }

    private fun loadPhones(ids: List<Long>): Map<Long, List<PhoneEntry>> {
        val raw = HashMap<Long, MutableList<PhoneEntry>>()
        val projection = arrayOf(Phone.CONTACT_ID, Phone.NUMBER, Phone.NORMALIZED_NUMBER, Phone.TYPE, Phone.LABEL)
        forEachIdChunk(ids) { placeholders, args ->
            resolver.query(
                Phone.CONTENT_URI, projection, "${Phone.CONTACT_ID} IN ($placeholders)", args,
                "${Phone.IS_SUPER_PRIMARY} DESC, ${Phone.IS_PRIMARY} DESC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val number = c.stringOrNull(1)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                    val normalized = c.stringOrNull(2)?.let(PhoneText::plausibleE164) ?: E164Formatter.toE164(context, number)
                    val type = PhoneTypes.label(c.intOr(3, Phone.TYPE_OTHER), c.stringOrNull(4))
                    raw.getOrPut(c.getLong(0)) { ArrayList(2) } += PhoneEntry(number, normalized, type)
                }
            }
        }
        return raw.mapValues { (_, list) -> ContactRanking.dedupePhones(list) }
    }

    private fun loadEmails(ids: List<Long>): Map<Long, List<String>> {
        val emails = HashMap<Long, MutableList<String>>()
        forEachIdChunk(ids) { placeholders, args ->
            resolver.query(Email.CONTENT_URI, arrayOf(Email.CONTACT_ID, Email.ADDRESS), "${Email.CONTACT_ID} IN ($placeholders)", args, null)
                ?.use { c ->
                    while (c.moveToNext()) {
                        val address = c.stringOrNull(1)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                        val list = emails.getOrPut(c.getLong(0)) { ArrayList(1) }
                        if (list.none { it.equals(address, ignoreCase = true) }) list += address
                    }
                }
        }
        return emails
    }

    /** Per contact, the apps that synced it ([ContactAppRows]). Unreadable rows are skipped. */
    private fun loadApps(ids: List<Long>): Map<Long, List<ContactAppEntry>> {
        val rows = HashMap<Long, MutableList<ContactAppRows.Row>>()
        forEachIdChunk(ids) { placeholders, args ->
            val standard = ContactAppRows.STANDARD_MIMETYPES
            val selection = "${Data.CONTACT_ID} IN ($placeholders) AND ${Data.MIMETYPE} NOT IN (${SqlText.placeholders(standard.size)})"
            val projection = arrayOf(Data.CONTACT_ID, RawContacts.ACCOUNT_TYPE, Data.MIMETYPE, Data.DATA2, Data.DATA3)
            val cursor = try {
                resolver.query(Data.CONTENT_URI, projection, selection, args + standard, null)
            } catch (e: IllegalArgumentException) {
                null // An OEM provider without ACCOUNT_TYPE in the Data view: no app entries.
            }
            cursor?.use { c ->
                while (c.moveToNext()) {
                    rows.getOrPut(c.getLong(0)) { ArrayList(2) } += ContactAppRows.Row(c.stringOrNull(1), c.stringOrNull(2), c.stringOrNull(3), c.stringOrNull(4))
                }
            }
        }
        val pm = context.packageManager
        val installed = HashMap<String, String?>()
        fun appLabel(pkg: String): String? = installed.getOrPut(pkg) {
            try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (e: Exception) {
                NOT_INSTALLED
            }
        }
        return rows.mapValues { (_, list) ->
            ContactAppRows.entries(list, isApp = { appLabel(it) != NOT_INSTALLED && it != context.packageName }, label = { appLabel(it) })
        }
    }

    private companion object {
        const val TAG = "PhoneBridge/Contacts"

        /** Marks account types that are no installed app's package (e.g. com.google). */
        const val NOT_INSTALLED = "\u0000"

        /** Cap on ids taken from the provider's name filter (a one-letter query can match everyone). */
        const val MAX_PROVIDER_HITS = 1000
    }
}
