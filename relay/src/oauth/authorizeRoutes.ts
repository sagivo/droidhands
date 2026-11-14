import type { IncomingMessage, ServerResponse } from 'node:http';
import { admit, clientInfo, publicPathPrefix, relayBase, type ClientInfo, type HttpDeps } from '../httpDeps.js';
import { allowMethods, sendJson } from '../httpUtil.js';
import { headerValue } from '../net.js';
import { sendOfflinePage, sendPairingError, sendWaitingPage, type WaitingPageInput } from '../pages/pairingPages.js';
import { decodeClientId, type RegisteredClient } from './clientRegistration.js';
import { deviceIdFromResource } from './metadata.js';
import type { AuthorizationRequest, CreateFailure, PairingView } from './pairingStore.js';
import { CODE_CHALLENGE_REGEX } from './pkce.js';
import { matchRedirectUri } from './redirectUris.js';

/**
 * `GET /oauth/authorize` and `GET /oauth/pair/<id>` (docs/PROTOCOL.md §5.3): the browser half of
 * one-tap pairing. Every problem with the request (unknown client, unregistered redirect URI,
 * unsupported response type, no PKCE, no device) is shown as an HTML page and never redirected:
 * anyone can register any https redirect URI, so a forged request must not bounce the user to an
 * arbitrary site. The browser only leaves for the redirect URI once the phone has decided.
 */

/** Path of the pairing status endpoint; the pairing id follows. */
export const PAIR_STATUS_PREFIX = '/oauth/pair/';

/** Pairing ids as issued (`p_` + 16 bytes base64url). */
export const PAIRING_ID_REGEX = /^p_[A-Za-z0-9_-]{22}$/;

/** Longest accepted `state` (opaque to us; MCP clients use ~43 chars). */
const MAX_STATE_LENGTH = 1024;

/** Longest user agent forwarded to the phone. */
const MAX_USER_AGENT_LENGTH = 200;

/** Authorization request parameters that must not repeat (RFC 6749 §3.1). */
const SINGLE_PARAMS = [
  'response_type',
  'client_id',
  'redirect_uri',
  'code_challenge',
  'code_challenge_method',
  'state',
  'resource',
  'scope',
] as const;

const RECONNECT_HINT = "Remove PhoneBridge from your AI app's connectors, then add your connection URL again.";

/** An error page: status, what went wrong and what to do instead. */
export interface PairingPageError {
  readonly status: number;
  readonly problem: string;
  readonly hint: string;
}

/** Shown when the per-IP request budget is exhausted. */
export const RATE_LIMITED_PAGE: PairingPageError = {
  status: 429,
  problem: 'Too many requests from your network.',
  hint: 'Wait a minute, then reload this page.',
};

/** A syntactically valid authorization request from a registered client (§5.3 step 1). */
export interface CheckedAuthorizeRequest {
  readonly clientId: string;
  readonly client: RegisteredClient;
  readonly redirectUri: string;
  readonly redirectUriProvided: boolean;
  readonly codeChallenge: string;
  readonly state: string | undefined;
  /** The raw `resource` parameter, or null. */
  readonly resource: string | null;
}

export type AuthorizeCheck =
  | ({ readonly ok: true } & CheckedAuthorizeRequest)
  | ({ readonly ok: false; /** Log line for the relay operator, if worth one. */ readonly log?: string } & PairingPageError);

/**
 * §5.3 step 1, shared by the Node and Worker relays: repeated parameters, the client (HMAC),
 * the redirect URI, `state`, `response_type` and PKCE, in this order. Pure.
 */
export function checkAuthorizeRequest(q: URLSearchParams, relaySecret: Buffer): AuthorizeCheck {
  const fail = (problem: string, hint: string, log?: string): AuthorizeCheck => ({
    ok: false,
    status: 400,
    problem,
    hint,
    ...(log === undefined ? {} : { log }),
  });
  const repeated = SINGLE_PARAMS.find((name) => q.getAll(name).length > 1);
  if (repeated !== undefined) return fail(`The request repeats the "${repeated}" parameter.`, RECONNECT_HINT);

  const clientId = q.get('client_id') ?? '';
  const registered = decodeClientId(clientId, relaySecret);
  if (registered === null) {
    return fail(
      "Your AI app's registration is not valid for this relay (it may have been made before the relay's secret changed).",
      RECONNECT_HINT,
      'authorize: unknown client_id',
    );
  }
  const redirectUri = chooseRedirectUri(registered, q.get('redirect_uri'));
  if (redirectUri === null) {
    return fail('Your AI app asked to return to an address it did not register.', RECONNECT_HINT, 'authorize: redirect_uri mismatch');
  }
  const state = q.get('state') ?? undefined;
  if (state !== undefined && state.length > MAX_STATE_LENGTH) {
    return fail('The request\'s "state" parameter is too long.', RECONNECT_HINT);
  }
  if (q.get('response_type') !== 'code') {
    return fail(
      'Your AI app asked for an unsupported kind of authorization (only response_type=code is supported).',
      'Update the app, or use an MCP client that supports the MCP authorization spec.',
    );
  }
  const codeChallenge = q.get('code_challenge');
  if (q.get('code_challenge_method') !== 'S256' || codeChallenge === null || !CODE_CHALLENGE_REGEX.test(codeChallenge)) {
    return fail(
      'Your AI app did not use PKCE with S256, which PhoneBridge requires to keep your phone safe.',
      'Update the app, or use an MCP client that supports the MCP authorization spec.',
    );
  }
  return {
    ok: true,
    clientId,
    client: registered,
    redirectUri,
    redirectUriProvided: q.get('redirect_uri') !== null,
    codeChallenge,
    state,
    resource: q.get('resource'),
  };
}

/** §5.3 step 2: `resource` names something that is not a connection URL of this relay at [base]. */
export function foreignResourceError(base: string): PairingPageError {
  return {
    status: 400,
    problem: `The connection URL your AI app uses does not belong to this relay (${base}).`,
    hint: `Copy your connection URL again from the PhoneBridge app; it looks like ${base}/d/…/mcp.`,
  };
}

/** §5.3 step 2: no `resource` (and no ALLOW_IMPLICIT_DEVICE). */
export const NO_RESOURCE_ERROR: PairingPageError = {
  status: 400,
  problem: "Your AI app didn't say which phone to connect to.",
  hint:
    'Add the connection URL shown in the PhoneBridge app (it ends with /d/…/mcp), not the relay address. ' +
    'If you did, update your AI app: PhoneBridge needs a client that supports the MCP authorization spec ' +
    '(2025-06-18 or later), which sends the connection URL as the "resource".',
};

export function handleAuthorize(deps: HttpDeps, req: IncomingMessage, res: ServerResponse, url: URL): void {
  if (!allowMethods(req, res, 'GET')) return;
  const client = clientInfo(deps, req);
  if (!admit(deps, client).allowed) {
    return sendPairingError(req, res, RATE_LIMITED_PAGE.status, RATE_LIMITED_PAGE.problem, RATE_LIMITED_PAGE.hint);
  }
  const checked = checkAuthorizeRequest(url.searchParams, deps.relaySecret);
  if (!checked.ok) {
    if (checked.log !== undefined) deps.logger.info(checked.log, { ip: client.ip });
    return sendPairingError(req, res, checked.status, checked.problem, checked.hint);
  }

  const base = relayBase(deps, req);
  const { resource } = checked;
  const deviceId = selectDevice(deps, req, res, resource, base);
  if (deviceId === null) return;
  const connection = deps.registry.get(deviceId);
  if (connection === undefined || !connection.isOpen) return sendOfflinePage(req, res);

  const result = deps.pairings.create(
    authorizationRequest(checked, deviceId, base),
    { ipKey: client.ipKey, remoteAddress: client.ip, userAgent: cleanUserAgent(headerValue(req, 'user-agent')) },
  );
  if (!result.ok) return sendCreateFailure(req, res, result.reason);
  sendWaiting(deps, req, res, result.pairing);
}

/** The pairing store's view of a checked request for [deviceId], issued by `{relay}` = [base]. */
export function authorizationRequest(checked: CheckedAuthorizeRequest, deviceId: string, base: string): AuthorizationRequest {
  return {
    deviceId,
    clientId: checked.clientId,
    clientName: checked.client.clientName,
    redirectUri: checked.redirectUri,
    redirectUriProvided: checked.redirectUriProvided,
    codeChallenge: checked.codeChallenge,
    state: checked.state,
    issuer: base,
    implicitDevice: checked.resource === null,
  };
}

/**
 * `GET /oauth/pair/<id>?t=<pageToken>`: `{"status", "redirect"?}` for the waiting page's polling.
 * With `&view=html` it is the reloadable waiting page itself, which forwards (303) to the redirect
 * once the pairing is decided; the noscript fallback uses it.
 */
export function handlePairStatus(deps: HttpDeps, req: IncomingMessage, res: ServerResponse, id: string, url: URL): void {
  if (!allowMethods(req, res, 'GET, HEAD')) return;
  const client: ClientInfo = clientInfo(deps, req);
  const decision = admit(deps, client);
  const wantsHtml = url.searchParams.get('view') === 'html';
  if (!decision.allowed) {
    res.setHeader('Retry-After', String(decision.retryAfterSec));
    return wantsHtml
      ? sendPairingError(req, res, RATE_LIMITED_PAGE.status, RATE_LIMITED_PAGE.problem, RATE_LIMITED_PAGE.hint)
      : sendJson(res, 429, { error: 'rate_limited' });
  }
  const pairing = PAIRING_ID_REGEX.test(id) ? deps.pairings.poll(id, url.searchParams.get('t') ?? '') : null;
  if (pairing === null) {
    return wantsHtml
      ? sendPairingError(req, res, PAIRING_GONE_ERROR.status, PAIRING_GONE_ERROR.problem, PAIRING_GONE_ERROR.hint)
      : sendJson(res, 404, { error: 'not_found' });
  }
  if (!wantsHtml) return sendJson(res, 200, pairStatusBody(pairing));
  if (pairing.redirect !== null) {
    res.statusCode = 303;
    res.setHeader('Location', pairing.redirect);
    res.setHeader('Referrer-Policy', 'no-referrer');
    res.setHeader('Content-Length', '0');
    res.end();
    return;
  }
  sendWaiting(deps, req, res, pairing);
}

/**
 * Renders the waiting page. Its own URLs are root-relative (with PUBLIC_URL's path prefix), so a
 * scheme or host the relay cannot see behind a proxy never breaks polling.
 */
function sendWaiting(deps: HttpDeps, req: IncomingMessage, res: ServerResponse, pairing: PairingView): void {
  const deviceName = deps.registry.get(pairing.deviceId)?.info.deviceName ?? null;
  sendWaitingPage(req, res, waitingPageInput(publicPathPrefix(deps.config), pairing, deviceName, Date.now()));
}

/** The waiting page's data; its URLs live under PUBLIC_URL's path [prefix]. */
export function waitingPageInput(prefix: string, pairing: PairingView, deviceName: string | null, now: number): WaitingPageInput {
  const pollUrl = `${prefix}${PAIR_STATUS_PREFIX}${pairing.id}?t=${pairing.pageToken}`;
  return {
    pairing,
    // Only name the phone to someone who named it (by its connection URL).
    deviceName: pairing.implicitDevice ? null : deviceName,
    pollUrl,
    pageUrl: `${pollUrl}&view=html`,
    now,
  };
}

/** Body of the JSON pairing status (§5.3 step 6). */
export function pairStatusBody(pairing: PairingView): Record<string, unknown> {
  return {
    status: pairing.status,
    ...(pairing.redirect === null ? {} : { redirect: pairing.redirect }),
    expiresAt: pairing.expiresAt,
  };
}

/** Unknown pairing, or wrong page token. */
export const PAIRING_GONE_ERROR: PairingPageError = {
  status: 404,
  problem: 'This connection request is no longer valid.',
  hint: 'Start again from your AI app.',
};

/**
 * The redirect URI to use: the requested one if registered (exact match, except that loopback URIs
 * may use any port, RFC 8252 §7.3), or the only registered one when the request omits it
 * (RFC 6749 §3.1.2.3). Null otherwise.
 */
function chooseRedirectUri(client: RegisteredClient, requested: string | null): string | null {
  if (requested === null) return client.redirectUris.length === 1 ? (client.redirectUris[0] as string) : null;
  return matchRedirectUri(client.redirectUris, requested) ? requested : null;
}

/**
 * The device named by `resource` (§5.3 step 2). Without `resource` the request is refused, unless
 * ALLOW_IMPLICIT_DEVICE restores the legacy fallback to the only online device. Renders the error
 * page and returns null when there is no single answer.
 */
function selectDevice(
  deps: HttpDeps,
  req: IncomingMessage,
  res: ServerResponse,
  resource: string | null,
  base: string,
): string | null {
  if (resource !== null) {
    const deviceId = deviceIdFromResource(resource, base);
    if (deviceId === null) {
      const error = foreignResourceError(base);
      sendPairingError(req, res, error.status, error.problem, error.hint);
    }
    return deviceId;
  }
  if (!deps.config.allowImplicitDevice) {
    // Otherwise anyone who knows the relay's address could prompt its only phone.
    deps.logger.info('authorize: no resource', { ip: clientInfo(deps, req).ip });
    sendPairingError(req, res, NO_RESOURCE_ERROR.status, NO_RESOURCE_ERROR.problem, NO_RESOURCE_ERROR.hint);
    return null;
  }
  const online = [...deps.registry.values()].filter((c) => c.isOpen);
  const only = online.length === 1 ? online[0] : undefined;
  if (only !== undefined) return only.deviceId;
  if (online.length === 0) sendOfflinePage(req, res);
  else {
    sendPairingError(
      req,
      res,
      400,
      "Several phones use this relay, and your AI app didn't say which one to connect to.",
      'Add the connection URL shown in the PhoneBridge app (it ends with /d/…/mcp) instead of the relay address.',
    );
  }
  return null;
}

function sendCreateFailure(req: IncomingMessage, res: ServerResponse, reason: CreateFailure): void {
  const error = createFailureError(reason);
  if (error === 'offline') return sendOfflinePage(req, res);
  sendPairingError(req, res, error.status, error.problem, error.hint);
}

/** The page for a pairing the store refused to create; `'offline'` means the offline page. */
export function createFailureError(reason: CreateFailure): PairingPageError | 'offline' {
  switch (reason) {
    case 'offline':
      return 'offline';
    case 'device_busy':
      return {
        status: 429,
        problem: 'Your phone already has several connection requests waiting.',
        hint: 'Answer them on your phone, or wait 3 minutes for them to expire, then reload this page.',
      };
    case 'device_hourly':
      return {
        status: 429,
        problem: 'Your phone received too many connection requests in the last hour.',
        hint: "Wait a while, then try again. If you didn't start them, someone else knows your connection URL; deny their requests on your phone.",
      };
    case 'ip_busy':
    case 'ip_hourly':
      return { status: 429, problem: 'Too many connection requests from your network.', hint: 'Wait a while, then try again.' };
    case 'capacity':
      return { status: 503, problem: 'The relay is busy right now.', hint: 'Try again in a few minutes.' };
  }
}

/** The browser's user agent for the phone prompt: printable, bounded. */
export function cleanUserAgent(value: string | undefined): string | undefined {
  if (value === undefined) return undefined;
  const clean = value.replace(/[^\x20-\x7e]/g, '').trim().slice(0, MAX_USER_AGENT_LENGTH);
  return clean === '' ? undefined : clean;
}
