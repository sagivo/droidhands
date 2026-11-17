import { randomBytes } from 'node:crypto';
import { DEFAULT_CLIENT_NAME, decodeClientId, sanitizeClientName } from './clientRegistration.js';
import { connectorUrl, deviceIdFromResource } from './metadata.js';
import { DEVICE_FLOW_REDIRECT_URI, type AuthorizationRequest, type CreateFailure, type PairingView, type RedeemResult } from './pairingStore.js';
import { PAIRING_ID_REGEX, createFailureError } from './authorizeRoutes.js';

/**
 * Agent sign-in (docs/PROTOCOL.md §5.6): the OAuth 2.0 Device Authorization Grant (RFC 8628) for
 * agents that can make HTTP requests but cannot run a browser sign-in, such as assistants you
 * chat or text with. The agent asks for a code, tells the user, and polls the token endpoint while
 * the phone shows its usual pairing prompt. It reuses the one-tap pairing (§5.3), so the phone
 * still mints the token and the same limits apply. Pure; shared by the Node and Worker relays.
 */

export const DEVICE_AUTHORIZATION_PATH = '/oauth/device_authorization';

/** RFC 8628 §3.4 grant type. */
export const DEVICE_CODE_GRANT = 'urn:ietf:params:oauth:grant-type:device_code';

/**
 * The client id recorded for agent sign-ins. It is not an HMAC-signed id, so the authorization
 * code grant (which checks client ids) never accepts the codes of these pairings.
 */
export const DEVICE_FLOW_CLIENT_ID = 'droidhands-agent-sign-in';

/** How often agents should poll (RFC 8628 §3.2 `interval`), in seconds. */
export const POLL_INTERVAL_SEC = 5;

/** Requests are a few short parameters. */
export const MAX_DEVICE_AUTHORIZATION_BODY_BYTES = 16 * 1024;

/** Parameters that must not repeat. */
const SINGLE_PARAMS = ['resource', 'client_id', 'client_name', 'scope'] as const;

/** Printable names agents may send as a bare client_id ("Instinct", "muse-agent"). */
const NAME_LIKE_CLIENT_ID = /^[\p{L}\p{N}][\p{L}\p{N} ._-]{0,59}$/u;

export interface DeviceFlowError {
  readonly status: number;
  readonly error: string;
  readonly description: string;
}

/**
 * The parameters of a POST body: `application/x-www-form-urlencoded` (the standard) or a JSON
 * object (lenient, for agents writing requests by hand; string, number and boolean values only).
 * Null for any other type or malformed JSON.
 */
export function bodyParams(contentType: string | undefined, body: string): URLSearchParams | null {
  const type = contentType?.split(';')[0]?.trim().toLowerCase();
  if (type === 'application/x-www-form-urlencoded') return new URLSearchParams(body);
  if (type !== 'application/json') return null;
  let value: unknown;
  try {
    value = JSON.parse(body);
  } catch {
    return null;
  }
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return null;
  const params = new URLSearchParams();
  for (const [name, v] of Object.entries(value as Record<string, unknown>)) {
    if (typeof v === 'string' || typeof v === 'number' || typeof v === 'boolean') params.append(name, String(v));
  }
  return params;
}

export type DeviceAuthorizationCheck =
  | { readonly ok: true; readonly deviceId: string; readonly clientName: string }
  | ({ readonly ok: false } & DeviceFlowError);

/**
 * Validates `POST /oauth/device_authorization` (§5.6 step 1). `resource` (the connection URL)
 * names the phone and is required. `client_id` is optional: a client registered here (§5.2) is
 * named by its registration, otherwise `client_name` or a name-like `client_id` is shown on the
 * phone, as unverified as any registered name.
 */
export function checkDeviceAuthorization(params: URLSearchParams, base: string, relaySecret: Buffer): DeviceAuthorizationCheck {
  const fail = (status: number, error: string, description: string): DeviceAuthorizationCheck => ({ ok: false, status, error, description });
  const repeated = SINGLE_PARAMS.find((name) => params.getAll(name).length > 1);
  if (repeated !== undefined) return fail(400, 'invalid_request', `"${repeated}" must not repeat`);
  const resource = params.get('resource');
  if (resource === null || resource === '') {
    return fail(400, 'invalid_request', `resource is required: the connection URL from the DroidHands app (${base}/d/<id>/mcp)`);
  }
  const deviceId = deviceIdFromResource(resource, base);
  if (deviceId === null) {
    return fail(400, 'invalid_target', `resource is not a connection URL of this relay; it looks like ${base}/d/<id>/mcp`);
  }
  return { ok: true, deviceId, clientName: agentName(params, relaySecret) };
}

/** The name the phone shows for an agent sign-in. */
function agentName(params: URLSearchParams, relaySecret: Buffer): string {
  const clientId = params.get('client_id');
  const registered = clientId === null ? null : decodeClientId(clientId, relaySecret);
  if (registered !== null) return registered.clientName;
  const name = params.get('client_name');
  if (name !== null && sanitizeClientName(name) !== DEFAULT_CLIENT_NAME) return sanitizeClientName(name);
  if (clientId !== null && NAME_LIKE_CLIENT_ID.test(clientId)) return sanitizeClientName(clientId);
  return 'AI agent';
}

/** The pairing store's request for an agent sign-in to [deviceId], issued by `{relay}` = [base]. */
export function deviceAuthorizationRequest(deviceId: string, clientName: string, base: string): AuthorizationRequest {
  return {
    deviceId,
    clientId: DEVICE_FLOW_CLIENT_ID,
    clientName,
    redirectUri: DEVICE_FLOW_REDIRECT_URI,
    redirectUriProvided: false,
    // Never checked (the device code is the proof); random so no two sign-ins count as the same flow.
    codeChallenge: randomBytes(32).toString('base64url'),
    state: undefined,
    issuer: base,
    flow: 'device',
  };
}

/** `device_code` = pairing id + "." + page token: what the waiting page of a browser would hold. */
export function deviceCode(pairing: PairingView): string {
  return `${pairing.id}.${pairing.pageToken}`;
}

/** The pairing id and page token in a device code, or null when it cannot be one. */
export function parseDeviceCode(code: string): { id: string; pageToken: string } | null {
  const dot = code.indexOf('.');
  if (dot < 0 || code.length > 256) return null;
  const id = code.slice(0, dot);
  const pageToken = code.slice(dot + 1);
  if (!PAIRING_ID_REGEX.test(id) || !/^[A-Za-z0-9_-]{16,200}$/.test(pageToken)) return null;
  return { id, pageToken };
}

/**
 * RFC 8628 §3.2 response, plus `message` and `token_endpoint` for agents that read it rather than
 * implement the RFC. `verification_uri` is the connection URL, whose page tells a person to answer
 * on the phone; there is nothing to type anywhere.
 */
export function deviceAuthorizationResponse(pairing: PairingView, base: string, now: number): Record<string, unknown> {
  const url = connectorUrl(base, pairing.deviceId);
  return {
    device_code: deviceCode(pairing),
    user_code: pairing.displayCode,
    verification_uri: url,
    expires_in: Math.max(0, Math.round((pairing.expiresAt - now) / 1000)),
    interval: POLL_INTERVAL_SEC,
    token_endpoint: `${base}/oauth/token`,
    message:
      `Tell the user: "Open DroidHands on your phone and tap Allow if it shows the code ${pairing.displayCode}." ` +
      `Then POST grant_type=${DEVICE_CODE_GRANT} and device_code to ${base}/oauth/token every ${POLL_INTERVAL_SEC} seconds ` +
      'until it returns access_token (authorization_pending means: keep waiting).',
  };
}

export type DeviceTokenCheck =
  | { readonly ok: true; readonly id: string; readonly pageToken: string }
  | ({ readonly ok: false; /** Counts toward the client's authentication-failure limit. */ readonly authFailure: boolean } & DeviceFlowError);

/** The device-code part of `POST /oauth/token` (§5.6 step 3), before the pairing is looked up. */
export function checkDeviceTokenRequest(params: URLSearchParams): DeviceTokenCheck {
  if (params.getAll('device_code').length > 1) {
    return { ok: false, authFailure: false, status: 400, error: 'invalid_request', description: '"device_code" must not repeat' };
  }
  const code = params.get('device_code');
  if (code === null) return { ok: false, authFailure: false, status: 400, error: 'invalid_request', description: 'device_code is required' };
  const parsed = parseDeviceCode(code);
  if (parsed === null) return { ok: false, authFailure: true, ...INVALID_DEVICE_CODE };
  return { ok: true, ...parsed };
}

export const INVALID_DEVICE_CODE: DeviceFlowError = {
  status: 400,
  error: 'invalid_grant',
  description: 'the device_code is invalid or was already used; start again at /oauth/device_authorization',
};

/** The RFC 8628 §3.5 error for a sign-in that did not (yet) produce a token. */
export function deviceTokenError(result: Exclude<RedeemResult, { status: 'approved' }>): DeviceFlowError {
  switch (result.status) {
    case 'pending':
      return {
        status: 400,
        error: 'authorization_pending',
        description: `Waiting for the user to tap Allow in DroidHands on their phone (code ${result.pairing.displayCode}). Poll again in ${POLL_INTERVAL_SEC} seconds.`,
      };
    case 'denied':
      return {
        status: 400,
        error: 'access_denied',
        description: 'The request was denied on the phone (or nobody answered in time). Start again only if the user asks.',
      };
    case 'expired':
      return {
        status: 400,
        error: 'expired_token',
        description: 'The request expired before the token was fetched. Start again if the user still wants to connect.',
      };
    case 'invalid':
      return INVALID_DEVICE_CODE;
  }
}

export const BODY_ERROR: DeviceFlowError = {
  status: 400,
  error: 'invalid_request',
  description: 'send the parameters form-encoded (application/x-www-form-urlencoded) or as a JSON object',
};

export const DEVICE_OFFLINE_ERROR: DeviceFlowError = {
  status: 503,
  error: 'temporarily_unavailable',
  description: 'The phone is offline. Ask the person to open DroidHands and check that it says Connected, then try again.',
};

/** A pairing the store refused to create, as an RFC 6749-style error. */
export function deviceCreateFailure(reason: CreateFailure): DeviceFlowError {
  const page = createFailureError(reason);
  if (page === 'offline') return DEVICE_OFFLINE_ERROR;
  return {
    status: page.status,
    error: page.status === 429 ? 'slow_down' : 'temporarily_unavailable',
    description: `${page.problem} ${page.hint}`.replace(' then reload this page', ' then try again'),
  };
}
