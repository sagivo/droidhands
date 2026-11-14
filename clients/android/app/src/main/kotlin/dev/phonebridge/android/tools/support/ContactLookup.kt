package dev.phonebridge.android.tools.support

import android.Manifest
import android.content.Context
import dev.phonebridge.android.tools.comms.CONTACTS_ACCESS_DENIED
import dev.phonebridge.android.tools.comms.ContactsRepository
import dev.phonebridge.android.tools.comms.E164Formatter
import dev.phonebridge.android.tools.comms.PhoneText
import dev.phonebridge.android.tools.comms.RecipientResolver
import dev.phonebridge.android.tools.comms.WhatsAppRows
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

data class WhatsAppEntry(
    /** International digits without '+', e.g. 972541234567. */
    val number: String,
    /** e.g. 972541234567@s.whatsapp.net */
    val jid: String,
    /** WhatsApp package that synced it: com.whatsapp or com.whatsapp.w4b. */
    val packageName: String,
)

data class ContactMatch(
    val id: Long,
    val name: String,
    val phones: List<PhoneEntry>,
    val emails: List<String>,
    val whatsapp: List<WhatsAppEntry>,
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
 * Contact search + recipient resolution shared by contacts/SMS/call/WhatsApp tools.
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
     * that contact must have a usable number (prefers WhatsApp numbers when [preferWhatsApp], else mobile;
     * ambiguous multiple mobiles → ToolException listing them so the agent can pass `to`).
     */
    fun resolveRecipient(context: Context, to: String?, contact: String?, preferWhatsApp: Boolean = false): Recipient =
        RecipientResolver(context).resolve(to, contact, preferWhatsApp)

    /**
     * `{id,name,phones:[{number,type,normalized}],emails,whatsapp:[{number,jid,business?}],starred}`.
     * WhatsApp numbers are given with '+' so they can be passed back as `to` unambiguously.
     */
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
        putJsonArray("whatsapp") {
            for (entry in match.whatsapp) {
                addJsonObject {
                    put("number", "+" + entry.number)
                    put("jid", entry.jid)
                    if (entry.packageName == WhatsAppRows.BUSINESS_PACKAGE) put("business", true)
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

    /**
     * International digits without '+' (WhatsApp wa.me format), or null. Like call and SMS targets,
     * only plain phone numbers are accepted ([PhoneText.cleanDialable]: no `*`/`#` codes, letters or
     * extensions).
     */
    fun toWhatsAppDigits(context: Context, raw: String): String? =
        PhoneText.cleanDialable(raw)?.let { toE164(context, it) }?.removePrefix("+")
}
