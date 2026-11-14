package dev.phonebridge.android.confirm

import dev.phonebridge.android.tools.support.ApprovalGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test

class ForegroundScreensTest {
    @Test
    fun activeWhileAnyScreenIsResumed() {
        val reported = mutableListOf<Boolean>()
        val screens = ForegroundScreens { reported += it }
        val confirmation = Any()
        val pairing = Any()
        screens.resumed(confirmation)
        assertEquals(listOf(true), reported)
        // Multi-window: both resumed, one pauses.
        screens.resumed(pairing)
        screens.paused(confirmation)
        assertEquals(true, reported.last())
        screens.paused(pairing)
        assertEquals(false, reported.last())
    }

    @Test
    fun pausingAScreenThatWasNeverResumedChangesNothing() {
        val reported = mutableListOf<Boolean>()
        val screens = ForegroundScreens { reported += it }
        screens.resumed("confirmation")
        screens.paused(Any())
        assertEquals(true, reported.last())
    }

    @Test
    fun screensAreTrackedByIdentity() {
        val reported = mutableListOf<Boolean>()
        val screens = ForegroundScreens { reported += it }
        val first = Equal()
        screens.resumed(first)
        screens.paused(Equal())
        assertEquals("an equal but different screen does not clear it", true, reported.last())
        screens.paused(first)
        assertEquals(false, reported.last())
    }

    @Test
    fun approvalScreensFeedTheGuardUnderTheirOwnSource() {
        // ApprovalGuard is process-wide: only meaningful when nothing else holds it.
        assumeFalse(ApprovalGuard.isActive)
        val screen = Any()
        ApprovalScreens.resumed(screen)
        assertTrue(ApprovalGuard.isActive)
        // Another approval UI clearing its own source must not clear ours.
        ApprovalGuard.set("approval_screen", false)
        assertTrue(ApprovalGuard.isActive)
        ApprovalScreens.paused(screen)
        assertFalse(ApprovalGuard.isActive)
    }

    private class Equal {
        override fun equals(other: Any?) = other is Equal

        override fun hashCode() = 1
    }
}
