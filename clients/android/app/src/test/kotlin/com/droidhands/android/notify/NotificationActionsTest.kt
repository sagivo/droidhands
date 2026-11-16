package com.droidhands.android.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationActionsTest {
    private fun c(
        title: String,
        canReply: Boolean = false,
        identity: Any? = title,
        semantic: Boolean = false,
        requiresInput: Boolean = canReply,
        payload: String = title,
    ) = ActionCandidate(title, canReply, requiresInput, semantic, identity, payload)

    @Test
    fun `standard actions keep their order and get indices from zero`() {
        val merged = NotificationActions.merge(listOf(c("Reply", canReply = true), c("Mark as read")), emptyList())
        assertEquals(listOf(0, 1), merged.map { it.info.index })
        assertEquals(listOf("Reply", "Mark as read"), merged.map { it.info.title })
        assertTrue(merged[0].info.canReply)
        assertFalse(merged[1].info.canReply)
        assertFalse(merged.any { it.info.fromWearable })
    }

    @Test
    fun `wearable duplicates by title are dropped case-insensitively`() {
        val merged = NotificationActions.merge(
            listOf(c("Reply", canReply = true, identity = "pi1"), c("Mark as read", identity = "pi2")),
            listOf(c("reply ", canReply = true, identity = "w1"), c("MARK AS READ", identity = "w2")),
        )
        assertEquals(2, merged.size)
    }

    @Test
    fun `wearable duplicates by same pending intent are dropped even with another title`() {
        val merged = NotificationActions.merge(listOf(c("Reply", canReply = true, identity = "pi")), listOf(c("Answer", canReply = true, identity = "pi")))
        assertEquals(listOf("Reply"), merged.map { it.info.title })
    }

    @Test
    fun `wearable reply is kept when the standard action with that title cannot reply`() {
        // WhatsApp-style: phone "Reply" opens the app; the wearable "Reply" carries the RemoteInput.
        val merged = NotificationActions.merge(
            listOf(c("Reply", identity = "open"), c("Mark as read", identity = "read")),
            listOf(c("Reply", canReply = true, identity = "remote", payload = "wearReply")),
        )
        assertEquals(3, merged.size)
        val wear = merged[2]
        assertEquals(2, wear.info.index)
        assertTrue(wear.info.canReply)
        assertTrue(wear.info.fromWearable)
        assertEquals("wearReply", wear.payload)
    }

    @Test
    fun `new wearable actions are appended after standard ones`() {
        val merged = NotificationActions.merge(listOf(c("Archive")), listOf(c("Reply", canReply = true), c("Archive", identity = "x")))
        assertEquals(listOf("Archive", "Reply"), merged.map { it.info.title })
        assertEquals(listOf(0, 1), merged.map { it.info.index })
    }

    @Test
    fun `wearable list is de-duplicated against itself too`() {
        val merged = NotificationActions.merge(emptyList(), listOf(c("Reply", canReply = true, identity = "a"), c("Reply", canReply = true, identity = "b")))
        assertEquals(1, merged.size)
    }

    @Test
    fun `blank titles only de-duplicate by identity`() {
        val merged = NotificationActions.merge(listOf(c("", identity = "a")), listOf(c("", identity = "b"), c("", identity = "a")))
        assertEquals(2, merged.size)
    }

    @Test
    fun `standard duplicates are kept as the user sees them`() {
        val merged = NotificationActions.merge(listOf(c("Open", identity = "a"), c("Open", identity = "a")), emptyList())
        assertEquals(2, merged.size)
    }

    @Test
    fun `requiresInput is implied by canReply and kept for choice-only inputs`() {
        val merged = NotificationActions.merge(
            listOf(c("Quick reply", canReply = false, requiresInput = true), c("Reply", canReply = true, requiresInput = false)),
            emptyList(),
        )
        assertTrue(merged[0].info.requiresInput)
        assertTrue(merged[1].info.requiresInput)
    }

    @Test
    fun `merge is deterministic for an unchanged notification`() {
        val standard = listOf(c("Reply", canReply = true), c("Mute"))
        val wearable = listOf(c("Like", identity = "like"))
        val first = NotificationActions.merge(standard, wearable).map { it.info }
        val second = NotificationActions.merge(standard, wearable).map { it.info }
        assertEquals(first, second)
    }
}
