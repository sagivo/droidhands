import type { IncomingMessage, ServerResponse } from 'node:http';
import { readBody } from '../body.js';
import { constantTimeEqual } from '../encoding.js';
import { admit, clientInfo, type ClientInfo, type HttpDeps } from '../httpDeps.js';
import { applyOpenCors, sendJson } from '../httpUtil.js';
import { deviceTag } from '../logger.js';
import { headerValue } from '../net.js';
import { decodeClientId } from './clientRegistration.js';
import { OAUTH_SCOPE, deviceIdFromResource } from './metadata.js';
import type { CodeGrant } from './pairingStore.js';
import { verifyPkce } from './pkce.js';

/**
 * `POST /oauth/token` (docs/PROTOCOL.md §5.4): exchanges a one-time authorization code for the
 * token the phone issued. Errors follow RFC 6749 §5.2 with status 400.
 */

/** Token requests are a handful of short parameters. */
const MAX_TOKEN_BODY_BYTES = 16 * 1024;

/** Token request parameters that must not repeat (RFC 6749 §3.2). */
const SINGLE_PARAMS = ['grant_type', 'code', 'redirect_uri', 'client_id', 'code_verifier', 'resource'] as const;

type TokenError = 'invalid_request' | 'invalid_client' | 'invalid_grant' | 'unsupported_grant_type' | 'invalid_target';

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
  const contentType = headerValue(req, 'content-type')?.split(';')[0]?.trim().toLowerCase();
  if (contentType !== 'application/x-www-form-urlencoded') {
    return fail(res, 'invalid_request', 'the body must be application/x-www-form-urlencoded');
  }
  const body = await readBody(req, MAX_TOKEN_BODY_BYTES);
  if (body.kind === 'aborted') return;
  if (body.kind === 'too_large') {
    res.setHeader('Connection', 'close');
    return sendJson(res, 413, { error: 'invalid_request', error_description: 'request too large' });
  }

  const params = new URLSearchParams(body.body);
  const repeated = SINGLE_PARAMS.find((name) => params.getAll(name).length > 1);
  if (repeated !== undefined) return fail(res, 'invalid_request', `"${repeated}" must not repeat`);
  const grantType = params.get('grant_type');
  if (grantType === null) return fail(res, 'invalid_request', 'grant_type is required');
  if (grantType !== 'authorization_code') {
    return fail(res, 'unsupported_grant_type', 'only authorization_code is supported; to reconnect, authorize again');
  }
  const clientId = requestClientId(params, headerValue(req, 'authorization'));
  const code = params.get('code');
  const verifier = params.get('code_verifier');
  if (clientId === undefined) return fail(res, 'invalid_request', 'client_id in the body and in Basic auth differ');
  if (clientId === null || code === null || verifier === null) {
    return fail(res, 'invalid_request', 'client_id, code and code_verifier are required');
  }
  if (decodeClientId(clientId, deps.relaySecret) === null) {
    return rejectAuth(deps, res, client, 'invalid_client', 'unknown client_id; register again');
  }

  const grant = deps.pairings.takeCode(code);
  if (grant === null) {
    return rejectAuth(deps, res, client, 'invalid_grant', 'the authorization code is invalid, expired or already used');
  }
  // From here on the code is consumed: any mismatch abandons the pairing so the phone deletes the token.
  const problem = checkGrant(grant, clientId, params, verifier);
  if (problem !== null) {
    deps.pairings.finishExchange(grant, false);
    return rejectAuth(deps, res, client, problem.error, problem.description, grant.deviceId);
  }

  const payload = JSON.stringify({ access_token: grant.accessToken, token_type: 'Bearer', scope: OAUTH_SCOPE });
  grant.accessToken = '';
  // pair_complete only once the response has been handed to the network; abandoned otherwise.
  res.once('close', () => deps.pairings.finishExchange(grant, res.writableFinished));
  deps.logger.info('token issued', { device: deviceTag(grant.deviceId), ip: client.ip });
  res.statusCode = 200;
  res.setHeader('Content-Type', 'application/json');
  res.setHeader('Content-Length', String(Buffer.byteLength(payload)));
  res.end(payload);
}

/** The binding checks of §5.4, or null when the request matches the code. */
function checkGrant(
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
