package com.droidhands.android.tools.system

import com.droidhands.core.tools.ToolException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlPolicyTest {
    private fun ok(raw: String) = UrlPolicy.check(raw)

    private fun rejected(raw: String?): String = assertThrows(ToolException::class.java) { UrlPolicy.check(raw) }.message!!

    @Test
    fun `web links and deep links are allowed`() {
        assertEquals("https", ok("https://example.com/a?b=1").scheme)
        assertEquals("http", ok("http://example.com").scheme)
        assertEquals("geo", ok("geo:37.42,-122.08?q=coffee").scheme)
        assertEquals("mailto", ok("mailto:someone@example.com").scheme)
        assertEquals("market", ok("market://details?id=com.whatsapp").scheme)
        assertEquals("whatsapp", ok("whatsapp://send?phone=972541234567").scheme)
        assertEquals("tg", ok("tg://resolve?domain=telegram").scheme)
        assertEquals("tel", ok("tel:+15551234567").scheme)
        assertEquals("com.example.app", ok("com.example.app:/callback").scheme)
    }

    @Test
    fun `scheme is lowercased and whitespace trimmed`() {
        assertEquals("https://Example.com/Path", ok("  HTTPS://Example.com/Path ").url)
    }

    @Test
    fun `bare host names get https`() {
        assertEquals("https://example.com", ok("example.com").url)
        assertEquals("https://www.example.com/path?q=1", ok("www.example.com/path?q=1").url)
        assertEquals("https://example.com:8080/x", ok("example.com:8080/x").url)
        assertEquals("https://localhost:3000", ok("localhost:3000").url)
    }

    @Test
    fun `dangerous schemes are rejected in any case`() {
        for (url in listOf(
            "intent://scan/#Intent;scheme=zxing;package=com.x;end",
            "INTENT:#Intent;action=android.intent.action.CALL;end",
            "android-app://com.example/https/example.com",
            "file:///sdcard/secret.txt",
            "content://com.android.contacts/contacts",
            "javascript:alert(1)",
            " JavaScript:alert(1)",
            "data:text/html,<script>alert(1)</script>",
        )) {
            assertTrue(url, rejected(url).contains("blocked"))
        }
    }

    @Test
    fun `blank, scheme-less, control chars and hostless web links are rejected`() {
        rejected(null)
        rejected("   ")
        rejected("not a url")
        rejected("java\tscript:alert(1)")
        rejected("https://")
        rejected("https:example.com")
        rejected("http:///path")
    }
}
