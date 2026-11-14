package dev.phonebridge.android.tools.notifications

import dev.phonebridge.android.notify.NotificationActionInfo
import dev.phonebridge.android.notify.NotificationInfo
import dev.phonebridge.android.notify.NotificationMessage
import dev.phonebridge.android.tools.system.Redactor
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
