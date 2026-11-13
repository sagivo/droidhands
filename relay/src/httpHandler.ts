import { validateHeaderValue, type IncomingMessage, type ServerResponse } from 'node:http';
import { performance } from 'node:perf_hooks';
import { declaredLength, readBody } from './body.js';
import { isOriginAllowed, type RelayConfig } from './config.js';
import type { TunnelFailure } from './deviceConnection.js';
import type { DeviceRegistry } from './deviceRegistry.js';
import { deviceTag, errorMessage, type Logger } from './logger.js';
import { clientAddress, headerValue, rateLimitKey } from './net.js';
import {
  DEVICE_WS_PATH,
  FORWARDED_REQUEST_HEADERS,
  RpcCode,
  SAFE_RESPONSE_HEADERS,
  jsonRpcError,
  type DeviceResponse,
} from './protocol.js';
import type { RateLimiter } from './rateLimit.js';
import { bearerCredential, parseToken } from './token.js';

export interface HttpDeps {
  readonly config: RelayConfig;
  readonly logger: Logger;
  readonly registry: DeviceRegistry;
  readonly limiter: RateLimiter;
  readonly isShuttingDown: () => boolean;
}

/** Retry-After for "device offline" and "shutting down" (seconds). */
const OFFLINE_RETRY_AFTER_SEC = 30;

const UNAUTHORIZED_BODY = jsonRpcError(RpcCode.UNAUTHORIZED, 'Unauthorized');

/** How each tunnel failure is reported to the agent. */
const FAILURE_RESPONSES: Record<TunnelFailure, { status: number; message: string }> = {
  timeout: { status: 504, message: 'Device did not respond in time' },
  disconnected: { status: 502, message: 'Device disconnected before responding' },
  replaced: { status: 502, message: 'Device reconnected before responding; retry the request' },
  send_failed: { status: 502, message: 'Could not deliver the request to the device' },
  invalid_response: { status: 502, message: 'Device sent an invalid response' },
  shutdown: { status: 503, message: 'Relay is shutting down; retry shortly' },
};

/**
 * The relay's HTTP surface: `POST /mcp` (and optionally `POST /mcp/<token>`) tunneled to the
 * device named by the token, plus `/healthz` and `/`. See docs/PROTOCOL.md §2-4.
 */
export function createHttpHandler(deps: HttpDeps): (req: IncomingMessage, res: ServerResponse) => void {
  return (req, res) => {
    handle(deps, req, res).catch((err: unknown) => {
      deps.logger.error('request handler failed', { error: errorMessage(err) });
      if (!res.headersSent) sendText(res, 500, 'Internal Server Error\n');
      else res.destroy();
    });
  };
}

async function handle(deps: HttpDeps, req: IncomingMessage, res: ServerResponse): Promise<void> {
  res.setHeader('Cache-Control', 'no-store');
  res.setHeader('X-Content-Type-Options', 'nosniff');

  const pathname = requestPath(req.url);
  if (pathname === null) return sendText(res, 400, 'Bad Request\n');

  const isMcp = pathname === '/mcp' || pathname.startsWith('/mcp/');
  const origin = headerValue(req, 'origin');
  if (origin !== undefined && !isOriginAllowed(origin, deps.config.allowedOrigins)) {
    deps.logger.info('origin rejected', { ip: clientAddress(req, deps.config.trustProxy) });
    return isMcp ? sendRpcError(req, res, 403, 'Origin not allowed') : sendText(res, 403, 'Forbidden\n');
  }

  if (pathname === '/mcp' || pathname === '/mcp/') return handleMcp(deps, req, res, null);
  if (pathname.startsWith('/mcp/')) {
    const segment = pathname.slice('/mcp/'.length);
    if (!deps.config.allowTokenInUrl || segment.includes('/')) return sendText(res, 404, 'Not Found\n');
    return handleMcp(deps, req, res, segment);
  }

  switch (pathname) {
    case '/healthz':
      if (!allowMethods(req, res, 'GET, HEAD')) return;
      return sendBody(req, res, 200, 'application/json', '{"ok":true}');
    case '/':
      if (!allowMethods(req, res, 'GET, HEAD')) return;
      return sendBody(req, res, 200, 'text/plain; charset=utf-8', 'PhoneBridge relay. MCP endpoint: POST /mcp\n');
    case DEVICE_WS_PATH:
      res.setHeader('Upgrade', 'websocket');
      return sendText(res, 426, 'Upgrade Required\n');
    default:
      return sendText(res, 404, 'Not Found\n');
  }
}

/**
 * `POST /mcp`: validate the token's shape, then tunnel the request to its device and relay the
 * answer. [pathToken] is the token from `/mcp/<token>` when that route is enabled.
 */
async function handleMcp(
  deps: HttpDeps,
  req: IncomingMessage,
  res: ServerResponse,
  pathToken: string | null,
): Promise<void> {
  const { config, logger, registry, limiter } = deps;
  const started = performance.now();
  const ip = clientAddress(req, config.trustProxy);
  const ipKey = rateLimitKey(ip);
  let device: string | undefined;
  const done = (status: number): void => {
    const fields = { status, ms: Math.round(performance.now() - started), device, ip };
    if (status >= 500 && status !== 503) logger.warn('mcp request', fields);
    else logger.info('mcp request', fields);
  };

  if (deps.isShuttingDown()) {
    res.setHeader('Connection', 'close');
    res.setHeader('Retry-After', String(OFFLINE_RETRY_AFTER_SEC));
    sendRpcError(req, res, 503, FAILURE_RESPONSES.shutdown.message);
    return done(503);
  }

  const decision = limiter.hit(ipKey);
  if (!decision.allowed) {
    res.setHeader('Retry-After', String(decision.retryAfterSec));
    sendRpcError(req, res, 429, 'Too many requests');
    logger.warn('rate limited', { ip, reason: decision.reason });
    return done(429);
  }

  if (req.method !== 'POST') {
    res.setHeader('Allow', 'POST');
    sendRpcError(req, res, 405, 'Method not allowed; use POST');
    return done(405);
  }

  const credential = pathToken ?? bearerCredential(headerValue(req, 'authorization'));
  const token = credential === null ? null : parseToken(credential);
  if (token === null) {
    limiter.recordAuthFailure(ipKey);
    sendUnauthorized(req, res);
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
    if (status === 503) res.setHeader('Retry-After', String(OFFLINE_RETRY_AFTER_SEC));
    sendRpcError(req, res, status, message);
    return done(status);
  }
  if (outcome.response.status === 401) limiter.recordAuthFailure(ipKey);
  writeDeviceResponse(req, res, outcome.response, logger);
  return done(outcome.response.status);
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

function sendUnauthorized(req: IncomingMessage, res: ServerResponse): void {
  res.setHeader('WWW-Authenticate', 'Bearer realm="phonebridge"');
  sendBody(req, res, 401, 'application/json', UNAUTHORIZED_BODY);
}

function sendOffline(req: IncomingMessage, res: ServerResponse): void {
  res.setHeader('Retry-After', String(OFFLINE_RETRY_AFTER_SEC));
  sendRpcError(req, res, 503, 'Device offline');
}

function sendRpcError(req: IncomingMessage, res: ServerResponse, status: number, message: string): void {
  sendBody(req, res, status, 'application/json', jsonRpcError(RpcCode.SERVER_ERROR, message));
}

/**
 * Ends the response. If the request body has not been consumed and is large or of unknown size,
 * the connection is closed afterwards instead of draining the rest of the upload.
 */
function sendBody(req: IncomingMessage, res: ServerResponse, status: number, contentType: string, body: string): void {
  if (!req.complete) {
    const declared = declaredLength(req);
    const chunked = req.headers['transfer-encoding'] !== undefined;
    if (chunked || (declared !== null && declared > 64 * 1024)) res.setHeader('Connection', 'close');
  }
  res.statusCode = status;
  res.setHeader('Content-Type', contentType);
  res.setHeader('Content-Length', String(Buffer.byteLength(body)));
  res.end(req.method === 'HEAD' ? undefined : body);
}

function sendText(res: ServerResponse, status: number, text: string): void {
  sendBody(res.req, res, status, 'text/plain; charset=utf-8', text);
}

function allowMethods(req: IncomingMessage, res: ServerResponse, allow: string): boolean {
  if (allow.split(', ').includes(req.method ?? '')) return true;
  res.setHeader('Allow', allow);
  sendText(res, 405, 'Method Not Allowed\n');
  return false;
}

/** The request's path, or null when the target cannot be parsed. Query strings are ignored. */
export function requestPath(url: string | undefined): string | null {
  if (url === undefined || url === '') return null;
  try {
    return new URL(url, 'http://relay.invalid').pathname;
  } catch {
    return null;
  }
}
