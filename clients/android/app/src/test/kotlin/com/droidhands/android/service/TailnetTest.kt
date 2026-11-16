package com.droidhands.android.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TailnetTest {
    private fun vpn(address: String) = Tailnet.Candidate(address, onVpn = true)
    private fun plain(address: String) = Tailnet.Candidate(address, onVpn = false)

    @Test
    fun tailnetRangesAreRecognized() {
        assertTrue(Tailnet.isTailnetIp("100.64.0.1"))
        assertTrue(Tailnet.isTailnetIp("100.127.255.254"))
        assertTrue(Tailnet.isTailnetIp("100.101.102.103"))
        assertFalse(Tailnet.isTailnetIp("100.63.255.255"))
        assertFalse(Tailnet.isTailnetIp("100.128.0.1"))
        assertFalse(Tailnet.isTailnetIp("192.168.1.20"))
        assertTrue(Tailnet.isTailnetIp("fd7a:115c:a1e0:ab12:4843:cd96:6258:b240"))
        assertTrue(Tailnet.isTailnetIp("fd7a:115c:a1e0::1%tun0"))
        assertTrue(Tailnet.isTailnetIp("[FD7A:115C:A1E0::1]"))
        assertFalse(Tailnet.isTailnetIp("fd7a:115c:a1e1::1"))
        assertFalse(Tailnet.isTailnetIp("fe80::1"))
        // A MagicDNS name is a Tailscale host, but not an address the server can bind.
        assertFalse(Tailnet.isTailnetIp("pixel.tail1234.ts.net"))
    }

    @Test
    fun pickAddressPrefersIpv4OnAVpn() {
        val candidates = listOf(
            plain("192.168.1.20"),
            vpn("fd7a:115c:a1e0::1234%tun0"),
            vpn("100.101.102.103"),
        )
        assertEquals("100.101.102.103", Tailnet.pickAddress(candidates))
        assertEquals("fd7a:115c:a1e0::1234", Tailnet.pickAddress(listOf(vpn("fd7a:115c:a1e0::1234%tun0"))))
    }

    @Test
    fun carrierGradeNatOnMobileDataIsNotTailscale() {
        // Carriers hand out 100.64.0.0/10 too; only a VPN network counts.
        assertNull(Tailnet.pickAddress(listOf(plain("100.72.14.9"), plain("10.0.0.3"))))
        assertNull(Tailnet.pickAddress(listOf(vpn("10.8.0.2"))))
        assertNull(Tailnet.pickAddress(emptyList()))
        assertEquals("100.101.102.103", Tailnet.pickAddress(listOf(plain("100.72.14.9"), vpn("100.101.102.103"))))
    }

    @Test
    fun onlyMagicDnsNamesAreAccepted() {
        assertEquals("pixel-8.tail1234.ts.net", Tailnet.acceptDnsName("100.101.102.103", "pixel-8.tail1234.ts.net."))
        assertEquals("pixel-8.tail1234.ts.net", Tailnet.acceptDnsName("100.101.102.103", " Pixel-8.Tail1234.TS.NET "))
        // No PTR record: the resolver hands back the address itself.
        assertNull(Tailnet.acceptDnsName("100.101.102.103", "100.101.102.103"))
        assertNull(Tailnet.acceptDnsName("100.101.102.103", null))
        assertNull(Tailnet.acceptDnsName("100.101.102.103", "host.example.com"))
        assertNull(Tailnet.acceptDnsName("100.101.102.103", ".ts.net"))
        assertNull(Tailnet.acceptDnsName("100.101.102.103", "evil.ts.net.example.com"))
        assertNull(Tailnet.acceptDnsName("100.101.102.103", "bad name.ts.net"))
    }

    @Test
    fun mcpUrlBracketsIpv6() {
        assertEquals("http://pixel.tail1234.ts.net:8765/mcp", Tailnet.mcpUrl("pixel.tail1234.ts.net", 8765))
        assertEquals("http://100.101.102.103:9000/mcp", Tailnet.mcpUrl("100.101.102.103", 9000))
        assertEquals("http://[fd7a:115c:a1e0::1]:8765/mcp", Tailnet.mcpUrl("fd7a:115c:a1e0::1", 8765))
    }

    @Test
    fun hostIsTheNameWhenKnown() {
        assertEquals("100.101.102.103", TailnetAddress("100.101.102.103").host)
        assertEquals("pixel.tail1234.ts.net", TailnetAddress("100.101.102.103", "pixel.tail1234.ts.net").host)
    }
}
