package dev.phonebridge.android.tools.comms

import android.Manifest
import android.content.Context
import android.util.Log
import dev.phonebridge.android.tools.support.ContactMatch
import dev.phonebridge.android.tools.support.Perms
import dev.phonebridge.android.tools.support.Recipient
import dev.phonebridge.core.tools.ToolException

/**
 * Turns `to`/`contact` tool arguments into a [Recipient] (see
 * [dev.phonebridge.android.tools.support.ContactLookup.resolveRecipient]). Blocking: call from
 * Dispatchers.IO.
 */
internal class RecipientResolver(private val context: Context) {
    private val contacts by lazy(LazyThreadSafetyMode.NONE) { ContactsRepository(context) }

    fun resolve(to: String?, contact: String?, preferWhatsApp: Boolean): Recipient {
        val number = to?.trim()?.takeIf { it.isNotEmpty() }
        val name = contact?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            number != null && name != null -> throw ToolException(
                "Pass either 'to' (phone number) or 'contact' (contact name), not both.",
            )
            number != null -> forNumber(number)
            // A number passed as 'contact' means that number, not "some number of whoever has it".
            name != null && PhoneText.looksLikePhoneNumber(name) -> forNumber(name)
            name != null -> forContact(name, preferWhatsApp)
            else -> throw ToolException("Missing recipient: pass 'to' (phone number) or 'contact' (contact name).")
        }
    }

    /**
     * The single contact [name] refers to, under the rules of [RecipientSelection.chooseContact];
     * otherwise a ToolException listing candidates. [numberArg] names the argument that accepts a
     * raw number instead ("to", "address"), for the error message.
     */
    fun resolveContact(name: String, numberArg: String): ContactMatch {
        Perms.require(context, Manifest.permission.READ_CONTACTS)
        val matches = withProviderAccess(CONTACTS_ACCESS_DENIED) { contacts.search(name, SEARCH_LIMIT) }
        return when (val choice = RecipientSelection.chooseContact(matches)) {
            is ContactChoice.Chosen -> choice.match
            is ContactChoice.Ambiguous -> throw ToolException(RecipientSelection.ambiguousMessage(name, choice, numberArg))
            ContactChoice.NotFound -> throw ToolException(RecipientSelection.notFoundMessage(name, numberArg))
        }
    }

    private fun forContact(name: String, preferWhatsApp: Boolean): Recipient {
        val match = resolveContact(name, numberArg = "to")
        return when (val choice = RecipientSelection.chooseNumber(match, preferWhatsApp)) {
            is NumberChoice.Chosen -> Recipient(displayName = match.name, number = choice.number, contact = match)
            is NumberChoice.Ambiguous -> throw ToolException(RecipientSelection.ambiguousNumberMessage(match, choice, "to"))
            NumberChoice.NoNumber -> throw ToolException(RecipientSelection.noNumberMessage(match, "to"))
        }
    }

    private fun forNumber(raw: String): Recipient {
        val cleaned = PhoneText.clean(raw)
        if (cleaned == null || PhoneText.digitCount(cleaned) < MIN_DIGITS) {
            throw ToolException(
                "'to' must be a phone number, preferably international (e.g. +14155550123). " +
                    "To use a contact name, pass it as 'contact' instead.",
            )
        }
        val number = E164Formatter.toE164(context, cleaned) ?: PhoneText.internationalize(cleaned)
        val owner = ownerOf(number)
        return Recipient(displayName = owner?.name ?: number, number = number, contact = owner)
    }

    /** Optional: the saved contact for a number, for a friendlier confirmation. Never fails the call. */
    private fun ownerOf(number: String): ContactMatch? {
        if (!Perms.has(context, Manifest.permission.READ_CONTACTS)) return null
        return try {
            contacts.findByNumber(number)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Contact lookup by number failed: ${e.javaClass.simpleName}")
            null
        }
    }

    private companion object {
        const val TAG = "PhoneBridge/Recipient"
        const val MIN_DIGITS = 3

        /** Enough to see the runner-up and list candidates. */
        const val SEARCH_LIMIT = 10
    }
}
