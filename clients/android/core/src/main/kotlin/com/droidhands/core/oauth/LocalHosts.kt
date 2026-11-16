package com.droidhands.core.oauth

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Which `Host` headers name this phone, for the local server's OAuth URLs (docs/PROTOCOL.md §4.1).
 * Pure; never resolves names.
 *
 * The issuer and resource URLs are built from the `Host` the agent used, so they work over every
 * route (adb forward, Tailscale, Wi-Fi) without configuration. A `Host` is only trusted when it
 * names the address the connection actually arrived on (the socket's own address),
 * so a web page that rebinds its DNS name to the phone (or anything else lying in `Host`) gets no
 * metadata and cannot start a pairing:
 *
 * * arrived on loopback (on the phone, or through `adb forward`): `Host` must be `localhost` or a
 *   loopback literal (any port: adb may forward from another port);
 * * arrived on a Tailscale address (`100.64.0.0/10`, `fd7a:115c:a1e0::/48`): `Host` must be that
 *   address, or a MagicDNS name (`*.ts.net`, which only the tailnet's resolver answers);
 * * arrived on a private LAN address (RFC 1918, link-local IPv4, IPv6 ULA): `Host` must be that address.
 *   The server only sees such connections when the user bound it to the LAN (or to that address).
 *
 * Anything else (public addresses, other names such as `phone.local` or `evil.example`) is refused.
 */
object LocalHosts {
    enum class Route { LOOPBACK, TAILNET, LAN }

    /** A parsed `Host` header: [host] lowercase (IPv6 without brackets), [port] or null. */
    data class HostHeader(val host: String, val port: Int?, val address: InetAddress?) {
        /** `host[:port]` as it goes into a URL (IPv6 in brackets, default port 80 omitted). */
        val authority: String
            get() = (if (address is Inet6Address) "[$host]" else host) + if (port != null && port != 80) ":$port" else ""
    }

    private val HOST_NAME = Regex("^(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*$")
    private val IPV4 = Regex("^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$")
    private val IPV6 = Regex("^[0-9a-f:.]{2,45}$")
    private const val MAGIC_DNS_SUFFIX = ".ts.net"

    /**
     * `http://<authority>` for a request carrying [hostHeader] that arrived on [arrivedOn], or null
     * when that `Host` does not name this phone on that route (see the class comment).
     */
    fun issuer(hostHeader: String?, arrivedOn: InetAddress?): String? {
        val localAddress = arrivedOn?.let(::unmapped) ?: return null
        val route = route(localAddress) ?: return null
        val host = hostHeader?.let(::parse) ?: return null
        val accepted = when {
            host.address != null -> when (route) {
                Route.LOOPBACK -> host.address.isLoopbackAddress
                else -> host.address == localAddress
            }
            host.host == "localhost" -> route == Route.LOOPBACK
            else -> route == Route.TAILNET && host.host.endsWith(MAGIC_DNS_SUFFIX)
        }
        return if (accepted) "http://${host.authority}" else null
    }

    /** How a connection that arrived on [arrivedOn] reached the phone, or null for any other address. */
    fun route(arrivedOn: InetAddress): Route? {
        val address = unmapped(arrivedOn)
        val b = address.address.map { it.toInt() and 0xff }
        return when {
            address.isLoopbackAddress -> Route.LOOPBACK
            address is Inet4Address -> when {
                b[0] == 100 && b[1] in 64..127 -> Route.TAILNET
                b[0] == 10 || (b[0] == 172 && b[1] in 16..31) || (b[0] == 192 && b[1] == 168) -> Route.LAN
                b[0] == 169 && b[1] == 254 -> Route.LAN
                else -> null
            }
            address is Inet6Address -> when {
                b[0] == 0xfd && b[1] == 0x7a && b[2] == 0x11 && b[3] == 0x5c && b[4] == 0xa1 && b[5] == 0xe0 -> Route.TAILNET
                (b[0] and 0xfe) == 0xfc -> Route.LAN
                else -> null
            }
            else -> null
        }
    }

    /** Parses a `Host` header value; null when malformed (userinfo, path, bad port, zone id, …). */
    fun parse(raw: String): HostHeader? {
        val value = raw.trim().lowercase()
        if (value.isEmpty() || value.length > 300) return null
        val hostPart: String
        val portPart: String?
        if (value.startsWith('[')) {
            val close = value.indexOf(']')
            if (close < 0) return null
            hostPart = value.substring(1, close)
            val rest = value.substring(close + 1)
            portPart = when {
                rest.isEmpty() -> null
                rest.startsWith(':') -> rest.substring(1)
                else -> return null
            }
            if (!IPV6.matches(hostPart) || ':' !in hostPart) return null
        } else {
            if (value.count { it == ':' } > 1) return null
            hostPart = value.substringBefore(':')
            portPart = if (':' in value) value.substringAfter(':') else null
        }
        val port = when (portPart) {
            null -> null
            else -> portPart.takeIf { it.length in 1..5 && it.all(Char::isDigit) }?.toInt()?.takeIf { it in 1..65535 } ?: return null
        }
        val address = when {
            value.startsWith('[') -> literal(hostPart)?.takeIf { it is Inet6Address } ?: return null
            IPV4.matches(hostPart) -> literal(hostPart) ?: return null
            HOST_NAME.matches(hostPart) && !hostPart.all { it.isDigit() || it == '.' } -> null
            else -> return null
        }
        // The agent's own spelling (e.g. `::1`), so the URLs match the one it was given.
        return HostHeader(hostPart, port, address)
    }

    /** [address] as IPv4 if it is an IPv4-mapped IPv6 address (`::ffff:a.b.c.d`, as dual-stack sockets may report). */
    private fun unmapped(address: InetAddress): InetAddress {
        val b = address.address
        if (b.size != 16 || (0 until 10).any { b[it] != 0.toByte() } || b[10] != 0xff.toByte() || b[11] != 0xff.toByte()) return address
        return InetAddress.getByAddress(b.copyOfRange(12, 16))
    }

    /** An IP literal (already syntax-checked, so no name lookup happens). */
    private fun literal(text: String): InetAddress? = try {
        InetAddress.getByName(text)
    } catch (e: UnknownHostException) {
        null
    }
}
