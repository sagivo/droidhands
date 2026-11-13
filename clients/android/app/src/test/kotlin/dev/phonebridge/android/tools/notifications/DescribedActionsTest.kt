package dev.phonebridge.android.tools.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DescribedActionsTest {
    private var now = 1_000L
    private val described = DescribedActions(ttlMs = 60_000, maxEntries = 3, clock = { now })

    @Test
    fun `remembered title is returned once`() {
        described.remember("k", "Mark as read", null, "Mark as read")
        assertEquals("Mark as read", described.take("k", "Mark as read", null))
        assertNull(described.take("k", "Mark as read", null))
    }

    @Test
    fun `lookup matches the same arguments only`() {
        described.remember("k", null, 1, "Archive")
        assertNull(described.take("k", null, 2))
        assertNull(described.take("other", null, 1))
        assertEquals("Archive", described.take("k", null, 1))
    }

    @Test
    fun `action argument is matched case-insensitively`() {
        described.remember("k", "Mark As Read ", null, "Mark as read")
        assertEquals("Mark as read", described.take("k", "mark as read", null))
    }

    @Test
    fun `entries expire`() {
        described.remember("k", "a", null, "A")
        now += 60_001
        assertNull(described.take("k", "a", null))
    }

    @Test
    fun `size stays bounded`() {
        (1..10).forEach { described.remember("k$it", null, 0, "T$it") }
        val remaining = (1..10).count { described.take("k$it", null, 0) != null }
        assertEquals(3, remaining)
    }
}
