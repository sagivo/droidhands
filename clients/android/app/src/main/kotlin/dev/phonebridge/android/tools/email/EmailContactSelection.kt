package dev.phonebridge.android.tools.email

import dev.phonebridge.android.tools.support.ContactMatch
import java.util.Locale

/** Outcome of turning a contact name into an email recipient. */
sealed interface EmailContactChoice {
    data class Chosen(val recipient: EmailRecipient) : EmailContactChoice

    /** Several contacts fit, or ([weak]) none fits well enough to pick automatically. */
    data class AmbiguousContacts(val candidates: List<ContactMatch>, val weak: Boolean) : EmailContactChoice

    data class AmbiguousEmails(val contact: ContactMatch, val emails: List<String>) : EmailContactChoice

    data class NoEmail(val contact: ContactMatch) : EmailContactChoice

    data object NotFound : EmailContactChoice
}

/**
 * The "never guess" rules of docs/TOOLS.md applied to email (pure). They mirror the SMS/call rules: a unique
 * exact name match wins; otherwise the best match wins only if it scores at least [AUTO_PICK] and the
 * runner-up scores below [RUNNER_UP_MAX]. Exact duplicates with the same addresses count as one person.
 * The chosen contact must have exactly one (valid) email address.
 */
object EmailContactSelection {
    const val AUTO_PICK = 0.85
    const val RUNNER_UP_MAX = 0.75
    const val MAX_CANDIDATES = 8
    private const val EXACT = 1.0
    private const val EPSILON = 1e-9

    fun choose(matches: List<ContactMatch>): EmailContactChoice {
        if (matches.isEmpty()) return EmailContactChoice.NotFound
        val sorted = matches.sortedByDescending { it.score }
        val exact = sorted.filter { it.score >= EXACT - EPSILON }
        val contact = when {
            exact.size == 1 -> exact.single()
            exact.size > 1 && areDuplicates(exact) -> exact.first()
            exact.size > 1 -> return EmailContactChoice.AmbiguousContacts(sorted.take(MAX_CANDIDATES), weak = false)
            else -> {
                val top = sorted.first()
                val runnerUp = sorted.getOrNull(1)
                val strong = top.score >= AUTO_PICK - EPSILON
                if (strong && (runnerUp == null || runnerUp.score < RUNNER_UP_MAX)) {
                    top
                } else {
                    return EmailContactChoice.AmbiguousContacts(sorted.take(MAX_CANDIDATES), weak = !strong)
                }
            }
        }
        val emails = emailsOf(contact)
        return when (emails.size) {
            0 -> EmailContactChoice.NoEmail(contact)
            1 -> EmailContactChoice.Chosen(EmailRecipient(emails.single(), contact.name))
            else -> EmailContactChoice.AmbiguousEmails(contact, emails)
        }
    }

    /** Unmerged copies of one person: the same non-empty set of addresses (case-insensitive). */
    private fun areDuplicates(contacts: List<ContactMatch>): Boolean {
        val sets = contacts.map { c -> emailsOf(c).map { it.lowercase(Locale.ROOT) }.toSet() }
        return sets.first().isNotEmpty() && sets.all { it == sets.first() }
    }

    /** The contact's valid addresses, de-duplicated case-insensitively, in stored order. */
    fun emailsOf(contact: ContactMatch): List<String> =
        contact.emails.map { it.trim() }.filter(EmailAddresses::isValid).distinctBy { it.lowercase(Locale.ROOT) }

    /** Agent-facing error for every non-[EmailContactChoice.Chosen] outcome. */
    fun message(query: String, choice: EmailContactChoice): String = when (choice) {
        is EmailContactChoice.Chosen -> "Resolved \"$query\" to ${choice.recipient.display}."
        EmailContactChoice.NotFound ->
            "No contact matches \"$query\". Check the spelling or call contacts_search with part of the name. " +
                "If the user gave an email address, pass it as 'to' instead."
        is EmailContactChoice.AmbiguousContacts -> {
            val list = choice.candidates.joinToString("; ", transform = ::describe)
            when {
                choice.weak && choice.candidates.size == 1 ->
                    "No contact closely matches \"$query\". The closest is $list. Ask the user whether that is the right person, " +
                        "then retry with the exact contact name or pass the address as 'to'."
                choice.weak ->
                    "No contact closely matches \"$query\". Closest: $list. Ask the user which one they mean, then retry with " +
                        "that exact contact name or pass the address as 'to'."
                else ->
                    "Several contacts match \"$query\": $list. Ask the user which one they mean, then retry with that exact " +
                        "contact name or pass the address as 'to'."
            }
        }
        is EmailContactChoice.AmbiguousEmails ->
            "Contact \"${choice.contact.name}\" has several email addresses: ${choice.emails.joinToString(", ")}. " +
                "Ask the user which one to use and pass it as 'to'."
        is EmailContactChoice.NoEmail ->
            "Contact \"${choice.contact.name}\" has no email address saved. Ask the user for the address and pass it as 'to'."
    }

    /** `Tania Cohen (tania@x.com)`, `Tania Cohen (a@x.com, b@y.com)` or `Tania Cohen (no email)`. */
    fun describe(contact: ContactMatch): String {
        val emails = emailsOf(contact)
        val detail = when {
            emails.isEmpty() -> "no email"
            emails.size > 2 -> emails.take(2).joinToString(", ") + ", …"
            else -> emails.joinToString(", ")
        }
        return "${contact.name} ($detail)"
    }
}
