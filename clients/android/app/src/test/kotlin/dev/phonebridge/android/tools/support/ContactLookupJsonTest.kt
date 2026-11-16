package dev.phonebridge.android.tools.support

import org.junit.Assert.assertEquals
import org.junit.Test

class ContactLookupJsonTest {
    @Test
    fun contactJsonShape() {
        val match = ContactMatch(
            id = 42,
            name = "Tania Cohen",
            phones = listOf(PhoneEntry("054-123-4567", "+972541234567", "mobile"), PhoneEntry("*3456", null, "other")),
            emails = listOf("tania@example.com"),
            apps = listOf(ContactAppEntry("com.chat", "Chat", listOf("Message +972 54-123-4567")), ContactAppEntry("org.talk", null, emptyList())),
            starred = true,
            score = 0.9,
        )
        assertEquals(
            """{"id":42,"name":"Tania Cohen",""" +
                """"phones":[{"number":"054-123-4567","type":"mobile","normalized":"+972541234567"},{"number":"*3456","type":"other"}],""" +
                """"emails":["tania@example.com"],""" +
                """"apps":[{"package":"com.chat","app":"Chat","entries":["Message +972 54-123-4567"]},{"package":"org.talk","entries":[]}],""" +
                """"starred":true}""",
            ContactLookup.toJson(match).toString(),
        )
    }

    @Test
    fun contactsWithoutAppsHaveNoAppsField() {
        val match = ContactMatch(1, "A", emptyList(), emptyList(), starred = false, score = 1.0)
        assertEquals("""{"id":1,"name":"A","phones":[],"emails":[],"starred":false}""", ContactLookup.toJson(match).toString())
    }
}
