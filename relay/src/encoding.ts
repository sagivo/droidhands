import { createHash, timingSafeEqual } from 'node:crypto';

/**
 * Encodings and the self-certifying device identity from docs/PROTOCOL.md §1.
 * Pure functions; no I/O.
 */

const BASE32_ALPHABET = 'abcdefghijklmnopqrstuvwxyz234567';

/** `[a-z2-7]{26}`: base32 of the first 16 bytes of SHA-256(deviceSecret). */
export const DEVICE_ID_REGEX = /^[a-z2-7]{26}$/;

/** base64url without padding, exactly 43 chars (= 32 bytes). */
const DEVICE_SECRET_REGEX = /^[A-Za-z0-9_-]{43}$/;

/** RFC 4648 base32, lowercase alphabet, no padding. */
export function base32Encode(bytes: Uint8Array): string {
  let out = '';
  let buffer = 0;
  let bits = 0;
  for (const byte of bytes) {
    buffer = (buffer << 8) | byte;
    bits += 8;
    while (bits >= 5) {
      out += BASE32_ALPHABET[(buffer >>> (bits - 5)) & 31];
      bits -= 5;
    }
    // Keep only the bits that have not been emitted yet so the accumulator never overflows.
    buffer &= (1 << bits) - 1;
  }
  if (bits > 0) {
    out += BASE32_ALPHABET[(buffer << (5 - bits)) & 31];
  }
  return out;
}

/**
 * Strictly decodes a canonical, unpadded base64url string. Returns null for any other alphabet,
 * padding, or a non-canonical encoding (non-zero trailing bits), so that a secret has exactly one
 * accepted spelling.
 */
export function base64UrlDecodeStrict(text: string): Buffer | null {
  if (!/^[A-Za-z0-9_-]*$/.test(text) || text.length % 4 === 1) return null;
  const bytes = Buffer.from(text, 'base64url');
  return bytes.toString('base64url') === text ? bytes : null;
}

/** `deviceId = base32(SHA-256(secret)[0..16])` → 26 chars. */
export function deriveDeviceId(secret: Uint8Array): string {
  const digest = createHash('sha256').update(secret).digest();
  return base32Encode(digest.subarray(0, 16));
}

/**
 * Decodes a device secret as sent in `hello.deviceSecret`: base64url, 43 chars, 32 bytes.
 * Returns null when malformed.
 */
export function decodeDeviceSecret(encoded: string): Buffer | null {
  if (!DEVICE_SECRET_REGEX.test(encoded)) return null;
  const bytes = base64UrlDecodeStrict(encoded);
  return bytes !== null && bytes.length === 32 ? bytes : null;
}

/**
 * True when [deviceSecret] is a well-formed secret whose derived id equals [deviceId].
 * The id comparison is constant-time.
 */
export function verifyDeviceIdentity(deviceId: string, deviceSecret: string): boolean {
  if (!DEVICE_ID_REGEX.test(deviceId)) return false;
  const secret = decodeDeviceSecret(deviceSecret);
  if (secret === null) return false;
  return constantTimeEqual(deriveDeviceId(secret), deviceId);
}

/**
 * Constant-time string equality. Both sides are hashed first so that neither the content nor the
 * length of the expected value leaks through timing.
 */
export function constantTimeEqual(a: string, b: string): boolean {
  const ha = createHash('sha256').update(a, 'utf8').digest();
  const hb = createHash('sha256').update(b, 'utf8').digest();
  return timingSafeEqual(ha, hb);
}
