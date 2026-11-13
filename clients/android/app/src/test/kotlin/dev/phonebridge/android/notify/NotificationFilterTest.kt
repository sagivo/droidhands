package dev.phonebridge.android.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationFilterTest {
    private val own = "dev.phonebridge.android"

    private fun n(
        key: String,
        pkg: String = "com.whatsapp",
        time: Long = 1_000,
        title: String? = null,
        text: String? = null,
        messages: List<NotificationMessage> = emptyList(),
        ongoing: Boolean = false,
        group: String = key,
        summary: Boolean = false,
    ) = NotificationInfo(
        key = key, packageName = pkg, appLabel = pkg, title = title, text = text, messages = messages,
        postTime = time, ongoing = ongoing, groupKey = group, isGroupSummary = summary,
    )

    private fun keys(all: List<NotificationInfo>, q: NotificationQuery = NotificationQuery()) =
        NotificationFilter.apply(all, q, own).items.map { it.key }

    @Test
    fun `own notifications are always hidden`() {
        val all = listOf(n("bridge", pkg = own, time = 5), n("prompt", pkg = own, time = 6, ongoing = true), n("wa"))
        assertEquals(listOf("wa"), keys(all, NotificationQuery(includeOngoing = true)))
        assertEquals(emptyList<String>(), keys(all, NotificationQuery(packageName = own)))
    }

    @Test
    fun `newest first with stable tie-break`() {
        val all = listOf(n("b", time = 10), n("c", time = 30), n("a", time = 10))
        assertEquals(listOf("c", "a", "b"), keys(all))
    }

    @Test
    fun `group summary hidden when the group has children`() {
        val all = listOf(n("sum", group = "g", summary = true, time = 50), n("c1", group = "g", time = 10), n("c2", group = "g", time = 20))
        assertEquals(listOf("c2", "c1"), keys(all))
    }

    @Test
    fun `lone group summary is shown`() {
        val all = listOf(n("sum", group = "g", summary = true), n("other", group = "h"))
        assertEquals(setOf("sum", "other"), keys(all).toSet())
    }

    @Test
    fun `own notifications do not count as group children`() {
        val all = listOf(n("sum", group = "g", summary = true), n("mine", pkg = own, group = "g"))
        assertEquals(listOf("sum"), keys(all))
    }

    @Test
    fun `ongoing hidden unless requested`() {
        val all = listOf(n("music", ongoing = true, time = 20), n("msg", time = 10))
        assertEquals(listOf("msg"), keys(all))
        assertEquals(listOf("music", "msg"), keys(all, NotificationQuery(includeOngoing = true)))
    }

    @Test
    fun `package filter is exact`() {
        val all = listOf(n("wa"), n("wab", pkg = "com.whatsapp.w4b"), n("tg", pkg = "org.telegram.messenger"))
        assertEquals(listOf("wa"), keys(all, NotificationQuery(packageName = "com.whatsapp")))
    }

    @Test
    fun `query matches title text and messages case-insensitively`() {
        val all = listOf(
            n("t", title = "Tania Cohen", time = 4),
            n("x", text = "Your PARCEL is out for delivery", time = 3),
            n("m", messages = listOf(NotificationMessage("Bob", "where is the parcel?", 0)), time = 2),
            n("s", messages = listOf(NotificationMessage("Tania", "hi", 0)), time = 1),
            n("none", title = "Other", text = "nothing", time = 0),
        )
        assertEquals(listOf("x", "m"), keys(all, NotificationQuery(query = "parcel")))
        assertEquals(listOf("t", "s"), keys(all, NotificationQuery(query = "  TANIA ")))
    }

    @Test
    fun `blank query matches everything`() {
        assertEquals(2, keys(listOf(n("a"), n("b")), NotificationQuery(query = "   ")).size)
    }

    @Test
    fun `query matches non-latin text`() {
        val all = listOf(n("he", title = "טניה כהן"), n("en", title = "Tania"))
        assertEquals(listOf("he"), keys(all, NotificationQuery(query = "טניה")))
    }

    @Test
    fun `limit caps items but reports total matches`() {
        val all = (1..40).map { n("k$it", time = it.toLong()) }
        val page = NotificationFilter.apply(all, NotificationQuery(limit = 5), own)
        assertEquals(listOf("k40", "k39", "k38", "k37", "k36"), page.items.map { it.key })
        assertEquals(40, page.totalMatches)
        assertEquals(35, page.omitted)
    }

    @Test
    fun `limit is clamped to the allowed range`() {
        val all = (1..150).map { n("k$it", time = it.toLong()) }
        assertEquals(NotificationQuery.MAX_LIMIT, NotificationFilter.apply(all, NotificationQuery(limit = 1000), own).items.size)
        assertEquals(1, NotificationFilter.apply(all, NotificationQuery(limit = 0), own).items.size)
        assertEquals(NotificationQuery.DEFAULT_LIMIT, NotificationFilter.apply(all, NotificationQuery(), own).items.size)
    }

    @Test
    fun `empty input gives empty page`() {
        val page = NotificationFilter.apply(emptyList(), NotificationQuery(), own)
        assertTrue(page.items.isEmpty())
        assertEquals(0, page.omitted)
    }
}
