import type { IncomingMessage, ServerResponse } from 'node:http';
import { readBody } from '../body.js';
import { constantTimeEqual } from '../encoding.js';
import { admit, clientInfo, type ClientInfo, type HttpDeps } from '../httpDeps.js';
import { applyOpenCors, sendJson } from '../httpUtil.js';
import { deviceTag } from '../logger.js';
import { headerValue } from '../net.js';
import { isKnownClientId } from './clientRegistration.js';
import { DEVICE_CODE_GRANT, bodyParams, checkDeviceTokenRequest, deviceTokenError } from './deviceFlow.js';
import { OAUTH_SCOPE, deviceIdFromResource } from './metadata.js';
import type { CodeGrant } from './pairingStore.js';
import { verifyPkce } from './pkce.js';

/**
 * `POST /oauth/token` (docs/PROTOCOL.md §5.4): exchanges a one-time authorization code for the
 * token the phone issued, or, for agent sign-in (§5.6), a device code once the phone approved it.
 * Errors follow RFC 6749 §5.2 (and RFC 8628 §3.5) with status 400.
 */

/** Token requests are a handful of short parameters. */
export const MAX_TOKEN_BODY_BYTES = 16 * 1024;

/** Token request parameters that must not repeat (RFC 6749 §3.2). */
const SINGLE_PARAMS = ['grant_type', 'code', 'redirect_uri', 'client_id', 'code_verifier', 'resource'] as const;

export type TokenError = 'invalid_request' | 'invalid_client' | 'invalid_grant' | 'unsupported_grant_type' | 'invalid_target';

/** `error_description` for an unknown, expired or used code. */
export const INVALID_CODE_DESCRIPTION = 'the authorization code is invalid, expired or already used';

export type TokenRequestCheck =
  | { readonly ok: true; readonly clientId: string; readonly code: string; readonly verifier: string }
  | {
      readonly ok: false;
      readonly error: TokenError;
      readonly description: string;
      /** Counts toward the client's authentication-failure limit (an unknown client). */
      readonly authFailure: boolean;
    };

/**
 * The checks of a token request before its code is looked up, shared by the Node and Worker relays:
 * repeated parameters, grant type, required parameters and the client (HMAC). Pure.
 */
export function checkTokenRequest(
  params: URLSearchParams,
  authorization: string | undefined,
  relaySecret: Buffer,
): TokenRequestCheck {
  const fail = (error: TokenError, description: string, authFailure = false): TokenRequestCheck => ({
    ok: false,
    error,
    description,
    authFailure,
  });
  const repeated = SINGLE_PARAMS.find((name) => params.getAll(name).length > 1);
  if (repeated !== undefined) return fail('invalid_request', `"${repeated}" must not repeat`);
  const grantType = params.get('grant_type');
  if (grantType === null) return fail('invalid_request', 'grant_type is required');
  if (grantType !== 'authorization_code') {
    return fail('unsupported_grant_type', `only authorization_code and ${DEVICE_CODE_GRANT} are supported; to reconnect, authorize again`);
  }
  const clientId = requestClientId(params, authorization);
  const code = params.get('code');
  const verifier = params.get('code_verifier');
  if (clientId === undefined) return fail('invalid_request', 'client_id in the body and in Basic auth differ');
  if (clientId === null || code === null || verifier === null) {
    return fail('invalid_request', 'client_id, code and code_verifier are required');
  }
  if (!isKnownClientId(clientId, relaySecret)) {
    return fail('invalid_client', 'unknown client_id; register again', true);
  }
  return { ok: true, clientId, code, verifier };
}

/** The successful token response (§5.4) for [grant]. Contains the token: never log it. */
export function tokenResponseBody(grant: CodeGrant): string {
  return JSON.stringify({ access_token: grant.accessToken, token_type: 'Bearer', scope: OAUTH_SCOPE });
}

export async function handleToken(deps: HttpDeps, req: IncomingMessage, res: ServerResponse): Promise<void> {
  if (applyOpenCors(req, res, 'POST')) return;
  res.setHeader('Pragma', 'no-cache');
  if (req.method !== 'POST') {
    res.setHeader('Allow', 'POST, OPTIONS');
    return sendJson(res, 405, { error: 'invalid_request', error_description: 'use POST' });
  }
  const client = clientInfo(deps, req);
  const decision = admit(deps, client);
  if (!decision.allowed) {
    res.setHeader('Retry-After', String(decision.retryAfterSec));
    return sendJson(res, 429, { error: 'invalid_request', error_description: 'too many requests; retry later' });
  }
  const contentType = headerValue(req, 'content-type');
  if (!isTokenContentType(contentType)) return fail(res, 'invalid_request', TOKEN_CONTENT_TYPE_DESCRIPTION);
  const body = await readBody(req, MAX_TOKEN_BODY_BYTES);
  if (body.kind === 'aborted') return;
  if (body.kind === 'too_large') {
    res.setHeader('Connection', 'close');
    return sendJson(res, 413, { error: 'invalid_request', error_description: 'request too large' });
  }

  const params = bodyParams(contentType, body.body);
  if (params === null) return fail(res, 'invalid_request', 'the body is not valid JSON');
  if (params.getAll('grant_type').length === 1 && params.get('grant_type') === DEVICE_CODE_GRANT) {
    return redeemDeviceCode(deps, res, client, params);
  }
  if (!isFormContentType(contentType)) return fail(res, 'invalid_request', FORM_ONLY_DESCRIPTION);
  const checked = checkTokenRequest(params, headerValue(req, 'authorization'), deps.relaySecret);
  if (!checked.ok) {
    return checked.authFailure
      ? rejectAuth(deps, res, client, checked.error, checked.description)
      : fail(res, checked.error, checked.description);
  }
  const { clientId, code, verifier } = checked;

  const grant = deps.pairings.takeCode(code);
  if (grant === null) {
    return rejectAuth(deps, res, client, 'invalid_grant', INVALID_CODE_DESCRIPTION);
  }
  // From here on the code is consumed: any mismatch abandons the pairing so the phone deletes the token.
  const problem = checkGrant(grant, clientId, params, verifier);
  if (problem !== null) {
    deps.pairings.finishExchange(grant, false);
    return rejectAuth(deps, res, client, problem.error, problem.description, grant.deviceId);
  }

  const payload = tokenResponseBody(grant);
  grant.accessToken = '';
  // pair_complete only once the response has been handed to the network; abandoned otherwise.
  res.once('close', () => deps.pairings.finishExchange(grant, res.writableFinished));
  deps.logger.info('token issued', { device: deviceTag(grant.deviceId), ip: client.ip });
  res.statusCode = 200;
  res.setHeader('Content-Type', 'application/json');
  res.setHeader('Content-Length', String(Buffer.byteLength(payload)));
  res.end(payload);
}

/**
 * Form-encoded (RFC 6749), or JSON for the device-code grant of agents writing requests by hand
 * (§5.6). Anything else is refused before the body is read.
 */
export function isTokenContentType(contentType: string | undefined): boolean {
  const type = contentType?.split(';')[0]?.trim().toLowerCase();
  return type === 'application/x-www-form-urlencoded' || type === 'application/json';
}

export const TOKEN_CONTENT_TYPE_DESCRIPTION =
  'the body must be application/x-www-form-urlencoded (JSON is accepted for the device_code grant)';

/** The authorization code grant keeps to RFC 6749's form encoding; only agent sign-in takes JSON. */
export function isFormContentType(contentType: string | undefined): boolean {
  return contentType?.split(';')[0]?.trim().toLowerCase() === 'application/x-www-form-urlencoded';
}

export const FORM_ONLY_DESCRIPTION = 'the body must be application/x-www-form-urlencoded';

/** The device-code grant (§5.6 step 3): the token once the phone approved, else an RFC 8628 error. */
function redeemDeviceCode(deps: HttpDeps, res: ServerResponse, client: ClientInfo, params: URLSearchParams): void {
  const checked = checkDeviceTokenRequest(params);
  if (!checked.ok) {
    return checked.authFailure ? rejectAuth(deps, res, client, 'invalid_grant', checked.description) : fail(res, 'invalid_request', checked.description);
  }
  const result = deps.pairings.redeem(checked.id, checked.pageToken);
  if (result.status !== 'approved') {
    const error = deviceTokenError(result);
    if (result.status === 'invalid') return rejectAuth(deps, res, client, 'invalid_grant', error.description);
    return sendJson(res, error.status, { error: error.error, error_description: error.description });
  }
  const { grant } = result;
  const payload = tokenResponseBody(grant);
  grant.accessToken = '';
  res.once('close', () => deps.pairings.finishExchange(grant, res.writableFinished));
  deps.logger.info('token issued', { device: deviceTag(grant.deviceId), ip: client.ip, flow: 'device' });
  res.statusCode = 200;
  res.setHeader('Content-Type', 'application/json');
  res.setHeader('Content-Length', String(Buffer.byteLength(payload)));
  res.end(payload);
}

/** The binding checks of §5.4, or null when the request matches the code. */
export function checkGrant(
  grant: CodeGrant,
  clientId: string,
  params: URLSearchParams,
  verifier: string,
): { error: TokenError; description: string } | null {
  if (!constantTimeEqual(grant.clientId, clientId)) {
    return { error: 'invalid_grant', description: 'the code was issued to another client' };
  }
  const redirectUri = params.get('redirect_uri');
  const redirectOk = grant.redirectUriProvided
    ? redirectUri === grant.redirectUri
    : redirectUri === null || redirectUri === grant.redirectUri;
  if (!redirectOk) return { error: 'invalid_grant', description: 'redirect_uri does not match the authorization request' };
  if (!verifyPkce(verifier, grant.codeChallenge)) {
    return { error: 'invalid_grant', description: 'code_verifier does not match the code_challenge' };
  }
  const resource = params.get('resource');
  if (resource !== null && deviceIdFromResource(resource, grant.issuer) !== grant.deviceId) {
    return { error: 'invalid_target', description: 'resource does not match the phone that approved this request' };
  }
  return null;
}

/**
 * client_id from the body, or from HTTP Basic credentials (some clients send public client ids
 * that way). null when absent; undefined when both are present and differ.
 */
function requestClientId(params: URLSearchParams, authorization: string | undefined): string | null | undefined {
  const fromBody = params.get('client_id');
  const basic = /^basic[ \t]+([A-Za-z0-9+/=]+)[ \t]*$/i.exec(authorization?.trim() ?? '')?.[1];
  if (basic === undefined) return fromBody;
  const decoded = Buffer.from(basic, 'base64').toString('utf8');
  const colon = decoded.indexOf(':');
  const fromBasic = decodeURIComponentSafe(colon < 0 ? decoded : decoded.slice(0, colon));
  if (fromBody !== null && fromBody !== fromBasic) return undefined;
  return fromBasic;
}

function decodeURIComponentSafe(value: string): string {
  try {
    return decodeURIComponent(value.replace(/\+/g, ' '));
  } catch {
    return value;
  }
}

function fail(res: ServerResponse, error: TokenError, description: string): void {
  sendJson(res, 400, { error, error_description: description });
}

/** A failed authentication-relevant check: counted toward the per-IP auth-failure limit. */
function rejectAuth(
  deps: HttpDeps,
  res: ServerResponse,
  client: ClientInfo,
  error: TokenError,
  description: string,
  deviceId?: string,
): void {
  deps.limiter.recordAuthFailure(client.ipKey);
  deps.logger.info('token request rejected', {
    error,
    ip: client.ip,
    device: deviceId === undefined ? undefined : deviceTag(deviceId),
  });
  fail(res, error, description);
}
