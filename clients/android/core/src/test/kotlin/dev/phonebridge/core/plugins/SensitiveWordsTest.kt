package dev.phonebridge.core.plugins

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SensitiveWordsTest {
    @Test
    fun findsSensitiveVerbsAsWholeWords() {
        val cases = mapOf(
            "send_message" to "send",
            "Send a message" to "send",
            "SENDING files" to "sending",
            "Post a photo" to "post",
            "Pay a friend" to "pay",
            "pays the bill" to "pays",
            "Buy now" to "buy",
            "Purchase tickets" to "purchase",
            "Order food" to "order",
            "book_ride" to "book",
            "Call {{contact}}" to "call",
            "dial_number" to "dial",
            "Delete the photo" to "delete",
            "transfer_money" to "transfer",
            "Go to checkout" to "checkout",
            "Envoyer / send" to "send",
        )
        for ((text, word) in cases) assertEquals(word, SensitiveWords.find(text), text)
    }

    @Test
    fun ignoresOtherWordsNounsAndPlaceholders() {
        for (text in listOf(
            "Play music", "Search", "open", "share_text", "Sender info", "Open postcard", "Show recent calls",
            "Show sent mail", "Booked trips", "Track orders", "Open {{order_id}}", "Sort by {{call}}", "bookmark", "callback",
            "Repost", "prepay", "",
        )) {
            assertNull(SensitiveWords.find(text), text)
        }
    }
}
