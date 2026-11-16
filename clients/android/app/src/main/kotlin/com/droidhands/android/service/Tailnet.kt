package com.droidhands.android.service

import com.droidhands.android.ui.format.Hosts

/** The phone on its tailnet: the address the local server binds, and its MagicDNS name if known. */
data class TailnetAddress(
    /** e.g. "100.101.102.103" (preferred) or "fd7a:115c:a1e0::1234", without brackets or scope. */
    val address: String,
    /** e.g. "pixel-8.tail1234.ts.net", or null until (or unless) a reverse lookup found one. */
    val dnsName: String? = null,
) {
    /** What agents should use: the MagicDNS name, else the address. */
    val host: String get() = dnsName ?: address
}

/**
 * Pure Tailscale rules (unit-tested): which of the phone's addresses is its tailnet address, which
 * reverse-DNS answers count as its MagicDNS name, and the resulting MCP URL. The Android side
 * ([TailscaleMonitor]) only collects the inputs.
 */
object Tailnet {
    /** The Tailscale Android app, for "Get Tailscale" / "Open Tailscale". */
    const val TAILSCALE_PACKAGE = "com.tailscale.ipn"

    /** One address of one network the phone is on. */
    data class Candidate(val address: String, val onVpn: Boolean)

    /** True for an address in Tailscale's ranges, 100.64.0.0/10 or fd7a:115c:a1e0::/48 (not a host name). */
    fun isTailnetIp(address: String): Boolean {
        val ip = stripScope(Hosts.normalize(address))
        if (Hosts.parseIpv4(ip) == null && ':' !in ip) return false
        return Hosts.isTailscale(ip)
    }

    /**
     * The phone's tailnet address: an address in Tailscale's ranges on an active VPN network,
     * IPv4 first (shorter, and every client can reach it). Mobile carriers also hand out
     * 100.64.0.0/10 (carrier-grade NAT) on cellular networks, hence the VPN requirement.
     */
    fun pickAddress(candidates: List<Candidate>): String? {
        val tailnet = candidates.filter { it.onVpn && isTailnetIp(it.address) }.map { stripScope(Hosts.normalize(it.address)) }
        return tailnet.firstOrNull { Hosts.parseIpv4(it) != null } ?: tailnet.firstOrNull()
    }

    /**
     * The MagicDNS name from a reverse lookup of [address], or null. Only `*.ts.net` names count:
     * a resolver that answers with the address itself, or with some other name, isn't MagicDNS.
     */
    fun acceptDnsName(address: String, reverse: String?): String? {
        val name = reverse?.trim()?.trimEnd('.')?.lowercase() ?: return null
        if (name == Hosts.normalize(address) || !name.endsWith(".ts.net") || name.length <= ".ts.net".length) return null
        return name.takeIf { n -> n.all { it.isLetterOrDigit() || it == '-' || it == '.' } }
    }

    /** `http://<host>:<port>/mcp`, with brackets around an IPv6 address. */
    fun mcpUrl(host: String, port: Int): String {
        val h = if (':' in host) "[$host]" else host
        return "http://$h:$port/mcp"
    }

    private fun stripScope(address: String): String = address.substringBefore('%')
}
