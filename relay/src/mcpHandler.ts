import { validateHeaderValue, type IncomingMessage, type ServerResponse } from 'node:http';
import { performance } from 'node:perf_hooks';
import { declaredLength, readBody } from './body.js';
import type { TunnelFailure } from './deviceConnection.js';
import { admit, clientInfo, relayBase, type HttpDeps } from './httpDeps.js';
import { applyOriginCors, sendBody, type OriginCors } from './httpUtil.js';
import { deviceTag, type Logger } from './logger.js';
import { headerValue } from './net.js';
import { connectorChallenge } from './oauth/metadata.js';
import { FORWARDED_REQUEST_HEADERS, RpcCode, SAFE_RESPONSE_HEADERS, jsonRpcError, type DeviceResponse } from './protocol.js';
import { bearerCredential, parseToken } from './token.js';

/**
 * The tunneled MCP endpoints (docs/PROTOCOL.md §2-4, §5.1):
 * * `POST /mcp` (and `POST /mcp/<token>` when enabled): the device comes from the token.
 * * `POST /d/<deviceId>/mcp`, the device connector URL: the device comes from the path, the token
 *   must belong to it, and every 401 points MCP clients at the OAuth metadata.
 */
export type McpRoute =
  | { readonly kind: 'token'; readonly pathToken: string | null }
  | { readonly kind: 'connector'; readonly deviceId: string };

/** Retry-After for "device offline" and "shutting down" (seconds). */
export const OFFLINE_RETRY_AFTER_SEC = 30;

const UNAUTHORIZED_BODY = jsonRpcError(RpcCode.UNAUTHORIZED, 'Unauthorized');
const PLAIN_CHALLENGE = 'Bearer realm="phonebridge"';

/** For requests refused before anything was sent to the phone. */
const SHUTTING_DOWN_MESSAGE = 'Relay is shutting down; retry shortly';

/**
 * Appended when the request reached (or may have reached) the phone. The phone keeps running a
 * call whose connection dropped, and tools/call is not idempotent (a message could go out twice),
 * so the agent must check instead of resending blindly.
 */
const MAY_HAVE_RUN =
  'The phone may already have performed the action: check whether it happened ' +
  '(for example with sms_list or screen_read) before sending the request again.';

/**
 * How each tunnel failure of a forwarded request is reported to the agent. None carries
 * Retry-After or invites a retry: the phone may have performed the action.
 */
const FAILURE_RESPONSES: Record<TunnelFailure, { status: number; message: string }> = {
  timeout: { status: 504, message: `The phone did not answer in time. ${MAY_HAVE_RUN}` },
  disconnected: { status: 502, message: `The phone's connection dropped before it answered. ${MAY_HAVE_RUN}` },
  replaced: { status: 502, message: `The phone reconnected before it answered. ${MAY_HAVE_RUN}` },
  send_failed: { status: 502, message: `The connection to the phone failed while sending the request. ${MAY_HAVE_RUN}` },
  invalid_response: { status: 502, message: `The phone sent an invalid response. ${MAY_HAVE_RUN}` },
  shutdown: { status: 503, message: `The relay shut down before the phone answered. ${MAY_HAVE_RUN}` },
};

/** CORS for MCP requests from an allowlisted Origin (ALLOWED_ORIGINS), e.g. browser-based clients. */
const MCP_CORS: OriginCors = {
  methods: 'POST',
  allowHeaders: 'Authorization, Content-Type, Accept, Mcp-Protocol-Version, Mcp-Session-Id',
  exposeHeaders: 'WWW-Authenticate, Mcp-Session-Id, Retry-After',
};

/** Validates the token, then tunnels the request to its device and relays the answer. */
export async function handleMcp(deps: HttpDeps, req: IncomingMessage, res: ServerResponse, route: McpRoute): Promise<void> {
  const { config, logger, registry, limiter } = deps;
  const started = performance.now();
  const client = clientInfo(deps, req);
  const { ip, ipKey } = client;
  let device: string | undefined = route.kind === 'connector' ? deviceTag(route.deviceId) : undefined;
  const done = (status: number): void => {
    const fields = { status, ms: Math.round(performance.now() - started), device, ip, route: route.kind };
    if (status >= 500 && status !== 503) logger.warn('mcp request', fields);
    else logger.info('mcp request', fields);
  };
  /** §5.1: on the connector path every 401 names the resource metadata. */
  const challenge = route.kind === 'connector' ? connectorChallenge(relayBase(deps, req), route.deviceId) : PLAIN_CHALLENGE;

  // The router has already refused any Origin that is not allowlisted.
  const origin = headerValue(req, 'origin');
  if (origin !== undefined && applyOriginCors(req, res, origin, MCP_CORS)) return done(204);

  if (deps.isShuttingDown()) {
    res.setHeader('Connection', 'close');
    res.setHeader('Retry-After', String(OFFLINE_RETRY_AFTER_SEC));
    sendRpcError(req, res, 503, SHUTTING_DOWN_MESSAGE);
    return done(503);
  }

  const decision = admit(deps, client);
  if (!decision.allowed) {
    res.setHeader('Retry-After', String(decision.retryAfterSec));
    sendRpcError(req, res, 429, 'Too many requests');
    return done(429);
  }

  if (req.method !== 'POST') {
    res.setHeader('Allow', 'POST');
    sendRpcError(req, res, 405, 'Method not allowed; use POST');
    return done(405);
  }

  const credential = route.kind === 'token' && route.pathToken !== null
    ? route.pathToken
    : bearerCredential(headerValue(req, 'authorization'));
  const token = credential === null ? null : parseToken(credential);
  const wrongDevice = route.kind === 'connector' && token !== null && token.deviceId !== route.deviceId;
  if (token === null || wrongDevice) {
    // A connector request without any credential is the normal first step of OAuth discovery,
    // not an attack; everything else counts toward the auth-failure limit.
    if (route.kind === 'token' || credential !== null) limiter.recordAuthFailure(ipKey);
    sendUnauthorized(req, res, challenge);
    return done(401);
  }
  device = deviceTag(token.deviceId);

  const declared = declaredLength(req);
  if (declared !== null && declared > config.maxBodyBytes) {
    sendTooLarge(req, res, config.maxBodyBytes);
    return done(413);
  }
  if (registry.get(token.deviceId)?.isOpen !== true) {
    sendOffline(req, res);
    return done(503);
  }

  const body = await readBody(req, config.maxBodyBytes);
  if (body.kind === 'aborted') return done(499);
  if (body.kind === 'too_large') {
    sendTooLarge(req, res, config.maxBodyBytes);
    return done(413);
  }

  // Re-resolve: the device may have disconnected or reconnected while the body was uploading.
  const connection = registry.get(token.deviceId);
  if (connection === undefined || !connection.isOpen) {
    sendOffline(req, res);
    return done(503);
  }
  if (connection.inflight >= config.maxInflightPerDevice) {
    res.setHeader('Retry-After', '1');
    sendRpcError(req, res, 429, 'Too many concurrent requests for this device');
    return done(429);
  }

  const outcome = await connection.forward({
    method: 'POST',
    path: '/mcp',
    headers: forwardedHeaders(req, token.token),
    body: body.body,
    remoteAddress: ip,
  });

  if (res.destroyed || res.writableEnded) return done(499);
  if (deps.isShuttingDown()) res.setHeader('Connection', 'close');
  if (!outcome.ok) {
    const { status, message } = FAILURE_RESPONSES[outcome.failure];
    sendRpcError(req, res, status, message);
    return done(status);
  }
  let response = outcome.response;
  if (response.status === 401) {
    limiter.recordAuthFailure(ipKey);
    // E.g. a revoked token: send the client back into the OAuth flow.
    if (route.kind === 'connector') {
      response = { ...response, headers: { ...response.headers, 'www-authenticate': challenge } };
    }
  }
  writeDeviceResponse(req, res, response, logger);
  return done(response.status);
}

/** The allowlisted request headers (§3.2), with the token normalized to `Bearer <token>`. */
function forwardedHeaders(req: IncomingMessage, token: string): Record<string, string> {
  const headers: Record<string, string> = {};
  for (const name of FORWARDED_REQUEST_HEADERS) {
    if (name === 'authorization') {
      headers.authorization = `Bearer ${token}`;
      continue;
    }
    const value = headerValue(req, name);
    if (value !== undefined) headers[name] = value;
  }
  return headers;
}

/** Copies status, the safe headers and the body of the device's response (§3.3). */
function writeDeviceResponse(req: IncomingMessage, res: ServerResponse, response: DeviceResponse, logger: Logger): void {
  res.statusCode = response.status;
  for (const name of SAFE_RESPONSE_HEADERS) {
    const value = response.headers[name];
    if (value === undefined) continue;
    try {
      validateHeaderValue(name, value);
      res.setHeader(name, value);
    } catch {
      logger.debug('dropped invalid response header from device', { header: name });
    }
  }
  const bodyless = response.status === 204 || response.status === 304;
  if (response.body === null || bodyless) {
    if (!bodyless) res.setHeader('Content-Length', '0');
    res.end();
    return;
  }
  res.setHeader('Content-Length', String(Buffer.byteLength(response.body)));
  res.end(req.method === 'HEAD' ? undefined : response.body);
}

/** 413, closing the connection so the remainder of the upload is never read. */
function sendTooLarge(req: IncomingMessage, res: ServerResponse, limit: number): void {
  res.setHeader('Connection', 'close');
  sendRpcError(req, res, 413, `Request body exceeds ${limit} bytes`);
}

function sendUnauthorized(req: IncomingMessage, res: ServerResponse, challenge: string): void {
  res.setHeader('WWW-Authenticate', challenge);
  sendBody(req, res, 401, 'application/json', UNAUTHORIZED_BODY);
}

function sendOffline(req: IncomingMessage, res: ServerResponse): void {
  res.setHeader('Retry-After', String(OFFLINE_RETRY_AFTER_SEC));
  sendRpcError(req, res, 503, 'Device offline');
}

/** A relay-generated JSON-RPC error (`id: null`). */
export function sendRpcError(req: IncomingMessage, res: ServerResponse, status: number, message: string): void {
  sendBody(req, res, status, 'application/json', jsonRpcError(RpcCode.SERVER_ERROR, message));
}
