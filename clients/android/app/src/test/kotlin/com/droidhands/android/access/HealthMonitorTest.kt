package com.droidhands.android.access

import androidx.core.content.ContextCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class HealthMonitorTest {
    @Test
    fun `the unlock receiver is exported so System UI's USER_PRESENT reaches it`() {
        // System UI is not the system uid: a RECEIVER_NOT_EXPORTED receiver never gets USER_PRESENT (PLAT-4).
        assertEquals(ContextCompat.RECEIVER_EXPORTED, HealthMonitor.UNLOCK_RECEIVER_FLAGS)
        assertNotEquals(ContextCompat.RECEIVER_NOT_EXPORTED, HealthMonitor.UNLOCK_RECEIVER_FLAGS)
    }
}
