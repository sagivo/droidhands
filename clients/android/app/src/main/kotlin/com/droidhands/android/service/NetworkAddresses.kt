package com.droidhands.android.service

import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketException

/** Enumerates this device's addresses for showing local-mode URLs. */
object NetworkAddresses {
    /**
     * Non-loopback, non-link-local IPv4 addresses of interfaces that are up (Wi-Fi, Ethernet,
     * Tailscale/VPN tunnels…), in interface order, without duplicates. Empty on failure.
     * Performs a few syscalls: call off the main thread.
     */
    fun nonLoopbackIpv4(): List<String> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { iface -> runCatching { iface.isUp && !iface.isLoopback }.getOrDefault(false) }
            .flatMap { iface -> iface.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filterNot { it.isLoopbackAddress || it.isLinkLocalAddress || it.isAnyLocalAddress }
            .mapNotNull { it.hostAddress }
            .distinct()
    } catch (e: SocketException) {
        emptyList()
    }
}
