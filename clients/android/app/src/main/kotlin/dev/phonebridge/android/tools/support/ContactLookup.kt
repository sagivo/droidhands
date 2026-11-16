package dev.phonebridge.android.tools.support

import android.Manifest
import android.content.Context
import dev.phonebridge.android.tools.comms.CONTACTS_ACCESS_DENIED
import dev.phonebridge.android.tools.comms.ContactsRepository
import dev.phonebridge.android.tools.comms.E164Formatter
import dev.phonebridge.android.tools.comms.PhoneText
import dev.phonebridge.android.tools.comms.RecipientResolver
import dev.phonebridge.android.tools.comms.withProviderAccess
import dev.phonebridge.core.tools.ToolException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

data class PhoneEntry(
    /** As stored in contacts. */
    val number: String,
    /** E.164 (e.g. +972541234567) if it could be determined, else null. */
    val normalized: String?,
    /** "mobile", "home", "work", "other" or the custom label. */
    val type: String,
)

/**
 * An app the contact can be reached in: an installed app that syncs its own raw contact into the address book
 * (messaging and calling apps do: WhatsApp, Telegram, Signal, Viber…), with the entries it adds, as the Contacts app
 * shows them ("Message +1 555 123 4567", "Voice call …"). Read generically from the contacts provider: nothing here
 * knows a particular app.
 */
data class ContactAppEntry(
    val packageName: String,
    /** The app's name, if it could be read. */
    val label: String?,
    val entries: List<String>,
)

data class ContactMatch(
    val id: Long,
    val name: String,
    val phones: List<PhoneEntry>,
    val emails: List<String>,
    /** Apps that synced this contact ([ContactAppEntry]). */
    val apps: List<ContactAppEntry> = emptyList(),
    val starred: Boolean,
    /** 0..1 relevance (1 = exact). */
    val score: Double,
)

/** A resolved message/call target. */
data class Recipient(
    /** "Tania Cohen" or the raw number when no contact. */
    val displayName: String,
    /** Dialable number: E.164 when known (e.g. +972541234567), else as given/stored. */
    val number: String,
    val contact: ContactMatch?,
)

/**
 * Contact search + recipient resolution shared by the contacts, SMS and call tools.
 *
 * All functions block on ContentResolver IPC: call them from `Dispatchers.IO`. Failures the agent
 * can act on (missing permission, no or several matching contacts, no usable number) are thrown
 * as [ToolException]. Matching logic: [dev.phonebridge.android.tools.comms.NameMatching]; selection
 * rules: [dev.phonebridge.android.tools.comms.RecipientSelection].
 */
object ContactLookup {
    private const val MAX_LIMIT = 200

    /** Exact/prefix matches first, then fuzzy & transliteration-tolerant. Requires READ_CONTACTS. */
    fun search(context: Context, query: String, limit: Int = 10): List<ContactMatch> {
        Perms.require(context, Manifest.permission.READ_CONTACTS)
        return withProviderAccess(CONTACTS_ACCESS_DENIED) {
            ContactsRepository(context).search(query, limit.coerceIn(1, MAX_LIMIT))
        }
    }

    /**
     * Resolves a target from tool args. Exactly one of [to] (phone number) / [contact] (name) must be
     * non-null. For names: exactly one contact must match (else ToolException listing candidates);
     * that contact must have a usable number (mobile first; ambiguous multiple mobiles → ToolException listing them so
     * the agent can pass `to`).
     */
    fun resolveRecipient(context: Context, to: String?, contact: String?): Recipient =
        RecipientResolver(context).resolve(to, contact)

    /** `{id,name,phones:[{number,type,normalized}],emails,apps:[{package,app?,entries}],starred}`. */
    fun toJson(match: ContactMatch): JsonObject = buildJsonObject {
        put("id", match.id)
        put("name", match.name)
        putJsonArray("phones") {
            for (phone in match.phones) {
                addJsonObject {
                    put("number", phone.number)
                    put("type", phone.type)
                    phone.normalized?.let { put("normalized", it) }
                }
            }
        }
        putJsonArray("emails") { match.emails.forEach { add(it) } }
        if (match.apps.isNotEmpty()) {
            putJsonArray("apps") {
                for (app in match.apps) {
                    addJsonObject {
                        put("package", app.packageName)
                        app.label?.let { put("app", it) }
                        putJsonArray("entries") { app.entries.forEach { add(it) } }
                    }
                }
            }
        }
        put("starred", match.starred)
    }
}

/** Phone-number normalization using the network/SIM country as default region. */
object PhoneNumbers {
    /** Best-effort E.164 ("+972541234567") or null if it can't be determined. */
    fun toE164(context: Context, raw: String): String? = E164Formatter.toE164(context, raw)

}
