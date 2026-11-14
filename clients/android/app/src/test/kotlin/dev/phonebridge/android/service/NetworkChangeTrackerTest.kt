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
    fun firstNetworkAfterStartingOfflineReconnects() {
        val tracker = NetworkChangeTracker<String>()
        tracker.start(null)
        // Airplane mode off / boot before Wi-Fi associated: connectivity is coming back, don't wait out the backoff.
        assertTrue(tracker.onAvailable("wifi"))
        assertFalse(tracker.onAvailable("wifi"))
    }

    @Test
    fun startingOnlineKeepsTheInitialCallbackQuiet() {
        val tracker = NetworkChangeTracker<String>()
        tracker.start("wifi")
        assertFalse(tracker.onAvailable("wifi"))
        assertTrue(tracker.onAvailable("mobile"))
    }

    @Test
    fun defaultNetworkSwitchedDuringRegistrationReconnects() {
        val tracker = NetworkChangeTracker<String>()
        tracker.start("wifi")
        assertTrue(tracker.onAvailable("mobile"))
    }

    @Test
    fun lossOfANonDefaultNetworkIsIgnored() {
        val tracker = NetworkChangeTracker<String>()
        tracker.onAvailable("wifi")
        tracker.onLost("mobile")
        assertFalse(tracker.onAvailable("wifi"))
    }
}
