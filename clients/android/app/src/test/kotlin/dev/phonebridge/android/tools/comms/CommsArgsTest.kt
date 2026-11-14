package dev.phonebridge.android.tools.comms

import dev.phonebridge.android.tools.support.Recipient
import dev.phonebridge.core.tools.ToolException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CommsArgsTest {
    private fun args(vararg pairs: Pair<String, Any>): JsonObject = JsonObject(
        pairs.associate { (k, v) ->
            k to when (v) {
                is Number -> JsonPrimitive(v)
                is Boolean -> JsonPrimitive(v)
                else -> JsonPrimitive(v.toString())
            }
        },
    )

    @Test
    fun limitDefaultsAndClamps() {
        assertEquals(20, CommsArgs.limit(args(), 20, 200))
        assertEquals(5, CommsArgs.limit(args("limit" to 5), 20, 200))
        assertEquals(200, CommsArgs.limit(args("limit" to 5000), 20, 200))
        assertEquals(1, CommsArgs.limit(args("limit" to 0), 20, 200))
        assertEquals(7, CommsArgs.limit(args("limit" to "7"), 20, 200))
    }

    @Test
    fun choices() {
        val allowed = listOf("all", "inbox", "sent")
        assertEquals("all", CommsArgs.choice(args(), "box", allowed, "all"))
        assertEquals("inbox", CommsArgs.choice(args("box" to " Inbox "), "box", allowed, "all"))
        val error = assertThrows(ToolException::class.java) { CommsArgs.choice(args("box" to "spam"), "box", allowed, "all") }
        assertTrue(error.message!!.contains("all, inbox, sent"))
    }

    @Test
    fun sinceHours() {
        val now = 10_000_000_000L
        assertNull(CommsArgs.sinceMillis(args(), now))
        assertEquals(now - 2 * 3_600_000, CommsArgs.sinceMillis(args("since_hours" to 2), now))
        assertEquals(now - 1_800_000, CommsArgs.sinceMillis(args("since_hours" to 0.5), now))
        assertNull(CommsArgs.sinceMillis(args("since_hours" to 1_000_000), now))
        assertThrows(ToolException::class.java) { CommsArgs.sinceMillis(args("since_hours" to 0), now) }
        assertThrows(ToolException::class.java) { CommsArgs.sinceMillis(args("since_hours" to -3), now) }
    }

    @Test
    fun addressOrContact() {
        assertEquals("+1555" to null, CommsArgs.addressOrContact(args("address" to " +1555 ")))
        assertEquals(null to "Tania", CommsArgs.addressOrContact(args("contact" to "Tania")))
        assertEquals(null to null, CommsArgs.addressOrContact(buildJsonObject { put("address", "  ") }))
        assertThrows(ToolException::class.java) { CommsArgs.addressOrContact(args("address" to "1", "contact" to "Tania")) }
    }

    @Test
    fun recipientLabels() {
        assertEquals("Tania Cohen (+972541234567)", CommsArgs.label(Recipient("Tania Cohen", "+972541234567", null)))
        assertEquals("+972541234567", CommsArgs.label(Recipient("+972541234567", "+972541234567", null)))
    }

    @Test
    fun addressFilters() {
        val byNumber = AddressFilter.forAddress("054-123-4567")
        assertTrue(byNumber.matches("+972541234567"))
        assertFalse(byNumber.matches("+972541234568"))
        assertFalse(byNumber.matches(null))

        val bySender = AddressFilter.forAddress("google")
        assertTrue(bySender.matches("Google"))
        assertTrue(bySender.matches("GOOGLE-PAY"))
        assertFalse(bySender.matches("+972541234567"))

        val byContact = AddressFilter.forNumbers(listOf("+972541234567", "03-1234567", ""))
        assertTrue(byContact.matches("0541234567"))
        assertTrue(byContact.matches("+97231234567"))
        assertFalse(byContact.matches("Google"))
    }
}
