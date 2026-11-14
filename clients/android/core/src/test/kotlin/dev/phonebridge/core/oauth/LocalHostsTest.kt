package dev.phonebridge.core.oauth

import org.junit.Test
import java.net.Inet6Address
import java.net.InetAddress
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LocalHostsTest {
    private fun ip(text: String): InetAddress = InetAddress.getByName(text)

    @Test
    fun routes() {
        assertEquals(LocalHosts.Route.LOOPBACK, LocalHosts.route(ip("127.0.0.1")))
        assertEquals(LocalHosts.Route.LOOPBACK, LocalHosts.route(ip("127.8.9.10")))
        assertEquals(LocalHosts.Route.LOOPBACK, LocalHosts.route(ip("::1")))
        assertEquals(LocalHosts.Route.TAILNET, LocalHosts.route(ip("100.64.0.1")))
        assertEquals(LocalHosts.Route.TAILNET, LocalHosts.route(ip("100.127.255.254")))
        assertEquals(LocalHosts.Route.TAILNET, LocalHosts.route(ip("fd7a:115c:a1e0::1234")))
        assertEquals(LocalHosts.Route.LAN, LocalHosts.route(ip("10.1.2.3")))
        assertEquals(LocalHosts.Route.LAN, LocalHosts.route(ip("172.16.0.1")))
        assertEquals(LocalHosts.Route.LAN, LocalHosts.route(ip("172.31.255.1")))
        assertEquals(LocalHosts.Route.LAN, LocalHosts.route(ip("192.168.1.20")))
        assertEquals(LocalHosts.Route.LAN, LocalHosts.route(ip("169.254.3.4")))
        assertEquals(LocalHosts.Route.LAN, LocalHosts.route(ip("fd12:3456::1")))
        for (other in listOf("100.63.255.255", "100.128.0.1", "172.32.0.1", "8.8.8.8", "203.0.113.7", "2001:db8::1", "fe80::1", "0.0.0.0")) {
            assertNull(LocalHosts.route(ip(other)), other)
        }
    }

    @Test
    fun loopbackAcceptsLoopbackNamesOnAnyPort() {
        val local = ip("127.0.0.1")
        assertEquals("http://127.0.0.1:8765", LocalHosts.issuer("127.0.0.1:8765", local))
        assertEquals("http://localhost:9000", LocalHosts.issuer("LocalHost:9000", local))
        assertEquals("http://[::1]:9000", LocalHosts.issuer("[::1]:9000", local))
        assertEquals("http://127.0.0.1", LocalHosts.issuer("127.0.0.1:80", local))
        assertEquals("http://127.0.0.1", LocalHosts.issuer("127.0.0.1", local))
        // DNS rebinding: an attacker's name pointing at 127.0.0.1.
        assertNull(LocalHosts.issuer("evil.example:8765", local))
        assertNull(LocalHosts.issuer("localhost.evil.example:8765", local))
        assertNull(LocalHosts.issuer("pixel.tail1234.ts.net:8765", local))
        assertNull(LocalHosts.issuer("192.168.1.20:8765", local))
    }

    @Test
    fun tailnetAcceptsItsOwnAddressAndMagicDns() {
        val local = ip("100.101.102.103")
        assertEquals("http://100.101.102.103:8765", LocalHosts.issuer("100.101.102.103:8765", local))
        assertEquals("http://pixel.tail1234.ts.net:8765", LocalHosts.issuer("Pixel.Tail1234.TS.NET:8765", local))
        assertNull(LocalHosts.issuer("100.101.102.104:8765", local), "another tailnet machine's address")
        assertNull(LocalHosts.issuer("localhost:8765", local))
        assertNull(LocalHosts.issuer("127.0.0.1:8765", local))
        assertNull(LocalHosts.issuer("pixel:8765", local), "short names could come from any search domain")
        assertNull(LocalHosts.issuer("ts.net.evil.example:8765", local))
        assertNull(LocalHosts.issuer("evilts.net:8765", local))

        val v6 = ip("fd7a:115c:a1e0::42")
        assertEquals("http://[fd7a:115c:a1e0::42]:8765", LocalHosts.issuer("[fd7a:115c:a1e0::42]:8765", v6))
        assertEquals("http://pixel.tail1234.ts.net:8765", LocalHosts.issuer("pixel.tail1234.ts.net:8765", v6))
    }

    @Test
    fun lanAcceptsOnlyItsOwnAddress() {
        val local = ip("192.168.1.20")
        assertEquals("http://192.168.1.20:8765", LocalHosts.issuer("192.168.1.20:8765", local))
        assertNull(LocalHosts.issuer("192.168.1.21:8765", local))
        assertNull(LocalHosts.issuer("pixel.local:8765", local))
        assertNull(LocalHosts.issuer("pixel.tail1234.ts.net:8765", local))
        assertNull(LocalHosts.issuer("localhost:8765", local))
    }

    @Test
    fun ipv4MappedSocketAddressesCountAsIpv4() {
        val mapped = Inet6Address.getByAddress(null, ByteArray(16).also { it[10] = -1; it[11] = -1; it[12] = 192.toByte(); it[13] = 168.toByte(); it[14] = 1; it[15] = 20 }, -1)
        assertEquals(LocalHosts.Route.LAN, LocalHosts.route(mapped))
        assertEquals("http://192.168.1.20:8765", LocalHosts.issuer("192.168.1.20:8765", mapped))
    }

    @Test
    fun otherRoutesAreRefused() {
        assertNull(LocalHosts.issuer("203.0.113.7:8765", ip("203.0.113.7")))
        assertNull(LocalHosts.issuer("[2001:db8::1]:8765", ip("2001:db8::1")))
        assertNull(LocalHosts.issuer("127.0.0.1:8765", null))
        assertNull(LocalHosts.issuer(null, ip("127.0.0.1")))
    }

    @Test
    fun malformedHostHeaders() {
        val local = ip("127.0.0.1")
        for (bad in listOf(
            "", " ", "user@127.0.0.1", "127.0.0.1/mcp", "127.0.0.1:0", "127.0.0.1:65536", "127.0.0.1:", "127.0.0.1:80a",
            "127.1", "0x7f.0.0.1", "0127.0.0.1", "2130706433", "[::1", "[::1]x", "[fe80::1%25eth0]", "::1", "[127.0.0.1]",
            "[::ffff:127.0.0.1]", "local_host", "-localhost", "127.0.0.1:8765, evil.example",
        )) {
            assertNull(LocalHosts.issuer(bad, local), "'$bad'")
        }
    }
}
