package dev.phonebridge.android.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkChangeTrackerTest {
    @Test
    fun initialNetworkDoesNotReconnect() {
        val tracker = NetworkChangeTracker<String>()
        assertFalse(tracker.onAvailable("wifi"))
        assertFalse(tracker.onAvailable("wifi"))
    }

    @Test
    fun switchingDefaultNetworkReconnects() {
        val tracker = NetworkChangeTracker<String>()
        tracker.onAvailable("wifi")
        assertTrue(tracker.onAvailable("mobile"))
        assertFalse(tracker.onAvailable("mobile"))
    }

    @Test
    fun regainingAfterLossReconnects() {
        val tracker = NetworkChangeTracker<String>()
        tracker.onAvailable("wifi")
        tracker.onLost("wifi")
        assertTrue(tracker.onAvailable("wifi"))
        assertFalse(tracker.onAvailable("wifi"))
    }

    @Test
    fun lossOfANonDefaultNetworkIsIgnored() {
        val tracker = NetworkChangeTracker<String>()
        tracker.onAvailable("wifi")
        tracker.onLost("mobile")
        assertFalse(tracker.onAvailable("wifi"))
    }
}
