package com.droidhands.android.service

/**
 * Decides when a default-network callback means "the relay socket is probably dead, reconnect now"
 * instead of waiting for the client's backoff. Generic over the network handle so it is testable
 * without Android. Thread-safe (callbacks arrive on a ConnectivityManager thread).
 *
 * Call [start] with the default network right before registering the callback. The first
 * `onAvailable` for that network does not trigger a reconnect; a later one does if the default
 * network was lost or switched (e.g. Wi-Fi → mobile). When there was no default network at start
 * (airplane mode, no signal, boot before Wi-Fi associates), the first `onAvailable` is connectivity
 * coming back, which triggers a reconnect too.
 */
class NetworkChangeTracker<N : Any> {
    private var current: N? = null
    private var lost = false

    /** Before registering: [network] is the current default network, or null if there is none. */
    @Synchronized
    fun start(network: N?) {
        current = network
        lost = network == null
    }

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
