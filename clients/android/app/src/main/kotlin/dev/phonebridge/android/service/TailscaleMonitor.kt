package dev.phonebridge.android.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dev.phonebridge.android.tools.system.packageInfoOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/** What the app knows about Tailscale on this phone. */
data class TailscaleState(
    /** The Tailscale app (com.tailscale.ipn) is installed. */
    val installed: Boolean = false,
    /** The phone's tailnet address and MagicDNS name, or null when Tailscale isn't connected. */
    val tailnet: TailnetAddress? = null,
) {
    val connected: Boolean get() = tailnet != null
}

/**
 * Detects Tailscale on this phone: the Tailscale app, and the phone's tailnet address on an active
 * VPN network ([Tailnet.pickAddress]). The MagicDNS name comes from a reverse lookup of that address
 * in the background (with a short timeout); [onNameFound] runs when one arrives so the local server
 * can publish the nicer URL. Thread-safe.
 */
class TailscaleMonitor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onNameFound: () -> Unit,
) {
    private val _state = MutableStateFlow(TailscaleState())

    /** Updated by every [refresh]. */
    val state: StateFlow<TailscaleState> = _state.asStateFlow()

    /** Address → MagicDNS name, or "" when the lookup found none. */
    private val names = ConcurrentHashMap<String, String>()
    private val lookups = ConcurrentHashMap.newKeySet<String>()

    /** Detects again (binder calls: off the main thread) and returns the tailnet address, if any. */
    fun refresh(): TailnetAddress? {
        val address = Tailnet.pickAddress(candidates())
        val tailnet = address?.let { TailnetAddress(it, names[it]?.ifEmpty { null }) }
        if (address != null && !names.containsKey(address)) lookUpName(address)
        _state.value = TailscaleState(installed = isInstalled(), tailnet = tailnet)
        return tailnet
    }

    private fun isInstalled(): Boolean = try {
        context.packageManager.packageInfoOrNull(Tailnet.TAILSCALE_PACKAGE) != null
    } catch (e: RuntimeException) {
        false
    }

    /** Every address of every network this app can use, flagged by whether that network is a VPN. */
    private fun candidates(): List<Tailnet.Candidate> = try {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION") // No replacement for a one-off listing; callbacks keep it fresh.
        val networks = connectivity?.allNetworks.orEmpty()
        networks.flatMap { network ->
            val vpn = connectivity?.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            connectivity?.getLinkProperties(network)?.linkAddresses.orEmpty()
                .mapNotNull { link -> link.address.hostAddress?.let { Tailnet.Candidate(it, vpn) } }
        }
    } catch (e: RuntimeException) {
        emptyList()
    }

    private fun lookUpName(address: String) {
        if (!lookups.add(address)) return
        scope.launch {
            val lookup = async(Dispatchers.IO) { runCatching { InetAddress.getByName(address).canonicalHostName }.getOrNull() }
            val reverse = withTimeoutOrNull(NAME_LOOKUP_TIMEOUT_MS) { lookup.await() }
            lookups.remove(address)
            // Timed out: try again on the next refresh (show the address meanwhile).
            if (reverse == null && lookup.isActive) return@launch
            val name = Tailnet.acceptDnsName(address, reverse)
            names[address] = name.orEmpty()
            if (name != null) {
                refresh()
                onNameFound()
            }
        }
    }

    private companion object {
        const val NAME_LOOKUP_TIMEOUT_MS = 3_000L
    }
}
