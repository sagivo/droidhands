package dev.phonebridge.android.tools.comms

/**
 * Matches SMS addresses / call-log numbers against what the agent asked for (pure). Phone numbers
 * match loosely with [PhoneText.numbersMatch], so "+972 54-123-4567", "0541234567" and
 * "541234567" are the same. Alphanumeric senders ("Google", "AMAZON") match case-insensitively by
 * substring.
 */
internal class AddressFilter private constructor(
    private val numbers: List<String>,
    private val senderNames: List<String>,
) {
    fun matches(address: String?): Boolean {
        if (address.isNullOrBlank()) return false
        if (numbers.any { PhoneText.numbersMatch(it, address) }) return true
        return senderNames.any { address.contains(it, ignoreCase = true) }
    }

    companion object {
        /** From an `address` argument: a phone number, or an alphanumeric sender name. */
        fun forAddress(address: String): AddressFilter {
            val trimmed = address.trim()
            return if (PhoneText.looksLikePhoneNumber(trimmed)) AddressFilter(listOf(trimmed), emptyList())
            else AddressFilter(emptyList(), listOf(trimmed))
        }

        /** From a contact's numbers. */
        fun forNumbers(numbers: Collection<String>): AddressFilter =
            AddressFilter(numbers.map { it.trim() }.filter { PhoneText.digitCount(it) >= 3 }.distinct(), emptyList())
    }
}
