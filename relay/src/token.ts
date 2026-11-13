/**
 * Agent token parsing (docs/PROTOCOL.md §2). The relay only checks the token's *shape* and extracts
 * the deviceId to route on; the secret part is never validated, stored or logged here. The device is
 * the only authority on whether a token is valid.
 */

/** `pb1.<deviceId>.<tokenId>.<tokenSecret>` */
export const TOKEN_REGEX = /^pb1\.[a-z2-7]{26}\.[a-z2-7]{12}\.[A-Za-z0-9_-]{43}$/;

/** `Bearer <token>`; the scheme is case-insensitive (RFC 9110 §11.1). */
const BEARER_REGEX = /^bearer[ \t]+([^ \t]+)[ \t]*$/i;

/** A syntactically valid agent token and the device it is addressed to. */
export interface ParsedToken {
  /** The full token, to be forwarded to the device. Never log it. */
  readonly token: string;
  readonly deviceId: string;
}

/** Validates the token format and extracts the deviceId. Returns null when malformed. */
export function parseToken(token: string): ParsedToken | null {
  if (!TOKEN_REGEX.test(token)) return null;
  // The regex guarantees exactly four dot-separated parts.
  const deviceId = token.split('.')[1] as string;
  return { token, deviceId };
}

/**
 * Extracts the bearer credential from an `Authorization` header value. Returns null when the
 * header is absent or uses another scheme. The credential itself is not validated here.
 */
export function bearerCredential(header: string | undefined): string | null {
  if (header === undefined) return null;
  const match = BEARER_REGEX.exec(header.trim());
  return match?.[1] ?? null;
}
