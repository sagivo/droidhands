package dev.phonebridge.android.tools.notifications

import dev.phonebridge.android.notify.FilteredNotifications
import dev.phonebridge.android.notify.NotificationInfo
import dev.phonebridge.android.notify.NotificationQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationSummariesTest {
    @Test
    fun `reply summary names app conversation and full message`() {
        assertEquals(
            "Reply in WhatsApp to Tania Cohen: \"See you at 8\"",
            NotificationSummaries.reply("WhatsApp", "Tania Cohen", "See you at 8"),
        )
        assertEquals("Reply in Signal: \"ok\"", NotificationSummaries.reply("Signal", null, "ok"))
    }

    @Test
    fun `reply summary never shortens the message`() {
        val message = "x".repeat(500)
        assertTrue(NotificationSummaries.reply("WhatsApp", "Tania", message).contains(message))
    }

    @Test
    fun `reply summary shortens long titles`() {
        val summary = NotificationSummaries.reply("WhatsApp", "T".repeat(200), "hi")
        assertTrue(summary.contains("…"))
        assertTrue(summary.length < 120)
    }

    @Test
    fun `action summary`() {
        assertEquals(
            "Tap \"Mark as read\" on WhatsApp notification: Tania Cohen",
            NotificationSummaries.action("Mark as read", "WhatsApp", "Tania Cohen"),
        )
        assertEquals("Tap \"Archive\" on Gmail notification", NotificationSummaries.action("Archive", "Gmail", null))
    }

    @Test
    fun `action fallback uses raw args`() {
        assertEquals("Tap \"Archive\" on notification 0|p|1|null|1", NotificationSummaries.actionFallback("0|p|1|null|1", "Archive", null))
        assertEquals("Tap action #2 on notification k", NotificationSummaries.actionFallback("k", null, 2))
        assertEquals("Tap an action on notification (no key)", NotificationSummaries.actionFallback(null, null, null))
    }

    @Test
    fun `dismiss summaries`() {
        assertEquals("Dismiss Gmail notification: Invoice", NotificationSummaries.dismiss("Gmail", "Invoice"))
        assertEquals("Dismiss Gmail notification", NotificationSummaries.dismiss("Gmail", " "))
        assertEquals("Dismiss notification k", NotificationSummaries.dismissFallback("k"))
    }

    private fun page(items: Int, total: Int) = FilteredNotifications(
        List(items) { NotificationInfo(key = "k$it", packageName = "p", appLabel = "P", postTime = 0) },
        total,
    )

    @Test
    fun `footer reports omitted matches`() {
        val footer = NotificationSummaries.listFooter(NotificationQuery(limit = 30), page(30, 45))
        assertEquals("15 more matching notification(s) not shown. Raise limit (max 100) or narrow with package/query.", footer)
        val atMax = NotificationSummaries.listFooter(NotificationQuery(limit = 100), page(100, 120))
        assertEquals("20 more matching notification(s) not shown. Narrow with package/query.", atMax)
    }

    @Test
    fun `footer hints when package looks like an app name`() {
        val footer = NotificationSummaries.listFooter(NotificationQuery(packageName = "WhatsApp"), page(0, 0))
        assertTrue(footer!!.contains("exact package name"))
        assertNull(NotificationSummaries.listFooter(NotificationQuery(packageName = "com.whatsapp"), page(0, 0)))
    }

    @Test
    fun `no footer for a complete page`() {
        assertNull(NotificationSummaries.listFooter(NotificationQuery(), page(3, 3)))
    }
}
