package dev.phonebridge.android.tools.email

import android.content.Context
import dev.phonebridge.android.tools.support.ContactLookup
import dev.phonebridge.core.tools.ToolException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Resolves email_send's `to` / `contact` arguments (contacts are read on Dispatchers.IO). */
object EmailRecipients {
    private const val SEARCH_LIMIT = 10

    suspend fun resolve(context: Context, to: String?, contact: String?): List<EmailRecipient> {
        val addresses = to?.trim()?.takeIf { it.isNotEmpty() }
        val name = contact?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            addresses != null && name != null ->
                throw ToolException("Pass either 'to' (email addresses) or 'contact' (a contact name), not both.")
            addresses != null -> EmailAddresses.parseList(addresses, "to")
            // An address passed as 'contact' means that address.
            name != null && EmailAddresses.looksLikeAddress(name) -> EmailAddresses.parseList(name, "contact")
            name != null -> listOf(fromContact(context, name))
            else -> throw ToolException("Missing recipient: pass 'to' (email addresses, comma-separated) or 'contact' (a contact name).")
        }
    }

    /** Requires READ_CONTACTS (ContactLookup throws an actionable error without it). */
    private suspend fun fromContact(context: Context, name: String): EmailRecipient = withContext(Dispatchers.IO) {
        val matches = ContactLookup.search(context, name, SEARCH_LIMIT)
        when (val choice = EmailContactSelection.choose(matches)) {
            is EmailContactChoice.Chosen -> choice.recipient
            else -> throw ToolException(EmailContactSelection.message(name, choice))
        }
    }
}
