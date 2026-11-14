package dev.phonebridge.android.tools.notifications

import dev.phonebridge.android.notify.NotificationActionInfo
import dev.phonebridge.android.notify.NotificationInfo
import dev.phonebridge.android.notify.NotificationMessage
import dev.phonebridge.android.tools.system.Redactor
import dev.phonebridge.core.util.OneTimeCodes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class NotificationRedactionTest {
    /** Stand-in for OneTimeCodes.redact (tested in core). */
    private val masker: Redactor = { it.replace(Regex("\\b\\d{6}\\b"), "[code hidden]") }

    private val otp = NotificationInfo(
        key = "k",
        packageName = "com.google.android.apps.messaging",
        appLabel = "Messages",
        title = "Code 482913",
        text = "Your verification code is 482913",
        messages = listOf(NotificationMessage("123456", "G-482913 is your code", 1L)),
        postTime = 0,
        actions = listOf(NotificationActionInfo(0, "Copy 482913", canReply = false), NotificationActionInfo(1, "Reply", canReply = true)),
    )

    @Test
    fun `masks title text messages and action titles and sets the flag`() {
        val out = NotificationRedaction.redact(otp, masker)
        assertEquals("Code [code hidden]", out.title)
        assertEquals("Your verification code is [code hidden]", out.text)
        assertEquals("G-[code hidden] is your code", out.messages.single().text)
        assertEquals(listOf("Copy [code hidden]", "Reply"), out.actions.map { it.title })
        assertTrue(out.codesHidden)
    }

    @Test
    fun `sender names and indices are kept`() {
        val out = NotificationRedaction.redact(otp, masker)
        assertEquals("123456", out.messages.single().sender)
        assertEquals(listOf(0, 1), out.actions.map { it.index })
        assertEquals(otp.key, out.key)
    }

    @Test
    fun `nothing to mask returns the same object without the flag`() {
        val plain = otp.copy(title = "Tania", text = "See you at 8", messages = emptyList(), actions = emptyList())
        assertSame(plain, NotificationRedaction.redact(plain, masker))
        assertSame(otp, NotificationRedaction.redact(otp, null))
        assertFalse(NotificationRedaction.redact(plain, masker).codesHidden)
    }

    private val real: Redactor = OneTimeCodes::redact

    private fun push(title: String?, text: String?, vararg actions: String) = NotificationInfo(
        key = "k",
        packageName = "com.example.bank",
        appLabel = "Bank",
        title = title,
        text = text,
        postTime = 0,
        actions = actions.mapIndexed { i, a -> NotificationActionInfo(i, a, canReply = false) },
    )

    @Test
    fun `a keyword in the title hides a bare code in the body`() {
        listOf(
            "One-time password" to "482913 (valid 5 min)",
            "Verification code" to "482913",
            "OTP" to "482913 – valid for 5 minutes",
            "Your verification code" to "Someone is signing in to your Example account: 482913",
        ).forEach { (title, text) ->
            // Neither text alone mentions a code next to the number.
            assertEquals(text, OneTimeCodes.redact(text))
            val out = NotificationRedaction.redact(push(title, text), real)
            assertFalse("$title / ${out.text}", out.text!!.contains("482913"))
            assertEquals(title, out.title)
            assertTrue(out.codesHidden)
        }
    }

    @Test
    fun `a code found in one text is hidden in every other text`() {
        val out = NotificationRedaction.redact(push("Bank", "Your code is 482913. Do not share it.", "Copy 482913", "Dismiss"), real)
        assertEquals("Your code is ${OneTimeCodes.MASK}. Do not share it.", out.text)
        assertEquals(listOf("Copy ${OneTimeCodes.MASK}", "Dismiss"), out.actions.map { it.title })
        val chat = otp.copy(title = "Google", text = null, messages = listOf(NotificationMessage("Google", "What's the code?", 1L), NotificationMessage("Google", "482913", 2L)), actions = emptyList())
        assertEquals("482913", NotificationRedaction.redact(chat.copy(messages = chat.messages.drop(1)), real).messages.single().text)
        assertEquals(OneTimeCodes.MASK, NotificationRedaction.redact(chat, real).messages.last().text)
    }

    @Test
    fun `ordinary notifications are left alone`() {
        val plain = push("Order shipped", "Your order 48291 arrives on 12.05.2025 at 14:30. Total $59.90", "Track")
        assertSame(plain, NotificationRedaction.redact(plain, real))
        val noTitle = push(null, "Meeting moved to 15:00")
        assertSame(noTitle, NotificationRedaction.redact(noTitle, real))
        // The title stays absent when only the body changes.
        assertEquals(null, NotificationRedaction.redact(push(null, "Your code is 482913"), real).title)
    }

    @Test
    fun `a form feed in a text falls back to masking each text alone`() {
        val perText = push("Code 482913\u000C", "hi")
        assertEquals("Code ${OneTimeCodes.MASK}\u000C", NotificationRedaction.redact(perText, real).title)
    }

    private fun encode(e: JsonElement) = Json.encodeToString(JsonElement.serializer(), e)

    @Test
    fun `json carries the flags only when set`() {
        val json = encode(NotificationJson.notification(NotificationRedaction.redact(otp, masker), ZoneOffset.UTC))
        assertTrue(json.contains("\"codes_hidden\":true"))
        assertFalse(json.contains("482913"))
        assertFalse(json.contains("content_hidden_by_android"))
        val hidden = encode(NotificationJson.notification(otp.copy(contentHiddenByAndroid = true), ZoneOffset.UTC))
        assertTrue(hidden.contains("\"content_hidden_by_android\":true"))
        assertFalse(hidden.contains("codes_hidden"))
    }

    @Test
    fun `codes are masked on the whole text before it is truncated`() {
        // Truncating first (at 1000 chars) would leave the first digits "4829" of the code visible.
        val long = otp.copy(text = "x".repeat(990) + " code 482913 more", title = "t".repeat(400), messages = listOf(NotificationMessage("a", "m".repeat(600), 0)))
        val json = encode(NotificationJson.notification(NotificationRedaction.redact(long, masker), ZoneOffset.UTC))
        assertFalse(json.contains("4829"))
        assertTrue(json.contains("[truncated, 1014 chars total]"))
        assertTrue(json.contains("[truncated, 400 chars total]"))
        assertTrue(json.contains("[truncated, 600 chars total]"))
    }
}
