package dev.phonebridge.android.tools.comms

import dev.phonebridge.android.tools.support.ContactMatch
import dev.phonebridge.android.tools.support.PhoneEntry

/** Outcome of picking one contact from search results. */
internal sealed interface ContactChoice {
    data class Chosen(val match: ContactMatch) : ContactChoice

    /** [weak]: nothing matched well enough to pick automatically (the best is below [NameMatching.AUTO_PICK]). */
    data class Ambiguous(val candidates: List<ContactMatch>, val weak: Boolean) : ContactChoice

    data object NotFound : ContactChoice
}

/** Outcome of picking the number to message/call for a contact. */
internal sealed interface NumberChoice {
    data class Chosen(val number: String) : NumberChoice

    /** [options] are "number (label)" strings for the error message. */
    data class Ambiguous(val options: List<String>, val kind: String) : NumberChoice

    data object NoNumber : NumberChoice
}

/**
 * The "never guess" rules for turning a contact name into a target (pure, unit-tested):
 *
 * * A unique exact match (score 1.0) wins.
 * * Otherwise the best match wins only if it scores at least [NameMatching.AUTO_PICK] (0.85) and
 *   the runner-up scores below [RUNNER_UP_MAX] (0.75). Only matches on whole name tokens score that
 *   high ("Tania" or "Cohen" → "Tania Cohen"): a query that only starts a name ("Dan" → "Danielle
 *   Smith") is never picked, and another contact that starts with the query ("Dana Levi") keeps a
 *   whole-token match ("Dan Katz") from being picked (see [NameMatching]).
 * * Anything else is ambiguous: the agent gets up to [MAX_CANDIDATES] candidates to ask the user about.
 *
 * Several exact matches that share exactly the same phone numbers are treated as duplicates of one
 * person (unmerged contacts) and the first one wins.
 */
internal object RecipientSelection {
    const val RUNNER_UP_MAX = 0.75
    const val MAX_CANDIDATES = 8
    private const val EPSILON = 1e-9
    private const val NUMBERS_PER_CANDIDATE = 2

    fun chooseContact(matches: List<ContactMatch>): ContactChoice {
        if (matches.isEmpty()) return ContactChoice.NotFound
        val sorted = matches.sortedByDescending { it.score }
        val exact = sorted.filter { it.score >= NameMatching.EXACT - EPSILON }
        if (exact.size == 1) return ContactChoice.Chosen(exact.single())
        if (exact.size > 1) {
            return if (areDuplicates(exact)) ContactChoice.Chosen(exact.first())
            else ContactChoice.Ambiguous(sorted.take(MAX_CANDIDATES), weak = false)
        }
        val top = sorted.first()
        val runnerUp = sorted.getOrNull(1)
        val strong = top.score >= NameMatching.AUTO_PICK - EPSILON
        if (strong && (runnerUp == null || runnerUp.score < RUNNER_UP_MAX)) return ContactChoice.Chosen(top)
        return ContactChoice.Ambiguous(sorted.take(MAX_CANDIDATES), weak = !strong)
    }

    private fun areDuplicates(matches: List<ContactMatch>): Boolean {
        val first = numberKeys(matches.first())
        return first.isNotEmpty() && matches.all { numberKeys(it) == first }
    }

    private fun numberKeys(match: ContactMatch): Set<String> =
        match.phones.mapTo(HashSet()) { it.normalized ?: PhoneText.comparableDigits(it.number) }

    /**
     * WhatsApp numbers first when [preferWhatsApp] (falling back to phones if the contact has none),
     * else the single mobile number, else the single number of any type. Several distinct
     * candidates at the deciding step are ambiguous.
     */
    fun chooseNumber(match: ContactMatch, preferWhatsApp: Boolean): NumberChoice {
        if (preferWhatsApp) {
            val whatsapp = match.whatsapp.map { "+" + it.number }.distinct()
            if (whatsapp.size == 1) return NumberChoice.Chosen(whatsapp.single())
            if (whatsapp.size > 1) return NumberChoice.Ambiguous(whatsapp.map { "$it (WhatsApp)" }, "WhatsApp numbers")
        }
        val phones = distinctPhones(match.phones)
        val mobiles = phones.filter { PhoneTypes.isMobile(it.type) }
        return when {
            mobiles.size == 1 -> NumberChoice.Chosen(dialable(mobiles.single()))
            mobiles.size > 1 -> NumberChoice.Ambiguous(mobiles.map(::describe), "mobile numbers")
            phones.size == 1 -> NumberChoice.Chosen(dialable(phones.single()))
            phones.size > 1 -> NumberChoice.Ambiguous(phones.map(::describe), "phone numbers")
            else -> NumberChoice.NoNumber
        }
    }

    /** E.164 when known, else the number as stored (formatting removed when possible). */
    fun dialable(phone: PhoneEntry): String = phone.normalized ?: PhoneText.clean(phone.number) ?: phone.number.trim()

    private fun distinctPhones(phones: List<PhoneEntry>): List<PhoneEntry> =
        phones.distinctBy { it.normalized ?: PhoneText.comparableDigits(it.number) }

    private fun describe(phone: PhoneEntry): String = "${dialable(phone)} (${phone.type})"

    /** "Tania Cohen (+972541234567)", "Tania Cohen (+972541234567, +97231234567)" or "Tania Cohen (no phone number)". */
    fun describeCandidate(match: ContactMatch): String {
        val phones = distinctPhones(match.phones).sortedByDescending { PhoneTypes.isMobile(it.type) }
        val numbers = phones.map(::dialable).ifEmpty { match.whatsapp.map { "+" + it.number }.distinct() }
        val detail = when {
            numbers.isEmpty() -> "no phone number"
            numbers.size > NUMBERS_PER_CANDIDATE -> numbers.take(NUMBERS_PER_CANDIDATE).joinToString(", ") + ", …"
            else -> numbers.joinToString(", ")
        }
        return "${match.name} ($detail)"
    }

    // --- Agent-facing error messages. [numberArg] is the tool argument that takes a raw number. ---

    fun notFoundMessage(query: String, numberArg: String): String =
        "No contact matches \"$query\". Check the spelling or call contacts_search with part of the name. " +
            "If the user gave a phone number, pass it as '$numberArg' instead."

    fun ambiguousMessage(query: String, choice: ContactChoice.Ambiguous, numberArg: String): String {
        val list = choice.candidates.joinToString("; ", transform = ::describeCandidate)
        return if (choice.weak && choice.candidates.size == 1) {
            "No contact closely matches \"$query\". The closest is $list. Ask the user whether that is the right person, " +
                "then retry with the exact contact name or pass the number as '$numberArg'."
        } else {
            val lead = if (choice.weak) "No contact closely matches \"$query\". Closest" else "Several contacts match \"$query\""
            "$lead: $list. Ask the user which one they mean, then retry with that exact contact name or pass the number as '$numberArg'."
        }
    }

    fun noNumberMessage(match: ContactMatch, numberArg: String): String =
        "Contact \"${match.name}\" has no phone number saved. Ask the user for the number and pass it as '$numberArg'."

    fun ambiguousNumberMessage(match: ContactMatch, choice: NumberChoice.Ambiguous, numberArg: String): String =
        "Contact \"${match.name}\" has several ${choice.kind}: ${choice.options.joinToString(", ")}. " +
            "Ask the user which one to use and pass it as '$numberArg'."
}
