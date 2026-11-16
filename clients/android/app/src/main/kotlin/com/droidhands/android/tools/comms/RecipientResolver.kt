package com.droidhands.android.tools.comms

import android.Manifest
import android.content.Context
import android.util.Log
import com.droidhands.android.tools.support.ContactMatch
import com.droidhands.android.tools.support.Perms
import com.droidhands.android.tools.support.Recipient
import com.droidhands.core.tools.ToolException

/**
 * Turns `to`/`contact` tool arguments into a [Recipient] (see
 * [com.droidhands.android.tools.support.ContactLookup.resolveRecipient]). Blocking: call from
 * Dispatchers.IO.
 */
internal class RecipientResolver(private val context: Context) {
    private val contacts by lazy(LazyThreadSafetyMode.NONE) { ContactsRepository(context) }

    fun resolve(to: String?, contact: String?): Recipient {
        val number = to?.trim()?.takeIf { it.isNotEmpty() }
        val name = contact?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            number != null && name != null -> throw ToolException(
                "Pass either 'to' (phone number) or 'contact' (contact name), not both.",
            )
            number != null -> forNumber(number, arg = "to")
            // A number passed as 'contact' means that number, not "some number of whoever has it".
            name != null && PhoneText.looksLikePhoneNumber(name) -> forNumber(name, arg = "contact")
            name != null -> forContact(name)
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

    private fun forContact(name: String): Recipient {
        val match = resolveContact(name, numberArg = "to")
        return when (val choice = RecipientSelection.chooseNumber(match)) {
            is NumberChoice.Chosen -> Recipient(displayName = match.name, number = choice.number, contact = match)
            is NumberChoice.Ambiguous -> throw ToolException(RecipientSelection.ambiguousNumberMessage(match, choice, "to"))
            NumberChoice.NoNumber -> throw ToolException(RecipientSelection.noNumberMessage(match, "to"))
        }
    }

    /** [raw] given as argument [arg]: only plain phone numbers, never USSD/MMI codes ([PhoneText.cleanDialable]). */
    private fun forNumber(raw: String, arg: String): Recipient {
        val cleaned = PhoneText.cleanDialable(raw) ?: throw ToolException(PhoneText.notDialableMessage(raw, arg))
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
        const val TAG = "DroidHands/Recipient"

        /** Enough to see the runner-up and list candidates. */
        const val SEARCH_LIMIT = 10
    }
}
