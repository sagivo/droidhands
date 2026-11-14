import { createHmac, timingSafeEqual } from 'node:crypto';
import { base64UrlDecodeStrict } from '../encoding.js';
import { sanitizeDisplayText } from '../text.js';
import { redirectUriProblem } from './redirectUris.js';

/**
 * RFC 7591 dynamic registration for public clients, without server-side state
 * (docs/PROTOCOL.md §5.2). The registration lives in the `client_id` itself:
 *
 *     client_id = base64url(json{n: client_name, r: redirect_uris, t: issuedAt}) + "." +
 *                 base64url(HMAC-SHA256(relaySecret, <first part>))
 *
 * so any relay process holding the same secret accepts it, and nobody can alter the name or
 * redirect URIs without invalidating the MAC. Pure; no I/O.
 */

/** Longest client name kept (in code points); longer names are truncated. */
export const MAX_CLIENT_NAME_LENGTH = 60;

/** Most redirect URIs per client (keeps the client_id short enough for URLs). */
export const MAX_REDIRECT_URIS = 5;

/** Longest client_id accepted when decoding; anything longer cannot be one of ours. */
const MAX_CLIENT_ID_LENGTH = 4096;

/** Name used when a client registers without `client_name`. */
export const DEFAULT_CLIENT_NAME = 'Unnamed app';

/** A verified registration, as decoded from a client_id. */
export interface RegisteredClient {
  readonly clientName: string;
  readonly redirectUris: readonly string[];
  /** Seconds since the epoch. */
  readonly issuedAt: number;
}

/** RFC 7591 §3.2.1 response body. */
export interface RegistrationResponse {
  readonly client_id: string;
  readonly client_name: string;
  readonly redirect_uris: readonly string[];
  readonly token_endpoint_auth_method: 'none';
  readonly grant_types: readonly ['authorization_code'];
  readonly response_types: readonly ['code'];
  readonly client_id_issued_at: number;
}

export type RegistrationError = 'invalid_redirect_uri' | 'invalid_client_metadata';

export type RegistrationResult =
  | { readonly ok: true; readonly response: RegistrationResponse }
  | { readonly ok: false; readonly error: RegistrationError; readonly description: string };

/**
 * Validates RFC 7591 client metadata and issues a stateless client_id. Requested values this relay
 * cannot honor (e.g. `client_secret_basic`, `refresh_token`) are replaced with the supported ones,
 * as RFC 7591 §3.2.1 allows; the response tells the client what it got.
 */
export function registerClient(metadata: unknown, key: Buffer, nowMs: number): RegistrationResult {
  const fail = (error: RegistrationError, description: string): RegistrationResult => ({ ok: false, error, description });
  if (typeof metadata !== 'object' || metadata === null || Array.isArray(metadata)) {
    return fail('invalid_client_metadata', 'the request body must be a JSON object');
  }
  const m = metadata as Record<string, unknown>;

  const uris = m.redirect_uris;
  if (!Array.isArray(uris) || uris.length === 0) {
    return fail('invalid_redirect_uri', 'redirect_uris must be a non-empty array of URIs');
  }
  if (uris.length > MAX_REDIRECT_URIS) {
    return fail('invalid_redirect_uri', `at most ${MAX_REDIRECT_URIS} redirect_uris are allowed`);
  }
  for (const uri of uris) {
    if (typeof uri !== 'string') return fail('invalid_redirect_uri', 'redirect_uris must contain strings');
    const problem = redirectUriProblem(uri);
    if (problem !== null) return fail('invalid_redirect_uri', problem);
  }
  if (m.client_name !== undefined && typeof m.client_name !== 'string') {
    return fail('invalid_client_metadata', 'client_name must be a string');
  }
  if (!listAbsentOrIncludes(m.grant_types, 'authorization_code')) {
    return fail('invalid_client_metadata', 'grant_types must include "authorization_code" (the only supported grant)');
  }
  if (!listAbsentOrIncludes(m.response_types, 'code')) {
    return fail('invalid_client_metadata', 'response_types must include "code" (the only supported response type)');
  }

  const client: RegisteredClient = {
    clientName: sanitizeClientName(m.client_name),
    redirectUris: [...new Set(uris as string[])],
    issuedAt: Math.floor(nowMs / 1000),
  };
  return {
    ok: true,
    response: {
      client_id: encodeClientId(client, key),
      client_name: client.clientName,
      redirect_uris: client.redirectUris,
      token_endpoint_auth_method: 'none',
      grant_types: ['authorization_code'],
      response_types: ['code'],
      client_id_issued_at: client.issuedAt,
    },
  };
}

/** Serializes and signs [client] into a client_id. */
export function encodeClientId(client: RegisteredClient, key: Buffer): string {
  const payload = Buffer.from(
    JSON.stringify({ n: client.clientName, r: client.redirectUris, t: client.issuedAt }),
    'utf8',
  ).toString('base64url');
  return `${payload}.${mac(payload, key)}`;
}

/**
 * Verifies the MAC (constant-time) and decodes a client_id. Returns null for anything that this
 * relay (with this [key]) did not issue, or whose contents are no longer acceptable.
 */
export function decodeClientId(clientId: string, key: Buffer): RegisteredClient | null {
  if (clientId.length > MAX_CLIENT_ID_LENGTH) return null;
  const parts = clientId.split('.');
  if (parts.length !== 2) return null;
  const [payload, signature] = parts as [string, string];
  const given = base64UrlDecodeStrict(signature);
  const expected = Buffer.from(mac(payload, key), 'base64url');
  if (given === null || given.length !== expected.length || !timingSafeEqual(given, expected)) return null;

  const json = base64UrlDecodeStrict(payload);
  if (json === null) return null;
  let value: unknown;
  try {
    value = JSON.parse(json.toString('utf8'));
  } catch {
    return null;
  }
  if (typeof value !== 'object' || value === null) return null;
  const { n, r, t } = value as Record<string, unknown>;
  if (typeof n !== 'string' || !Number.isSafeInteger(t) || !Array.isArray(r) || r.length === 0) return null;
  // Defense in depth: re-check what the MAC vouches for, in case the rules got stricter since.
  if (!r.every((uri) => typeof uri === 'string' && redirectUriProblem(uri) === null)) return null;
  return { clientName: sanitizeClientName(n), redirectUris: r as string[], issuedAt: t as number };
}

/**
 * Makes a client-supplied name safe to show on the phone and in HTML: strips control, bidi and
 * line-separator characters, collapses whitespace and truncates to [MAX_CLIENT_NAME_LENGTH] code
 * points. Empty or missing names become [DEFAULT_CLIENT_NAME]. (HTML escaping happens at render.)
 */
export function sanitizeClientName(raw: unknown): string {
  if (typeof raw !== 'string') return DEFAULT_CLIENT_NAME;
  const cleaned = raw
    .replace(/[\p{Cc}\p{Zl}\p{Zp}؜​-‏‪-‮⁠-⁩﻿]/gu, ' ')
    .replace(/\s+/g, ' ')
    .trim();
  const truncated = Array.from(cleaned).slice(0, MAX_CLIENT_NAME_LENGTH).join('').trim();
  return truncated === '' ? DEFAULT_CLIENT_NAME : truncated;
}

function mac(payload: string, key: Buffer): string {
  return createHmac('sha256', key).update(payload, 'utf8').digest('base64url');
}

function listAbsentOrIncludes(value: unknown, required: string): boolean {
  if (value === undefined) return true;
  return Array.isArray(value) && value.includes(required);
}
