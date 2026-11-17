/**
 * One-tap pairing frames on the device tunnel (docs/PROTOCOL.md §5.3). Pure.
 */
import { parseToken } from '../token.js';

/** Relay → device: show an approval prompt. */
export interface PairRequestFrame {
  type: 'pair_request';
  id: string;
  clientName: string;
  redirectHost: string;
  displayCode: string;
  remoteAddress: string;
  userAgent?: string;
  expiresAt: number;
  /** `device`: agent sign-in (§5.6), no browser; absent for the browser flow. */
  flow?: 'device';
}

/** Relay → device: the pairing expired before the phone answered; remove the prompt. */
export interface PairCancelFrame {
  type: 'pair_cancel';
  id: string;
}

/** Relay → device: the agent received the token. */
export interface PairCompleteFrame {
  type: 'pair_complete';
  id: string;
  tokenId: string;
}

/** Relay → device: no agent received the token (if any); delete it. */
export interface PairAbandonedFrame {
  type: 'pair_abandoned';
  id: string;
  tokenId?: string;
}

export type RelayPairFrame = PairRequestFrame | PairCancelFrame | PairCompleteFrame | PairAbandonedFrame;

/** Reasons a device may give for not approving. Anything else is reported as `denied`. */
export const DENY_REASONS = ['denied', 'timeout', 'busy'] as const;
export type DenyReason = (typeof DENY_REASONS)[number];

/** A validated `pair_response`. */
export type PairResponse =
  | { readonly kind: 'approved'; readonly id: string; readonly accessToken: string; readonly tokenId: string }
  | { readonly kind: 'denied'; readonly id: string; readonly reason: DenyReason }
  /** `approved: true` with an unusable token; [tokenId] is set when it can be named for deletion. */
  | { readonly kind: 'invalid'; readonly id: string; readonly tokenId: string | null };

const TOKEN_ID_REGEX = /^[a-z2-7]{12}$/;

/** Longest pairing id accepted from a device (ours are 24 chars). */
const MAX_ID_LENGTH = 64;

/**
 * Validates a `pair_response` from the device [deviceId]. An approved token must be a well-formed
 * `pb1` token for that same device, and `tokenId` must be its id part. Returns null without a
 * usable `id`.
 */
export function parsePairResponse(frame: Record<string, unknown>, deviceId: string): PairResponse | null {
  const { id, approved, accessToken, tokenId, reason } = frame;
  if (typeof id !== 'string' || id.length === 0 || id.length > MAX_ID_LENGTH) return null;
  if (approved !== true) {
    const known = (DENY_REASONS as readonly unknown[]).includes(reason) ? (reason as DenyReason) : 'denied';
    return { kind: 'denied', id, reason: known };
  }
  const namedTokenId = typeof tokenId === 'string' && TOKEN_ID_REGEX.test(tokenId) ? tokenId : null;
  const token = typeof accessToken === 'string' ? parseToken(accessToken) : null;
  if (token === null || token.deviceId !== deviceId || namedTokenId === null) {
    return { kind: 'invalid', id, tokenId: namedTokenId };
  }
  if (token.token.split('.')[2] !== namedTokenId) return { kind: 'invalid', id, tokenId: namedTokenId };
  return { kind: 'approved', id, accessToken: token.token, tokenId: namedTokenId };
}
