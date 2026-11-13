package dev.phonebridge.android.service

/**
 * Decides when a default-network callback means "the relay socket is probably dead, reconnect now"
 * instead of waiting for the client's backoff. Generic over the network handle so it is testable
 * without Android. Thread-safe (callbacks arrive on a ConnectivityManager thread).
 *
 * The first `onAvailable` after registration is the current network and does not trigger a
 * reconnect; a later one does if the default network was lost or switched (e.g. Wi-Fi → mobile).
 */
class NetworkChangeTracker<N : Any> {
    private var current: N? = null
    private var lost = false

    /** @return true if the relay should reconnect. */
    @Synchronized
    fun onAvailable(network: N): Boolean {
        val reconnect = lost || (current != null && current != network)
        current = network
        lost = false
        return reconnect
    }

    @Synchronized
    fun onLost(network: N) {
        if (network == current) lost = true
    }
}
