package dev.phonebridge.android.tools.whatsapp

import android.Manifest
import android.content.Context
import dev.phonebridge.android.access.TextNorm
import dev.phonebridge.android.tools.support.ContactLookup
import dev.phonebridge.android.tools.support.Perms
import dev.phonebridge.android.tools.support.PhoneNumbers
import dev.phonebridge.core.tools.ToolException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A resolved WhatsApp recipient. [digits] are international digits without '+'. */
class WhatsAppTarget(val displayName: String, val digits: String, val expected: ExpectedChat) {
    /** `Tania Cohen (+972541234567)` or just `+972541234567`. */
    val description: String get() = if (TextNorm.digits(displayName) == digits) "+$digits" else "$displayName (+$digits)"
}

/** Resolves `to` / `contact` arguments (contacts are read on Dispatchers.IO). */
object WhatsAppTargets {
    suspend fun resolve(context: Context, to: String?, contact: String?): WhatsAppTarget = withContext(Dispatchers.IO) {
        when {
            to != null && contact != null ->
                throw ToolException("Pass either to (phone number) or contact (name), not both.")
            to != null -> {
                val digits = WhatsAppLinks.requireValidDigits(PhoneNumbers.toWhatsAppDigits(context, to), to)
                val names = savedNamesFor(context, digits)
                WhatsAppTarget(to.trim(), digits, ExpectedChat(digits, names, namesAuthoritative = false))
            }
            contact != null -> {
                Perms.require(context, Manifest.permission.READ_CONTACTS)
                val recipient = ContactLookup.resolveRecipient(context, to = null, contact = contact, preferWhatsApp = true)
                val digits = WhatsAppLinks.requireValidDigits(PhoneNumbers.toWhatsAppDigits(context, recipient.number), recipient.number)
                val names = listOfNotNull(recipient.displayName, recipient.contact?.name).distinct()
                WhatsAppTarget(recipient.displayName, digits, ExpectedChat(digits, names, namesAuthoritative = recipient.contact != null))
            }
            else -> throw ToolException(
                "Pass to (phone number in international format, e.g. +972541234567) or contact (a name from the phone's contacts).",
            )
        }
    }

    /**
     * Names under which [digits] is saved in contacts (WhatsApp titles chats with them). Best
     * effort: empty without contacts permission or on lookup errors.
     */
    private fun savedNamesFor(context: Context, digits: String): List<String> {
        if (!Perms.has(context, Manifest.permission.READ_CONTACTS)) return emptyList()
        return try {
            ContactLookup.search(context, "+$digits", limit = 5)
                .filter { m ->
                    m.whatsapp.any { it.number == digits } ||
                        m.phones.any { p -> WhatsAppScreens.digitsMatch(TextNorm.digits(p.normalized ?: p.number), digits) }
                }
                .map { it.name }
        } catch (e: ToolException) {
            emptyList()
        } catch (e: RuntimeException) {
            emptyList()
        }
    }
}
