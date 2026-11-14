package dev.phonebridge.android.tools.whatsapp

import dev.phonebridge.core.tools.ToolException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

class WhatsAppLinksTest {
    @Test
    fun `spaces are percent-encoded, never plus`() {
        assertEquals("hello%20world", WhatsAppLinks.encodeText("hello world"))
        assertEquals("1%2B1%3D2%20%26%20more%3F%20%23tag", WhatsAppLinks.encodeText("1+1=2 & more? #tag"))
    }

    @Test
    fun `hebrew, emoji and newlines are UTF-8 percent-encoded`() {
        assertEquals("%D7%A9%D7%9C%D7%95%D7%9D", WhatsAppLinks.encodeText("שלום"))
        assertEquals("%F0%9F%91%8D", WhatsAppLinks.encodeText("👍"))
        assertEquals("line1%0Aline2", WhatsAppLinks.encodeText("line1\nline2"))
        val mixed = "היי טניה 👋\nמגיע ב-8, אוקיי? \"quoted\" & 100% 🇮🇱"
        val encoded = WhatsAppLinks.encodeText(mixed)
        assertFalse(encoded.contains(' ') || encoded.contains('+') || encoded.contains('\n'))
        assertEquals(mixed, URLDecoder.decode(encoded, "UTF-8"))
    }

    @Test
    fun `click to chat urls`() {
        assertEquals(
            "https://api.whatsapp.com/send?phone=972541234567&text=hi%20there",
            WhatsAppLinks.apiSendUrl("972541234567", "hi there"),
        )
        assertEquals("https://api.whatsapp.com/send?phone=972541234567", WhatsAppLinks.apiSendUrl("972541234567", null))
        assertEquals("https://api.whatsapp.com/send?phone=972541234567", WhatsAppLinks.apiSendUrl("972541234567", ""))
        assertEquals("whatsapp://send?phone=15551234567&text=%D7%94%D7%99%D7%99", WhatsAppLinks.schemeSendUrl("15551234567", "היי"))
    }

    @Test
    fun `recipient digits validation`() {
        assertTrue(WhatsAppLinks.isValidDigits("972541234567"))
        assertTrue(WhatsAppLinks.isValidDigits("6834001"))
        assertFalse(WhatsAppLinks.isValidDigits("0541234567"))
        assertFalse(WhatsAppLinks.isValidDigits("12345"))
        assertFalse(WhatsAppLinks.isValidDigits("1234567890123456"))
        assertFalse(WhatsAppLinks.isValidDigits("97254a234567"))
        assertEquals("972541234567", WhatsAppLinks.requireValidDigits("972541234567", "+972 54-123-4567"))
        val e = assertThrows(ToolException::class.java) { WhatsAppLinks.requireValidDigits(null, "054") }
        assertTrue(e.message!!.contains("international format"))
    }

    @Test
    fun `confirmation summary shows resolved name, number and text`() {
        assertEquals(
            "Send WhatsApp to Tania Cohen (+972541234567): \"מה נשמע?\"",
            WhatsAppLinks.confirmationSummary("Tania Cohen", "972541234567", "מה נשמע?", business = false),
        )
        assertEquals(
            "Send WhatsApp Business to +15551234567 (+15551234567): \"hi\"",
            WhatsAppLinks.confirmationSummary("+15551234567", "15551234567", "hi", business = true),
        )
    }

    @Test
    fun `package choice`() {
        val all = setOf(WhatsAppApps.CONSUMER, WhatsAppApps.BUSINESS)
        assertEquals(WhatsAppApps.CONSUMER, WhatsAppApps.choose(false) { it in all })
        assertEquals(WhatsAppApps.BUSINESS, WhatsAppApps.choose(true) { it in all })
        assertEquals(WhatsAppApps.BUSINESS, WhatsAppApps.choose(false) { it == WhatsAppApps.BUSINESS })
        assertEquals(WhatsAppApps.NOT_INSTALLED, assertThrows(ToolException::class.java) { WhatsAppApps.choose(false) { false } }.message)
        val noBusiness = assertThrows(ToolException::class.java) { WhatsAppApps.choose(true) { it == WhatsAppApps.CONSUMER } }
        assertTrue(noBusiness.message!!.contains("Retry without business=true"))
        assertEquals("WhatsApp Business", WhatsAppApps.label(WhatsAppApps.BUSINESS))
        assertEquals("WhatsApp", WhatsAppApps.label(WhatsAppApps.CONSUMER))
    }
}
