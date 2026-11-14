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
            whatsapp = listOf(
                WhatsAppEntry("972541234567", "972541234567@s.whatsapp.net", "com.whatsapp"),
                WhatsAppEntry("972541234567", "972541234567@s.whatsapp.net", "com.whatsapp.w4b"),
            ),
            starred = true,
            score = 0.9,
        )
        assertEquals(
            """{"id":42,"name":"Tania Cohen",""" +
                """"phones":[{"number":"054-123-4567","type":"mobile","normalized":"+972541234567"},{"number":"*3456","type":"other"}],""" +
                """"emails":["tania@example.com"],""" +
                """"whatsapp":[{"number":"+972541234567","jid":"972541234567@s.whatsapp.net"},""" +
                """{"number":"+972541234567","jid":"972541234567@s.whatsapp.net","business":true}],""" +
                """"starred":true}""",
            ContactLookup.toJson(match).toString(),
        )
    }
}
