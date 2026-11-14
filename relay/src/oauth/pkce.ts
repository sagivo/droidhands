import { createHash } from 'node:crypto';
import { constantTimeEqual } from '../encoding.js';

/**
 * PKCE (RFC 7636) with the only method this relay supports, `S256`. Pure.
 */

/** BASE64URL(SHA-256(verifier)) of a 32-byte digest: always 43 chars. */
export const CODE_CHALLENGE_REGEX = /^[A-Za-z0-9_-]{43}$/;

/** RFC 7636 §4.1: 43-128 unreserved characters. */
export const CODE_VERIFIER_REGEX = /^[A-Za-z0-9._~-]{43,128}$/;

/** `BASE64URL(SHA256(ASCII(verifier)))`. */
export function s256Challenge(verifier: string): string {
  return createHash('sha256').update(verifier, 'ascii').digest('base64url');
}

/** True when [verifier] is well-formed and hashes to [challenge] (constant-time comparison). */
export function verifyPkce(verifier: string, challenge: string): boolean {
  if (!CODE_VERIFIER_REGEX.test(verifier)) return false;
  return constantTimeEqual(s256Challenge(verifier), challenge);
}
