package dev.phonebridge.android.tools.support

import android.content.Context
import kotlinx.serialization.json.JsonObject

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
 * STUB — implemented by the comms agent.
 */
object ContactLookup {
    /** Exact/prefix matches first, then fuzzy & transliteration-tolerant. Requires READ_CONTACTS. */
    fun search(context: Context, query: String, limit: Int = 10): List<ContactMatch> = TODO()

    /**
     * Resolves a target from tool args. Exactly one of [to] (phone number) / [contact] (name) must be
     * non-null. For names: exactly one contact must match (else ToolException listing candidates);
     * that contact must have a usable number (prefers WhatsApp numbers when [preferWhatsApp], else mobile;
     * ambiguous multiple mobiles → ToolException listing them so the agent can pass `to`).
     */
    fun resolveRecipient(context: Context, to: String?, contact: String?, preferWhatsApp: Boolean = false): Recipient = TODO()

    fun toJson(match: ContactMatch): JsonObject = TODO()
}

/** Phone-number normalization using the SIM/network country as default region. STUB — comms agent. */
object PhoneNumbers {
    /** Best-effort E.164 ("+972541234567") or null if it can't be determined. */
    fun toE164(context: Context, raw: String): String? = TODO()

    /** International digits without '+' (WhatsApp wa.me format), or null. */
    fun toWhatsAppDigits(context: Context, raw: String): String? = TODO()
}
