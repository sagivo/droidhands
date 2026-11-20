/** Small Web Crypto helpers (Workers runtime). */

const encoder = new TextEncoder();

export function base64url(bytes: Uint8Array): string {
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

/** [byteCount] random bytes, base64url. */
export function randomToken(byteCount = 32): string {
  return base64url(crypto.getRandomValues(new Uint8Array(byteCount)));
}

/** base64url(SHA-256(UTF-8 [text])): the PKCE S256 transform (RFC 7636 §4.2). */
export async function sha256base64url(text: string): Promise<string> {
  return base64url(new Uint8Array(await crypto.subtle.digest('SHA-256', encoder.encode(text))));
}

/** Compares two strings without revealing where they differ. */
export function constantTimeEqual(a: string, b: string): boolean {
  const x = encoder.encode(a);
  const y = encoder.encode(b);
  if (x.length !== y.length) return false;
  let diff = 0;
  for (let i = 0; i < x.length; i++) diff |= x[i]! ^ y[i]!;
  return diff === 0;
}

/** RFC 7636 §4.1: code verifiers and S256 challenges are 43–128 characters of this alphabet. */
const PKCE_VALUE = /^[A-Za-z0-9._~-]{43,128}$/;

export function isPkceValue(value: unknown): value is string {
  return typeof value === 'string' && PKCE_VALUE.test(value);
}
