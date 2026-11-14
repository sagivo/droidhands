package dev.phonebridge.core.plugins

/**
 * The sensitive-word floor (docs/PLUGINS.md §9): an action whose id, title or confirm text says it sends,
 * posts, pays, buys, orders, books, calls, deletes or transfers must have risk `sensitive`. Pure.
 *
 * Matching is by whole word, ignoring case: text is split on everything that is not a letter or digit,
 * so the id `send_message` contains the word `send`, and `Sender` or `postcard` contain nothing.
 * Placeholders (`{{order_id}}`) are ignored. [WORDS] holds the base verbs, their -ing forms and the
 * third-person forms that are rarely nouns. Nouns spelled like a third-person verb (`orders`, `posts`,
 * `books`, `calls`) and past participles used as adjectives (`sent mail`, `booked trips`) are not
 * matched, which keeps false alarms on read-only actions rare. A word used as a noun can still match
 * (`Track order`): then rephrase the title or mark the action `sensitive`.
 */
object SensitiveWords {
    val WORDS: Set<String> = setOf(
        "send", "sends", "sending",
        "post", "posting",
        "pay", "pays", "paying", "payment",
        "buy", "buys", "buying", "purchase", "purchases", "purchasing", "checkout",
        "order", "ordering",
        "book", "booking",
        "call", "calling", "dial", "dials", "dialing", "dialling",
        "delete", "deletes", "deleting",
        "transfer", "transfers", "transferring",
    )

    private val PLACEHOLDER = Regex("\\{\\{[^}]*\\}\\}")
    private val SEPARATORS = Regex("[^\\p{L}\\p{N}]+")

    /** The first sensitive word in [text] (lowercase), or null. */
    fun find(text: String): String? =
        text.replace(PLACEHOLDER, " ").lowercase().split(SEPARATORS).firstOrNull { it in WORDS }
}
