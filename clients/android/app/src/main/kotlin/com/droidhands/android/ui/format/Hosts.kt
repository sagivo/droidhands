package com.droidhands.android.ui.format

/**
 * Host classification for URL warnings and endpoint labels. Pure string logic: never resolves
 * names, so it is safe on any thread and in unit tests.
 */
object Hosts {
    private val LAN_SUFFIXES = listOf(".local", ".lan", ".home.arpa", ".internal")

    /** Removes the brackets around an IPv6 literal ("[::1]" -> "::1") and lowercases. */
    fun normalize(host: String): String = host.trim().removePrefix("[").removeSuffix("]").lowercase()

    /** `localhost`, `*.localhost`, 127.0.0.0/8 and `::1`. */
    fun isLoopback(host: String): Boolean {
        val h = normalize(host)
        if (h == "localhost" || h.endsWith(".localhost")) return true
        parseIpv4(h)?.let { return it[0] == 127 }
        return firstIpv6Hextet(h)?.let { isIpv6Loopback(h) } ?: false
    }

    /** Tailscale: CGNAT range 100.64.0.0/10, its IPv6 prefix fd7a:115c:a1e0::/48, and MagicDNS `*.ts.net`. */
    fun isTailscale(host: String): Boolean {
        val h = normalize(host)
        if (h.endsWith(".ts.net")) return true
        parseIpv4(h)?.let { return it[0] == 100 && it[1] in 64..127 }
        return h.startsWith("fd7a:115c:a1e0:")
    }

    /**
     * Private / link-local networks: RFC 1918 (incl. the emulator's 10.0.2.2), 169.254.0.0/16,
     * IPv6 ULA fc00::/7 and link-local fe80::/10, mDNS/LAN suffixes, and single-label names.
     */
    fun isPrivateNetwork(host: String): Boolean {
        val h = normalize(host)
        parseIpv4(h)?.let { ip ->
            return ip[0] == 10 ||
                (ip[0] == 172 && ip[1] in 16..31) ||
                (ip[0] == 192 && ip[1] == 168) ||
                (ip[0] == 169 && ip[1] == 254)
        }
        firstIpv6Hextet(h)?.let { first ->
            return (first and 0xfe00) == 0xfc00 || (first and 0xffc0) == 0xfe80
        }
        if (LAN_SUFFIXES.any { h.endsWith(it) }) return true
        // A dotless name like "raspberrypi" only resolves on the local network.
        return h.isNotEmpty() && '.' !in h && h.all { it.isLetterOrDigit() || it == '-' }
    }

    /** True if unencrypted http:// to [host] does not cross the public internet. */
    fun isTrustedForPlainHttp(host: String): Boolean = isLoopback(host) || isTailscale(host) || isPrivateNetwork(host)

    /** Parses a dotted-quad IPv4 literal, or null. */
    fun parseIpv4(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val out = IntArray(4)
        for ((i, p) in parts.withIndex()) {
            if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' }) return null
            val v = p.toInt()
            if (v > 255) return null
            out[i] = v
        }
        return out
    }

    /** First 16-bit group of an IPv6 literal (0 for "::…"), or null if [host] isn't IPv6. */
    private fun firstIpv6Hextet(host: String): Int? {
        if (':' !in host) return null
        val first = host.substringBefore(':')
        if (first.isEmpty()) return 0
        if (first.length > 4 || !first.all { it.isDigit() || it in 'a'..'f' }) return null
        return first.toInt(16)
    }

    private fun isIpv6Loopback(host: String): Boolean {
        val h = host.substringBefore('%')
        if (h == "::1") return true
        val groups = h.split(':')
        return groups.size == 8 && groups.dropLast(1).all { it.trimStart('0').isEmpty() } && groups.last().trimStart('0') == "1"
    }
}
