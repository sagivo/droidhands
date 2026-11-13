package dev.phonebridge.android.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset

class NotificationTextTest {
    @Test
    fun `clean trims and nulls blanks`() {
        assertEquals("Hi", NotificationText.clean("  Hi \n"))
        assertNull(NotificationText.clean("   "))
        assertNull(NotificationText.clean(null))
    }

    @Test
    fun `title prefers conversation title then title then big title`() {
        assertEquals("Family", NotificationText.title("Family", "Family: Bob", "Big"))
        assertEquals("Tania", NotificationText.title(null, "Tania", "Big"))
        assertEquals("Big", NotificationText.title(" ", null, "Big"))
        assertNull(NotificationText.title(null, null, null))
    }

    @Test
    fun `body prefers big text over text`() {
        assertEquals("full message", NotificationText.body("full message", "full mes…", emptyList()))
        assertEquals("short", NotificationText.body(null, "short", emptyList()))
        assertEquals("short", NotificationText.body(" ", "short", emptyList()))
        assertNull(NotificationText.body(null, null, emptyList()))
    }

    @Test
    fun `body appends inbox lines`() {
        assertEquals("2 new messages\nBob: hi\nAnn: yo", NotificationText.body(null, "2 new messages", listOf("Bob: hi", " ", "Ann: yo")))
        assertEquals("Bob: hi\nAnn: yo", NotificationText.body(null, null, listOf("Bob: hi", "Ann: yo")))
    }

    @Test
    fun `body drops primary text already contained in lines`() {
        assertEquals("Bob: hi\nAnn: yo", NotificationText.body(null, "Ann: yo", listOf("Bob: hi", "Ann: yo")))
    }

    @Test
    fun `truncate leaves short text alone`() {
        assertEquals("abc", NotificationText.truncate("abc", 3))
    }

    @Test
    fun `truncate cuts and says so`() {
        val text = "a".repeat(1500)
        val out = NotificationText.truncate(text, 1000)
        assertTrue(out.startsWith("a".repeat(1000) + "…"))
        assertTrue(out.endsWith("[truncated, 1500 chars total]"))
    }

    @Test
    fun `truncate never splits a surrogate pair`() {
        val text = "ab😀cd" // a b 😀 c d
        val out = NotificationText.truncate(text, 3)
        assertTrue(out.startsWith("ab…"))
        assertFalse(out.contains('\uD83D'))
    }

    @Test
    fun `ellipsize collapses whitespace and shortens`() {
        assertEquals("one two", NotificationText.ellipsize(" one \n two ", 20))
        assertEquals("abcd…", NotificationText.ellipsize("abcdefghij", 5))
    }

    @Test
    fun `message without sender person is from the user`() {
        assertTrue(NotificationText.isFromUser(null, PersonRef("me", "You")))
        assertTrue(NotificationText.isFromUser(null, null))
    }

    @Test
    fun `sender compared by key when both have keys`() {
        assertTrue(NotificationText.isFromUser(PersonRef("u1", "Tania"), PersonRef("u1", "You")))
        assertFalse(NotificationText.isFromUser(PersonRef("u2", "You"), PersonRef("u1", "You")))
    }

    @Test
    fun `sender compared by name when keys are missing`() {
        assertTrue(NotificationText.isFromUser(PersonRef(null, "You"), PersonRef("u1", "You")))
        assertFalse(NotificationText.isFromUser(PersonRef(null, "Bob"), PersonRef(null, "You")))
        assertFalse(NotificationText.isFromUser(PersonRef(null, null), PersonRef(null, null)))
        assertFalse(NotificationText.isFromUser(PersonRef("k", "Bob"), null))
    }

    @Test
    fun `attachment placeholder uses mime major type`() {
        assertEquals("[image]", NotificationText.attachmentPlaceholder("image/jpeg"))
        assertEquals("[audio]", NotificationText.attachmentPlaceholder("Audio/ogg"))
        assertEquals("[file]", NotificationText.attachmentPlaceholder(""))
    }

    @Test
    fun `iso time has offset and second precision`() {
        val millis = 1_731_500_000_123L // 2024-11-13T12:13:20.123Z
        assertEquals("2024-11-13T12:13:20Z", NotificationText.isoTime(millis, ZoneOffset.UTC))
        assertEquals("2024-11-13T14:13:20+02:00", NotificationText.isoTime(millis, ZoneId.of("Asia/Jerusalem")))
    }

    @Test
    fun `iso time keeps zero seconds`() {
        assertEquals("2024-11-13T12:13:00Z", NotificationText.isoTime(1_731_499_980_000L, ZoneOffset.UTC))
    }
}
