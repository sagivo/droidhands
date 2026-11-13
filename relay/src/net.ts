import { isIP, isIPv4 } from 'node:net';
import type { IncomingMessage } from 'node:http';
import type { TLSSocket } from 'node:tls';

/**
 * Client-address and URL helpers. Proxy headers (X-Forwarded-*) are honored only when the
 * operator sets TRUST_PROXY=true, because otherwise any client could spoof them.
 */

/** Strips the IPv4-mapped IPv6 prefix (`::ffff:1.2.3.4` → `1.2.3.4`) and any zone id. */
export function normalizeIp(ip: string): string {
  const withoutZone = ip.split('%')[0] ?? ip;
  const mapped = /^::ffff:(\d+\.\d+\.\d+\.\d+)$/i.exec(withoutZone);
  return mapped?.[1] ?? withoutZone.toLowerCase();
}

/**
 * The agent's or device's address. With [trustProxy] the right-most valid `X-Forwarded-For` entry
 * is used: it is the one appended by the (single) trusted reverse proxy, so clients cannot spoof
 * it by sending their own header.
 */
export function clientAddress(req: IncomingMessage, trustProxy: boolean): string {
  if (trustProxy) {
    const forwarded = headerValue(req, 'x-forwarded-for');
    if (forwarded !== undefined) {
      const hops = forwarded.split(',').map((h) => h.trim()).filter((h) => h !== '');
      const last = hops[hops.length - 1];
      if (last !== undefined && isIP(normalizeIp(last)) !== 0) return normalizeIp(last);
    }
  }
  return normalizeIp(req.socket.remoteAddress ?? 'unknown');
}

/**
 * The key used for per-IP rate limiting. IPv6 clients are grouped by /64, because a single
 * subscriber usually controls a whole /64 and could otherwise rotate addresses freely.
 */
export function rateLimitKey(ip: string): string {
  const normalized = normalizeIp(ip);
  if (isIPv4(normalized) || isIP(normalized) === 0) return normalized;
  const groups = expandIpv6(normalized);
  return groups === null ? normalized : `${groups.slice(0, 4).join(':')}::/64`;
}

/** Expands an IPv6 address into its 8 hextets (lowercase, no leading zeros), or null if invalid. */
export function expandIpv6(ip: string): string[] | null {
  let text = ip.toLowerCase();
  // Convert a trailing dotted IPv4 part (e.g. 64:ff9b::1.2.3.4) into two hextets.
  const v4 = /(\d+)\.(\d+)\.(\d+)\.(\d+)$/.exec(text);
  if (v4 !== null) {
    const [a, b, c, d] = v4.slice(1).map(Number) as [number, number, number, number];
    text = `${text.slice(0, v4.index)}${((a << 8) | b).toString(16)}:${((c << 8) | d).toString(16)}`;
  }
  const halves = text.split('::');
  if (halves.length > 2) return null;
  const head = halves[0] === '' ? [] : (halves[0] ?? '').split(':');
  const tail = halves.length === 2 && halves[1] !== '' ? (halves[1] ?? '').split(':') : [];
  const missing = 8 - head.length - tail.length;
  if (halves.length === 1 ? missing !== 0 : missing < 1) return null;
  const groups = [...head, ...Array<string>(halves.length === 2 ? missing : 0).fill('0'), ...tail];
  if (!groups.every((g) => /^[0-9a-f]{1,4}$/.test(g))) return null;
  return groups.map((g) => parseInt(g, 16).toString(16));
}

/** First value of a (possibly repeated or comma-joined) request header, trimmed. */
export function firstHeaderValue(req: IncomingMessage, name: string): string | undefined {
  const value = headerValue(req, name);
  const first = value?.split(',')[0]?.trim();
  return first === '' ? undefined : first;
}

/** A request header as a single string (repeated headers joined with ", "). */
export function headerValue(req: IncomingMessage, name: string): string | undefined {
  const value = req.headers[name];
  return Array.isArray(value) ? value.join(', ') : value;
}

const HOST_REGEX = /^(?:[a-z0-9.-]+|\[[0-9a-f:.]+\])(?::\d{1,5})?$/i;

/**
 * The public base URL (no trailing slash) as seen by the client of [req]: PUBLIC_URL when
 * configured, otherwise derived from the Host header (or X-Forwarded-Proto/Host behind a trusted
 * proxy), falling back to the local socket address.
 */
export function publicBaseUrl(req: IncomingMessage, publicUrl: string | null, trustProxy: boolean): string {
  if (publicUrl !== null) return publicUrl;

  const encrypted = (req.socket as Partial<TLSSocket>).encrypted === true;
  let scheme = encrypted ? 'https' : 'http';
  let host = req.headers.host;
  if (trustProxy) {
    const proto = firstHeaderValue(req, 'x-forwarded-proto')?.toLowerCase();
    if (proto === 'https' || proto === 'wss') scheme = 'https';
    else if (proto === 'http' || proto === 'ws') scheme = 'http';
    host = firstHeaderValue(req, 'x-forwarded-host') ?? host;
  }
  if (host === undefined || !HOST_REGEX.test(host)) {
    const local = req.socket.localAddress ?? '127.0.0.1';
    const address = isIP(local) === 6 && !local.startsWith('::ffff:') ? `[${local}]` : normalizeIp(local);
    host = `${address}:${req.socket.localPort ?? 80}`;
  }
  return `${scheme}://${host.toLowerCase()}`;
}
