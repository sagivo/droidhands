package dev.phonebridge.android.ui.format

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostsTest {
    @Test
    fun loopback() {
        listOf("localhost", "LOCALHOST", "app.localhost", "127.0.0.1", "127.8.9.10", "[::1]", "::1", "0:0:0:0:0:0:0:1")
            .forEach { assertTrue(it, Hosts.isLoopback(it)) }
        listOf("10.0.2.2", "example.com", "128.0.0.1", "::2", "localhost.example.com")
            .forEach { assertFalse(it, Hosts.isLoopback(it)) }
    }

    @Test
    fun tailscale() {
        listOf("100.64.0.1", "100.100.100.100", "100.127.255.255", "my-pc.tail1234.ts.net", "[fd7a:115c:a1e0::1]")
            .forEach { assertTrue(it, Hosts.isTailscale(it)) }
        listOf("100.63.255.255", "100.128.0.1", "ts.net.example.com", "fd00::1")
            .forEach { assertFalse(it, Hosts.isTailscale(it)) }
    }

    @Test
    fun privateNetworks() {
        listOf(
            "10.0.2.2", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.1.20", "169.254.3.4",
            "[fd12:3456::1]", "fc00::1", "fe80::1", "printer.local", "nas.lan", "box.home.arpa", "raspberrypi",
        ).forEach { assertTrue(it, Hosts.isPrivateNetwork(it)) }
        listOf("172.15.0.1", "172.32.0.1", "192.169.0.1", "8.8.8.8", "example.com", "2001:db8::1", "fd::1")
            .forEach { assertFalse(it, Hosts.isPrivateNetwork(it)) }
    }

    @Test
    fun trustedForPlainHttp() {
        assertTrue(Hosts.isTrustedForPlainHttp("localhost"))
        assertTrue(Hosts.isTrustedForPlainHttp("10.0.2.2"))
        assertTrue(Hosts.isTrustedForPlainHttp("100.101.102.103"))
        assertFalse(Hosts.isTrustedForPlainHttp("relay.example.com"))
        assertFalse(Hosts.isTrustedForPlainHttp("203.0.113.7"))
    }

    @Test
    fun parseIpv4() {
        assertArrayEquals(intArrayOf(192, 168, 0, 1), Hosts.parseIpv4("192.168.0.1"))
        assertNull(Hosts.parseIpv4("256.1.1.1"))
        assertNull(Hosts.parseIpv4("1.2.3"))
        assertNull(Hosts.parseIpv4("1.2.3.x"))
        assertNull(Hosts.parseIpv4("1..2.3"))
    }
}
