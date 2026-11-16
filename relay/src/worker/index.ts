/**
 * DroidHands relay on Cloudflare Workers + Durable Objects: the same HTTP surface and device
 * protocol as the Node relay (relay/src/relay.ts; docs/PROTOCOL.md §2-5), verified by the shared
 * conformance suite (relay/conformance/).
 *
 * This Worker does everything that needs no per-device state (routing, Origin/CORS policy, rate
 * limits, OAuth discovery and registration, request validation, pages) and hands the rest to the
 * device's Durable Object ([DeviceObject]), which it finds from the device id in the path, the
 * token, the device URL's `deviceId` parameter, or the device-id prefix of pairing page tokens and
 * authorization codes.
 */
import { ConfigError, isOriginAllowed } from '../config.js';
import { DEVICE_ID_REGEX, constantTimeEqual } from '../encoding.js';
import { DEVICE_ID_PARAM } from '../handshake.js';
import { openCorsHeaders, originCorsHeaders } from '../httpUtil.js';
import { createLogger, deviceTag, errorMessage, type Logger } from '../logger.js';
import { FAILURE_RESPONSES, MCP_CORS, OFFLINE_RETRY_AFTER_SEC, PLAIN_CHALLENGE, UNAUTHORIZED_BODY, forwardedHeaders } from '../mcpHandler.js';
import { normalizeIp, rateLimitKey } from '../net.js';
import {
  PAIR_STATUS_PREFIX,
  PAIRING_GONE_ERROR,
  PAIRING_ID_REGEX,
  NO_RESOURCE_ERROR,
  RATE_LIMITED_PAGE,
  authorizationRequest,
  checkAuthorizeRequest,
  cleanUserAgent,
  createFailureError,
  foreignResourceError,
  pairStatusBody,
  waitingPageInput,
  type PairingPageError,
} from '../oauth/authorizeRoutes.js';
import { MAX_REGISTRATION_BODY_BYTES, registrationOutcome } from '../oauth/discoveryRoutes.js';
import {
  CONNECTOR_PATH_REGEX,
  authorizationServerMetadata,
  connectorChallenge,
  deviceIdFromMetadataPath,
  deviceIdFromResource,
  isAuthorizationServerMetadataPath,
  protectedResourceMetadata,
} from '../oauth/metadata.js';
import { MAX_TOKEN_BODY_BYTES, checkTokenRequest } from '../oauth/tokenRoute.js';
import { offlinePage, OFFLINE_PAGE_STATUS, pairingErrorPage, waitingPage } from '../pages/pairingPages.js';
import { setupPage } from '../pages/setupPage.js';
import { CloseCode, DEVICE_WS_PATH, PROTOCOL_VERSION, SAFE_RESPONSE_HEADERS, type ErrorFrame } from '../protocol.js';
import { RateLimiter, type RateDecision } from '../rateLimit.js';
import { setupBanner } from '../setup/banner.js';
import { qrPath, qrSvg } from '../setup/qr.js';
import { SETUP_PATH, setupUrl } from '../setup/setupUrl.js';
import { bearerCredential, parseToken } from '../token.js';
import { unsupportedSettings, workerConfig, type Env, type WorkerConfig } from './config.js';
import { INTERNAL, type AuthorizeResult, type DeviceObject, type ExchangeResult, type PollResult } from './deviceObject.js';
import { declaredLength, json, methodNotAllowed, page, readBody, respond, rpcError, text } from './http.js';

export { DeviceObject } from './deviceObject.js';
export type { Env } from './config.js';

const RATE_WINDOW_MS = 60_000;
const APK_PATH = '/download/droidhands.apk';
const SETUP_QR_PATH = '/setup/qr';
/** `{"key": "<access key>"}` is tiny (as in setup/setupRoutes.ts). */
const MAX_QR_BODY_BYTES = 1024;

/**
 * Per-isolate state. Cloudflare runs many isolates (per location and more under load), so the
 * per-IP limits here are best effort: they bound abuse per isolate, not globally. Per-device limits
 * (pairings, in-flight requests, pending handshakes) are exact: they live in the device's object.
 */
interface Isolate {
  readonly limiter: RateLimiter;
  readonly logger: Logger;
  lastSweep: number;
  bannerShown: boolean;
}

let isolate: Isolate | null = null;

function isolateState(cfg: WorkerConfig): Isolate {
  if (isolate === null) {
    isolate = {
      limiter: new RateLimiter({
        windowMs: RATE_WINDOW_MS,
        maxRequests: cfg.relay.rateLimitRequestsPerMin,
        maxAuthFailures: cfg.relay.rateLimitAuthFailuresPerMin,
        blockMs: RATE_WINDOW_MS,
        maxEntries: 50_000,
      }),
      logger: createLogger(cfg.relay.logLevel, (line) => console.log(line)),
      lastSweep: Date.now(),
      bannerShown: false,
    };
  }
  const now = Date.now();
  if (now - isolate.lastSweep >= RATE_WINDOW_MS) {
    isolate.limiter.sweep();
    isolate.lastSweep = now;
  }
  return isolate;
}

/** One request, with what every route needs. */
interface Ctx {
  readonly request: Request;
  readonly url: URL;
  readonly method: string;
  readonly env: Env;
  readonly cfg: WorkerConfig;
  readonly limiter: RateLimiter;
  readonly logger: Logger;
  /** `{relay}`: PUBLIC_URL, or the origin this request was sent to. */
  readonly base: string;
  readonly ip: string;
  readonly ipKey: string;
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    let cfg: WorkerConfig;
    try {
      cfg = workerConfig(env);
    } catch (err) {
      if (!(err instanceof ConfigError)) throw err;
      console.error(`droidhands-relay: invalid configuration: ${err.message}`);
      return text(500, `droidhands-relay: invalid configuration: ${err.message}\n`);
    }
    const state = isolateState(cfg);
    const url = new URL(request.url);
    const ip = normalizeIp(request.headers.get('cf-connecting-ip') ?? 'unknown');
    const ctx: Ctx = {
      request,
      url,
      method: request.method.toUpperCase(),
      env,
      cfg,
      limiter: state.limiter,
      logger: state.logger,
      base: cfg.relay.publicUrl ?? url.origin,
      ip,
      ipKey: rateLimitKey(ip),
    };
    if (!state.bannerShown) {
      state.bannerShown = true;
      const ignored = unsupportedSettings(env);
      if (ignored.length > 0) ctx.logger.warn('settings not supported by the Cloudflare relay are ignored', { names: ignored.join(',') });
      if (cfg.relay.setupBanner) {
        console.log(setupBanner(setupUrl(ctx.base, cfg.relay.accessKey), { guessed: cfg.relay.publicUrl === null }));
      }
    }
    try {
      return await route(ctx);
    } catch (err) {
      ctx.logger.error('request handler failed', { error: errorMessage(err) });
      return text(500, 'Internal Server Error\n');
    }
  },
} satisfies ExportedHandler<Env>;

// ---- routing (mirrors relay/src/httpHandler.ts) ------------------------------------------------

type OriginPolicy = 'checked' | 'open' | 'page';

async function route(ctx: Ctx): Promise<Response> {
  let pathname = ctx.url.pathname;
  const { prefix } = ctx.cfg;
  // Workers routes do not strip PUBLIC_URL's path prefix the way a reverse proxy would.
  if (prefix !== '' && (pathname === prefix || pathname.startsWith(`${prefix}/`))) pathname = pathname.slice(prefix.length) || '/';

  const { policy, rpc, handle } = match(ctx, pathname);
  const origin = ctx.request.headers.get('origin');
  if (policy === 'checked' && origin !== null && !isOriginAllowed(origin, ctx.cfg.relay.allowedOrigins)) {
    ctx.logger.info('origin rejected', { ip: ctx.ip });
    return rpc ? rpcError(403, 'Origin not allowed') : text(403, 'Forbidden\n');
  }
  return handle();
}

function match(ctx: Ctx, pathname: string): { policy: OriginPolicy; rpc: boolean; handle: () => Promise<Response> | Response } {
  const checked = (handle: () => Promise<Response> | Response, rpc = false) => ({ policy: 'checked' as const, rpc, handle });
  const open = (handle: () => Promise<Response> | Response) => ({ policy: 'open' as const, rpc: false, handle });
  const pageRoute = (handle: () => Promise<Response> | Response) => ({ policy: 'page' as const, rpc: false, handle });

  if (pathname === '/mcp' || pathname === '/mcp/') return checked(() => handleMcp(ctx, { kind: 'token', pathToken: null }), true);
  if (pathname.startsWith('/mcp/')) {
    const segment = pathname.slice('/mcp/'.length);
    if (!ctx.cfg.relay.allowTokenInUrl || segment.includes('/')) return checked(() => notFound(), true);
    return checked(() => handleMcp(ctx, { kind: 'token', pathToken: segment }), true);
  }
  const connector = CONNECTOR_PATH_REGEX.exec(pathname)?.[1];
  if (connector !== undefined) return checked(() => handleMcp(ctx, { kind: 'connector', deviceId: connector }), true);

  if (pathname.startsWith('/.well-known/')) {
    const deviceId = deviceIdFromMetadataPath(pathname, ctx.cfg.prefix);
    if (deviceId !== null) return open(() => discovery(ctx, () => protectedResourceMetadata(ctx.base, deviceId)));
    if (isAuthorizationServerMetadataPath(pathname, ctx.cfg.prefix)) {
      return open(() => discovery(ctx, () => authorizationServerMetadata(ctx.base)));
    }
    return open(() => notFound());
  }
  if (pathname.startsWith(PAIR_STATUS_PREFIX)) {
    const id = pathname.slice(PAIR_STATUS_PREFIX.length);
    return pageRoute(() => handlePairStatus(ctx, id));
  }
  switch (pathname) {
    case '/oauth/register':
      return open(() => handleRegister(ctx));
    case '/oauth/token':
      return open(() => handleToken(ctx));
    case '/oauth/authorize':
      return pageRoute(() => handleAuthorize(ctx));
    case SETUP_PATH:
      return pageRoute(() => handleSetupPage(ctx));
    case SETUP_QR_PATH:
      return pageRoute(() => handleSetupQr(ctx));
    case APK_PATH:
      return pageRoute(() => handleApk(ctx));
    case '/healthz':
      return checked(() => methodNotAllowed(ctx.method, 'GET, HEAD') ?? json(200, { ok: true }));
    case '/':
      return checked(() => methodNotAllowed(ctx.method, 'GET, HEAD') ?? bannerText(ctx));
    case DEVICE_WS_PATH:
      // The Node relay's upgrade handler never looks at Origin (the hello authenticates the socket);
      // only plain requests to this path go through the allowlist, as there.
      return isWebSocketUpgrade(ctx.request) ? open(() => handleDeviceSocket(ctx)) : checked(() => handleDeviceSocket(ctx));
    default:
      return checked(() => notFound());
  }
}

function notFound(): Response {
  return text(404, 'Not Found\n');
}

function bannerText(ctx: Ctx): Response {
  return respond(
    200,
    `DroidHands relay.\nSet up a phone: ${ctx.base}${SETUP_PATH}\nAI apps connect to the connection URL shown in the DroidHands app (${ctx.base}/d/<deviceId>/mcp).\n`,
    { 'Content-Type': 'text/plain; charset=utf-8' },
  );
}

function admit(ctx: Ctx): RateDecision {
  const decision = ctx.limiter.hit(ctx.ipKey);
  if (!decision.allowed) ctx.logger.warn('rate limited', { ip: ctx.ip, reason: decision.reason });
  return decision;
}

function stub(ctx: Ctx, deviceId: string): DurableObjectStub<DeviceObject> {
  return ctx.env.DEVICES.getByName(deviceId);
}

// ---- device WebSocket (§3.1) -------------------------------------------------------------------

function isWebSocketUpgrade(request: Request): boolean {
  return request.headers.get('upgrade')?.toLowerCase() === 'websocket';
}

async function handleDeviceSocket(ctx: Ctx): Promise<Response> {
  if (!isWebSocketUpgrade(ctx.request)) {
    return text(426, 'Upgrade Required\n', { Upgrade: 'websocket' });
  }
  // Upgrades share the per-IP request budget, which also bounds handshake spam.
  if (!ctx.limiter.hit(ctx.ipKey).allowed) return respond(429, null);
  const deviceId = ctx.url.searchParams.get(DEVICE_ID_PARAM);
  if (deviceId === null) {
    // The Durable Object must be chosen before the hello arrives: apps that predate the parameter
    // are told to update (4002 stops their retries).
    return refuseSocket(
      'protocol_version',
      CloseCode.PROTOCOL_VERSION,
      `This relay needs the ${DEVICE_ID_PARAM} parameter on the device URL (protocol version ${PROTOCOL_VERSION} with heartbeats). Update the DroidHands app.`,
    );
  }
  if (!DEVICE_ID_REGEX.test(deviceId)) {
    return refuseSocket('bad_request', CloseCode.BAD_REQUEST, `The ${DEVICE_ID_PARAM} parameter is not a device id.`);
  }
  const headers = new Headers(ctx.request.headers);
  headers.set(INTERNAL.deviceId, deviceId);
  headers.set(INTERNAL.ip, ctx.ip);
  headers.set(INTERNAL.ipKey, ctx.ipKey);
  headers.set(INTERNAL.base, ctx.base);
  return stub(ctx, deviceId).fetch(new Request('https://device.internal/v1/device', { headers }));
}

/** Accepts the socket only to say why it is refused (as the Node relay does for a bad hello). */
function refuseSocket(code: ErrorFrame['code'], closeCode: number, message: string): Response {
  const pair = new WebSocketPair();
  const server = pair[1];
  server.accept();
  const frame: ErrorFrame = { type: 'error', code, message };
  server.send(JSON.stringify(frame));
  server.close(closeCode, code);
  return new Response(null, { status: 101, webSocket: pair[0] });
}

// ---- MCP endpoints (§2-4, §5.1; mirrors relay/src/mcpHandler.ts) ------------------------------

type McpRoute = { readonly kind: 'token'; readonly pathToken: string | null } | { readonly kind: 'connector'; readonly deviceId: string };

async function handleMcp(ctx: Ctx, route: McpRoute): Promise<Response> {
  const started = Date.now();
  let device: string | undefined = route.kind === 'connector' ? deviceTag(route.deviceId) : undefined;
  const challenge = route.kind === 'connector' ? connectorChallenge(ctx.base, route.deviceId) : PLAIN_CHALLENGE;
  // The router has already refused any Origin that is not allowlisted.
  const origin = ctx.request.headers.get('origin');
  const cors: Record<string, string> = {};
  if (origin !== null) {
    const preflight = ctx.method === 'OPTIONS';
    const corsHeaders = originCorsHeaders(origin, MCP_CORS, preflight);
    if (preflight) return done(respond(204, null, corsHeaders));
    Object.assign(cors, corsHeaders);
  }
  function done(response: Response): Response {
    const status = response.status;
    const fields = { status, ms: Date.now() - started, device, ip: ctx.ip, route: route.kind };
    if (status >= 500 && status !== 503) ctx.logger.warn('mcp request', fields);
    else ctx.logger.info('mcp request', fields);
    return response;
  }
  const error = (status: number, message: string, headers: Record<string, string> = {}): Response =>
    done(rpcError(status, message, { ...cors, ...headers }));

  const decision = admit(ctx);
  if (!decision.allowed) return error(429, 'Too many requests', { 'Retry-After': String(decision.retryAfterSec) });
  if (ctx.method !== 'POST') return error(405, 'Method not allowed; use POST', { Allow: 'POST' });

  const credential =
    route.kind === 'token' && route.pathToken !== null
      ? route.pathToken
      : bearerCredential(ctx.request.headers.get('authorization') ?? undefined);
  const token = credential === null ? null : parseToken(credential);
  const wrongDevice = route.kind === 'connector' && token !== null && token.deviceId !== route.deviceId;
  if (token === null || wrongDevice) {
    // A connector request without any credential is the normal first step of OAuth discovery.
    if (route.kind === 'token' || credential !== null) ctx.limiter.recordAuthFailure(ctx.ipKey);
    return done(respond(401, UNAUTHORIZED_BODY, { ...cors, 'Content-Type': 'application/json', 'WWW-Authenticate': challenge }));
  }
  device = deviceTag(token.deviceId);

  const max = ctx.cfg.relay.maxBodyBytes;
  const declared = declaredLength(ctx.request);
  if (declared !== null && declared > max) return error(413, `Request body exceeds ${max} bytes`);
  const body = await readBody(ctx.request, max);
  if (body.kind === 'too_large') return error(413, `Request body exceeds ${max} bytes`);

  let result;
  try {
    result = await stub(ctx, token.deviceId).tunnel({
      headers: forwardedHeaders((name) => ctx.request.headers.get(name) ?? undefined, token.token),
      body: body.body,
      remoteAddress: ctx.ip,
    });
  } catch (err) {
    // The object was reset (deploy, crash, overload) while the request may have reached the phone.
    ctx.logger.warn('device object failed', { device, error: errorMessage(err) });
    const { status, message } = FAILURE_RESPONSES.disconnected;
    return error(status, message);
  }
  switch (result.kind) {
    case 'offline':
      return error(503, 'Device offline', { 'Retry-After': String(OFFLINE_RETRY_AFTER_SEC) });
    case 'busy':
      return error(429, 'Too many concurrent requests for this device', { 'Retry-After': '1' });
    case 'failed': {
      const { status, message } = FAILURE_RESPONSES[result.failure];
      return error(status, message);
    }
    case 'response':
      break;
  }
  const response = result.response;
  const headers: Record<string, string> = { ...cors };
  for (const name of SAFE_RESPONSE_HEADERS) {
    const value = response.headers[name];
    if (value !== undefined && isValidHeaderValue(value)) headers[name] = value;
  }
  if (response.status === 401) {
    ctx.limiter.recordAuthFailure(ctx.ipKey);
    // E.g. a revoked token: send the client back into the OAuth flow.
    if (route.kind === 'connector') headers['www-authenticate'] = challenge;
  }
  return done(respond(response.status, response.body, headers));
}

/** What node:http's validateHeaderValue accepts: no control characters except tab. */
function isValidHeaderValue(value: string): boolean {
  return !/[^\t\x20-\x7e\x80-\xff]/.test(value);
}

// ---- OAuth discovery and registration (§5.2; mirrors relay/src/oauth/discoveryRoutes.ts) -------

function openCors(ctx: Ctx, methods: string): { headers: Record<string, string>; preflight: Response | null } {
  const preflight = ctx.method === 'OPTIONS';
  const headers = openCorsHeaders(methods, preflight);
  return { headers, preflight: preflight ? respond(204, null, headers) : null };
}

async function discovery(ctx: Ctx, document: () => Record<string, unknown>): Promise<Response> {
  const cors = openCors(ctx, 'GET, HEAD');
  if (cors.preflight !== null) return cors.preflight;
  if (ctx.method !== 'GET' && ctx.method !== 'HEAD') {
    return json(405, { error: 'invalid_request', error_description: 'use GET' }, { ...cors.headers, Allow: 'GET, HEAD, OPTIONS' });
  }
  const decision = admit(ctx);
  if (!decision.allowed) return tooManyJson(decision, cors.headers);
  return json(200, document(), cors.headers);
}

function tooManyJson(decision: Extract<RateDecision, { allowed: false }>, headers: Record<string, string>): Response {
  return json(
    429,
    { error: 'invalid_request', error_description: 'too many requests; retry later' },
    { ...headers, 'Retry-After': String(decision.retryAfterSec) },
  );
}

async function handleRegister(ctx: Ctx): Promise<Response> {
  const cors = openCors(ctx, 'POST');
  if (cors.preflight !== null) return cors.preflight;
  if (ctx.method !== 'POST') {
    return json(405, { error: 'invalid_request', error_description: 'use POST' }, { ...cors.headers, Allow: 'POST, OPTIONS' });
  }
  const decision = admit(ctx);
  if (!decision.allowed) return tooManyJson(decision, cors.headers);
  const body = await readBody(ctx.request, MAX_REGISTRATION_BODY_BYTES);
  if (body.kind === 'too_large') {
    return json(413, { error: 'invalid_client_metadata', error_description: 'registration request too large' }, cors.headers);
  }
  const outcome = registrationOutcome(body.body, ctx.cfg.relaySecret, Date.now());
  if (outcome.log !== null) ctx.logger.info(outcome.log.msg, { ip: ctx.ip, ...outcome.log.fields });
  return json(outcome.status, outcome.body, cors.headers);
}

// ---- authorization and pairing status (§5.3; mirrors relay/src/oauth/authorizeRoutes.ts) -------

function errorPage(error: PairingPageError, headers: Record<string, string> = {}): Response {
  return page(error.status, pairingErrorPage(error.problem, error.hint), headers);
}

/**
 * The device's object was reset (a deploy, an overload) while answering. The Node relay has no
 * equivalent; answer like its other transient failures instead of a bare 500.
 */
const OBJECT_RESET_PAGE: PairingPageError = {
  status: 503,
  problem: 'The relay restarted while handling this request.',
  hint: 'Reload this page in a moment.',
};

function objectFailed(ctx: Ctx, deviceId: string, err: unknown): void {
  ctx.logger.warn('device object failed', { device: deviceTag(deviceId), error: errorMessage(err) });
}

async function handleAuthorize(ctx: Ctx): Promise<Response> {
  const notAllowed = methodNotAllowed(ctx.method, 'GET');
  if (notAllowed !== null) return notAllowed;
  if (!admit(ctx).allowed) return errorPage(RATE_LIMITED_PAGE);
  const checked = checkAuthorizeRequest(ctx.url.searchParams, ctx.cfg.relaySecret);
  if (!checked.ok) {
    if (checked.log !== undefined) ctx.logger.info(checked.log, { ip: ctx.ip });
    return errorPage(checked);
  }
  if (checked.resource === null) {
    // ALLOW_IMPLICIT_DEVICE ("the only online phone") cannot work without a device registry.
    ctx.logger.info('authorize: no resource', { ip: ctx.ip });
    return errorPage(NO_RESOURCE_ERROR);
  }
  const deviceId = deviceIdFromResource(checked.resource, ctx.base);
  if (deviceId === null) return errorPage(foreignResourceError(ctx.base));

  let result: AuthorizeResult;
  try {
    result = await stub(ctx, deviceId).authorize(authorizationRequest(checked, deviceId, ctx.base), {
      ipKey: ctx.ipKey,
      remoteAddress: ctx.ip,
      userAgent: cleanUserAgent(ctx.request.headers.get('user-agent') ?? undefined),
    });
  } catch (err) {
    objectFailed(ctx, deviceId, err);
    return errorPage(OBJECT_RESET_PAGE);
  }
  if (result.kind === 'offline') return page(OFFLINE_PAGE_STATUS, offlinePage());
  if (result.kind === 'failure') {
    const error = createFailureError(result.reason);
    return error === 'offline' ? page(OFFLINE_PAGE_STATUS, offlinePage()) : errorPage(error);
  }
  return page(200, waitingPage(waitingPageInput(ctx.cfg.prefix, result.pairing, result.deviceName, Date.now())));
}

async function handlePairStatus(ctx: Ctx, id: string): Promise<Response> {
  const notAllowed = methodNotAllowed(ctx.method, 'GET, HEAD');
  if (notAllowed !== null) return notAllowed;
  const wantsHtml = ctx.url.searchParams.get('view') === 'html';
  const decision = admit(ctx);
  if (!decision.allowed) {
    const retry = { 'Retry-After': String(decision.retryAfterSec) };
    return wantsHtml ? errorPage(RATE_LIMITED_PAGE, retry) : json(429, { error: 'rate_limited' }, retry);
  }
  const pageToken = ctx.url.searchParams.get('t') ?? '';
  // Page tokens issued by this relay start with the device id (see DeviceObject).
  const deviceId = pageToken.slice(0, 26);
  let found: PollResult | null = null;
  if (PAIRING_ID_REGEX.test(id) && DEVICE_ID_REGEX.test(deviceId)) {
    try {
      found = await stub(ctx, deviceId).poll(id, pageToken);
    } catch (err) {
      // The waiting page keeps polling after an error; its state is in storage.
      objectFailed(ctx, deviceId, err);
      return wantsHtml ? errorPage(OBJECT_RESET_PAGE) : json(503, { error: 'unavailable' }, { 'Retry-After': '1' });
    }
  }
  if (found === null) return wantsHtml ? errorPage(PAIRING_GONE_ERROR) : json(404, { error: 'not_found' });
  const { pairing, deviceName } = found;
  if (!wantsHtml) return json(200, pairStatusBody(pairing));
  if (pairing.redirect !== null) {
    return respond(303, null, { Location: pairing.redirect, 'Referrer-Policy': 'no-referrer' });
  }
  return page(200, waitingPage(waitingPageInput(ctx.cfg.prefix, pairing, deviceName, Date.now())));
}

// ---- token endpoint (§5.4; mirrors relay/src/oauth/tokenRoute.ts) -------------------------------

async function handleToken(ctx: Ctx): Promise<Response> {
  const cors = openCors(ctx, 'POST');
  if (cors.preflight !== null) return cors.preflight;
  const headers = { ...cors.headers, Pragma: 'no-cache' };
  const fail = (error: string, description: string): Response => json(400, { error, error_description: description }, headers);
  const rejectAuth = (error: string, description: string, deviceId?: string | null): Response => {
    ctx.limiter.recordAuthFailure(ctx.ipKey);
    ctx.logger.info('token request rejected', {
      error,
      ip: ctx.ip,
      device: deviceId === undefined || deviceId === null ? undefined : deviceTag(deviceId),
    });
    return fail(error, description);
  };
  if (ctx.method !== 'POST') {
    return json(405, { error: 'invalid_request', error_description: 'use POST' }, { ...headers, Allow: 'POST, OPTIONS' });
  }
  const decision = admit(ctx);
  if (!decision.allowed) return tooManyJson(decision, headers);
  const contentType = ctx.request.headers.get('content-type')?.split(';')[0]?.trim().toLowerCase();
  if (contentType !== 'application/x-www-form-urlencoded') {
    return fail('invalid_request', 'the body must be application/x-www-form-urlencoded');
  }
  const body = await readBody(ctx.request, MAX_TOKEN_BODY_BYTES);
  if (body.kind === 'too_large') return json(413, { error: 'invalid_request', error_description: 'request too large' }, headers);

  const params = new URLSearchParams(body.body);
  const checked = checkTokenRequest(params, ctx.request.headers.get('authorization') ?? undefined, ctx.cfg.relaySecret);
  if (!checked.ok) return checked.authFailure ? rejectAuth(checked.error, checked.description) : fail(checked.error, checked.description);

  // Codes issued by this relay start with the device id (see DeviceObject).
  const deviceId = checked.code.slice(0, 26);
  if (!DEVICE_ID_REGEX.test(deviceId)) {
    return rejectAuth('invalid_grant', 'the authorization code is invalid, expired or already used');
  }
  let result: ExchangeResult;
  try {
    result = await stub(ctx, deviceId).exchange({
      code: checked.code,
      clientId: checked.clientId,
      verifier: checked.verifier,
      form: body.body,
    });
  } catch (err) {
    // The code may be spent; the phone deletes a token nobody fetched after 15 minutes.
    objectFailed(ctx, deviceId, err);
    return json(503, { error: 'temporarily_unavailable', error_description: 'the relay restarted; authorize again' }, headers);
  }
  if (!result.ok) return rejectAuth(result.error, result.description, result.deviceId);
  ctx.logger.info('token issued', { device: deviceTag(result.deviceId), ip: ctx.ip });
  return respond(200, result.body, { ...headers, 'Content-Type': 'application/json' });
}

// ---- phone setup (§5.5; mirrors relay/src/setup/setupRoutes.ts) --------------------------------

function handleSetupPage(ctx: Ctx): Response {
  const notAllowed = methodNotAllowed(ctx.method, 'GET, HEAD');
  if (notAllowed !== null) return notAllowed;
  if (!admit(ctx).allowed) return text(429, 'Too Many Requests\n');
  const accessKeyRequired = ctx.cfg.relay.accessKey !== null;
  return page(
    200,
    setupPage({
      base: ctx.base,
      apkUrl: ctx.cfg.apkUrl,
      qrEndpoint: `${ctx.cfg.prefix}${SETUP_QR_PATH}`,
      accessKeyRequired,
      // With an access key the QR must include it, which only the page itself knows (see /setup/qr).
      qrSvg: accessKeyRequired ? null : qrSvg(setupUrl(ctx.base, null), 'QR code of this setup page'),
    }),
  );
}

/** There is no file system: `/download/droidhands.apk` forwards to APK_URL when it is set. */
function handleApk(ctx: Ctx): Response {
  const notAllowed = methodNotAllowed(ctx.method, 'GET, HEAD');
  if (notAllowed !== null) return notAllowed;
  if (!admit(ctx).allowed) return text(429, 'Too Many Requests\n');
  if (ctx.cfg.apkUrl === null) return notFound();
  return respond(302, null, { Location: ctx.cfg.apkUrl });
}

async function handleSetupQr(ctx: Ctx): Promise<Response> {
  const notAllowed = methodNotAllowed(ctx.method, 'POST');
  if (notAllowed !== null) return notAllowed;
  if (!admit(ctx).allowed) return json(429, { error: 'rate_limited' });
  const body = await readBody(ctx.request, MAX_QR_BODY_BYTES);
  if (body.kind === 'too_large') return json(413, { error: 'too_large' });
  let key: unknown;
  try {
    key = (JSON.parse(body.body) as { key?: unknown } | null)?.key;
  } catch {
    return json(400, { error: 'invalid_request' });
  }
  const expected = ctx.cfg.relay.accessKey;
  if (expected !== null && (typeof key !== 'string' || !constantTimeEqual(key, expected))) {
    ctx.limiter.recordAuthFailure(ctx.ipKey);
    return json(403, { error: 'access_key' });
  }
  return json(200, qrPath(setupUrl(ctx.base, expected)));
}
