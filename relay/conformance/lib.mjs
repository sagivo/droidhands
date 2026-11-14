// Helpers for the relay conformance suite: a scripted fake device speaking the tunnel protocol
// (docs/PROTOCOL.md §3, §5) over a real WebSocket, raw HTTP requests with full header control, and
// an OAuth client driver. Independent of the relay's own code; only `ws` is required.
import assert from 'node:assert/strict';
import { createHash, randomBytes } from 'node:crypto';
import { request } from 'node:http';
import { request as httpsRequest } from 'node:https';
import WebSocket from 'ws';

const BASE32 = 'abcdefghijklmnopqrstuvwxyz234567';
const WAIT_MS = Number(process.env.CONFORMANCE_WAIT_MS ?? 5_000);

/** The relay under test, its settings (as configured by the runner) and what the suite may assume. */
export const config = Object.freeze({
  base: (process.env.RELAY_URL ?? '').replace(/\/+$/, ''),
  accessKey: process.env.CONFORMANCE_ACCESS_KEY || null,
  requestTimeoutMs: optionalInt('CONFORMANCE_REQUEST_TIMEOUT_MS'),
  helloTimeoutMs: optionalInt('CONFORMANCE_HELLO_TIMEOUT_MS'),
  maxInflight: optionalInt('CONFORMANCE_MAX_INFLIGHT') ?? 32,
  maxBodyBytes: optionalInt('CONFORMANCE_MAX_BODY_BYTES') ?? 1_048_576,
  pairingTtlMs: optionalInt('CONFORMANCE_PAIRING_TTL_MS'),
  codeTtlMs: optionalInt('CONFORMANCE_CODE_TTL_MS'),
  allowedOrigin: process.env.CONFORMANCE_ALLOWED_ORIGIN || null,
  tokenInUrl: process.env.CONFORMANCE_TOKEN_IN_URL === 'true',
  apkUrl: process.env.CONFORMANCE_APK_URL || null,
});

if (config.base === '') throw new Error('RELAY_URL is not set (e.g. RELAY_URL=http://127.0.0.1:8787)');

function optionalInt(name) {
  const value = process.env[name];
  return value === undefined || value === '' ? null : Number(value);
}

export const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/** deviceId = base32(SHA-256(secret)[0..16]) (§1). */
export function identityFrom(secretBytes) {
  const digest = createHash('sha256').update(secretBytes).digest().subarray(0, 16);
  let bits = '';
  for (const byte of digest) bits += byte.toString(2).padStart(8, '0');
  let deviceId = '';
  for (let i = 0; i < bits.length; i += 5) deviceId += BASE32[parseInt(bits.slice(i, i + 5).padEnd(5, '0'), 2)];
  return { secret: secretBytes.toString('base64url'), deviceId };
}

export const newIdentity = () => identityFrom(randomBytes(32));

/** A well-formed agent token for [deviceId] (relays never check the secret part). */
export function tokenFor(deviceId) {
  const tokenId = newIdentity().deviceId.slice(0, 12);
  return `pb1.${deviceId}.${tokenId}.${randomBytes(32).toString('base64url')}`;
}

export const connectorUrl = (deviceId) => `${config.base}/d/${deviceId}/mcp`;

/** A device that speaks the tunnel protocol and lets the test decide how to answer. */
export class FakeDevice {
  constructor(identity, ws) {
    this.identity = identity;
    this.ws = ws;
    this.buffered = [];
    this.waiters = [];
    this.handler = null;
    this.closed = new Promise((resolve) => ws.on('close', (code, reason) => resolve({ code, reason: String(reason) })));
    ws.on('error', () => {});
    ws.on('message', (data, isBinary) => {
      if (isBinary) return;
      const frame = JSON.parse(String(data));
      if (frame.type === 'request' && this.handler !== null) return this.handler(frame);
      const index = this.waiters.findIndex((w) => w.type === frame.type);
      if (index >= 0) this.waiters.splice(index, 1)[0].resolve(frame);
      else this.buffered.push(frame);
    });
  }

  get deviceId() {
    return this.identity.deviceId;
  }

  /** The next frame of [type] (buffered or future); rejects after a few seconds. */
  nextFrame(type, waitMs = WAIT_MS) {
    const index = this.buffered.findIndex((f) => f.type === type);
    if (index >= 0) return Promise.resolve(this.buffered.splice(index, 1)[0]);
    return new Promise((resolve, reject) => {
      const waiter = { type, resolve: (frame) => (clearTimeout(timer), resolve(frame)) };
      const timer = setTimeout(() => {
        this.waiters.splice(this.waiters.indexOf(waiter), 1);
        reject(new Error(`timed out waiting for a "${type}" frame`));
      }, waitMs);
      this.waiters.push(waiter);
    });
  }

  /** True when no frame of [type] arrives within [ms]. */
  async noFrame(type, ms = 300) {
    try {
      await this.nextFrame(type, ms);
      return false;
    } catch {
      return true;
    }
  }

  nextRequest() {
    return this.nextFrame('request');
  }

  /** Answers every current and future request with [handler](request) → {status, body, headers}. */
  serve(handler) {
    this.handler = (frame) => {
      const { status, body, headers } = handler(frame);
      this.respond(frame.id, status, body, headers);
    };
    for (const frame of this.buffered.filter((f) => f.type === 'request')) this.handler(frame);
    this.buffered = this.buffered.filter((f) => f.type !== 'request');
  }

  send(frame) {
    this.ws.send(typeof frame === 'string' ? frame : JSON.stringify(frame));
  }

  respond(id, status, body, headers = { 'content-type': 'application/json' }) {
    this.send({ type: 'response', id, status, headers, body });
  }

  /** JSON ping round trip: afterwards every frame sent by the relay before it has arrived. */
  async sync() {
    const t = Date.now();
    this.send({ type: 'ping', t });
    const pong = await this.nextFrame('pong');
    assert.equal(pong.t, t);
  }

  close() {
    this.ws.close(1000, 'test done');
    return this.closed;
  }
}

/**
 * Opens a device socket (`/v1/device?deviceId=<id>`) and sends a hello; does not wait for the
 * answer. Options: identity, urlDeviceId (null: omit the parameter), hello (fields merged into the
 * default hello), rawHello (string sent instead; null sends nothing).
 */
export async function connectDevice(options = {}) {
  const identity = options.identity ?? newIdentity();
  const urlDeviceId = options.urlDeviceId === undefined ? identity.deviceId : options.urlDeviceId;
  const query = urlDeviceId === null ? '' : `?deviceId=${encodeURIComponent(urlDeviceId)}`;
  // Like the Android client (OkHttp's onClosing), act on the relay's close frame without waiting
  // long for the TCP connection to end: workerd keeps it open after closing a socket that never
  // sent a frame (the hello timeout), which would otherwise delay `close` by ws's default 30 s.
  const ws = new WebSocket(`${config.base.replace(/^http/, 'ws')}/v1/device${query}`, { perMessageDeflate: false, closeTimeout: 1_000 });
  const device = new FakeDevice(identity, ws);
  await new Promise((resolve, reject) => {
    ws.once('open', resolve);
    ws.once('error', reject);
    ws.once('unexpected-response', (_req, res) => reject(new Error(`upgrade refused: HTTP ${res.statusCode}`)));
  });
  if (options.rawHello !== null) {
    device.send(
      options.rawHello ??
        JSON.stringify({
          type: 'hello',
          protocolVersion: 1,
          deviceId: identity.deviceId,
          deviceSecret: identity.secret,
          platform: 'conformance',
          appVersion: '0.0.1',
          deviceName: 'Conformance device',
          ...(config.accessKey === null ? {} : { accessKey: config.accessKey }),
          ...options.hello,
        }),
    );
  }
  return device;
}

/** Connects a device and waits for its welcome. */
export async function connectReady(options = {}) {
  const device = await connectDevice(options);
  device.welcome = await device.nextFrame('welcome');
  return device;
}

/** Waits for the relay's error frame and close code of a refused handshake. */
export async function refusal(device) {
  const [error, closed] = await Promise.all([device.nextFrame('error'), withTimeout(device.closed, 'close')]);
  return { error, closed };
}

export function withTimeout(promise, what, ms = WAIT_MS) {
  let timer;
  return Promise.race([
    promise.finally(() => clearTimeout(timer)),
    new Promise((_, reject) => (timer = setTimeout(() => reject(new Error(`timed out waiting for ${what}`)), ms))),
  ]);
}

/** Plain HTTP(S) request with exactly the given headers. Resolves {status, headers, body}. */
export function http(url, { method = 'GET', headers = {}, body } = {}) {
  const target = new URL(url);
  const send = target.protocol === 'https:' ? httpsRequest : request;
  return new Promise((resolve, reject) => {
    const req = send(target, { method, headers, agent: false }, (res) => {
      const chunks = [];
      res.on('data', (chunk) => chunks.push(chunk));
      res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body: Buffer.concat(chunks).toString('utf8') }));
      res.on('error', reject);
    });
    req.on('error', reject);
    req.end(body);
  });
}

/** `POST` an MCP message the way an MCP client does. */
export function postMcp(url, { token, body, headers = {} } = {}) {
  return http(url, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      accept: 'application/json, text/event-stream',
      ...(token ? { authorization: `Bearer ${token}` } : {}),
      ...headers,
    },
    body: body ?? '{"jsonrpc":"2.0","id":1,"method":"tools/list"}',
  });
}

export const rpcMessage = (res) => JSON.parse(res.body).error?.message ?? '';

// ---- OAuth client driver ------------------------------------------------------------------------

export const REDIRECT_URI = 'http://localhost:33418/callback';

export function newPkce() {
  const verifier = randomBytes(32).toString('base64url');
  return { verifier, challenge: createHash('sha256').update(verifier).digest('base64url') };
}

export function postJson(url, value, headers = {}) {
  return http(url, { method: 'POST', headers: { 'content-type': 'application/json', ...headers }, body: JSON.stringify(value) });
}

export function postForm(url, params, headers = {}) {
  return http(url, {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded', ...headers },
    body: new URLSearchParams(params).toString(),
  });
}

export async function registerClient(metadata = { client_name: 'Conformance Agent', redirect_uris: [REDIRECT_URI] }) {
  const res = await postJson(`${config.base}/oauth/register`, metadata);
  assert.equal(res.status, 201, res.body);
  return JSON.parse(res.body).client_id;
}

/** `/oauth/authorize?…` as an MCP client builds it. */
export function authorizeUrl({ clientId, pkce, deviceId, redirectUri = REDIRECT_URI, state = 'state-123', extra = {} }) {
  const url = new URL(`${config.base}/oauth/authorize`);
  url.searchParams.set('response_type', 'code');
  url.searchParams.set('client_id', clientId);
  if (redirectUri !== null) url.searchParams.set('redirect_uri', redirectUri);
  url.searchParams.set('code_challenge', pkce.challenge);
  url.searchParams.set('code_challenge_method', 'S256');
  url.searchParams.set('state', state);
  if (deviceId !== null && deviceId !== undefined) url.searchParams.set('resource', connectorUrl(deviceId));
  for (const [name, value] of Object.entries(extra)) url.searchParams.set(name, value);
  return url.toString();
}

/** Opens the authorize URL; returns the waiting page's poll URL, page URL and display code. */
export async function openAuthorize(params) {
  const res = await http(authorizeUrl(params), { headers: { 'user-agent': 'ConformanceBrowser/1.0' } });
  assert.equal(res.status, 200, res.body.slice(0, 500));
  const attr = (name) => {
    const match = new RegExp(`${name}="([^"]*)"`).exec(res.body);
    assert.ok(match, `waiting page has ${name}`);
    return new URL(match[1].replace(/&amp;/g, '&'), config.base).toString();
  };
  const displayCode = /<div class="code" id="code">([A-Z0-9]{4})<\/div>/.exec(res.body)?.[1];
  assert.ok(displayCode, 'waiting page shows the display code');
  return { res, pollUrl: attr('data-poll'), pageUrl: attr('data-page'), displayCode };
}

export async function pollStatus(pollUrl) {
  const res = await http(pollUrl);
  assert.equal(res.status, 200, res.body);
  return JSON.parse(res.body);
}

export async function pollUntilDecided(pollUrl, waitMs = WAIT_MS) {
  const deadline = Date.now() + waitMs;
  for (;;) {
    const status = await pollStatus(pollUrl);
    if (status.status !== 'pending') return status;
    if (Date.now() > deadline) throw new Error('pairing still pending');
    await sleep(25);
  }
}

/** Answers the next pair_request with an approval and a fresh token. */
export async function approveNext(device) {
  const request = await device.nextFrame('pair_request');
  const accessToken = tokenFor(device.deviceId);
  const tokenId = accessToken.split('.')[2];
  device.send({ type: 'pair_response', id: request.id, approved: true, accessToken, tokenId });
  return { request, accessToken, tokenId };
}

export function redirectParams(redirect) {
  const url = new URL(redirect);
  assert.equal(`${url.origin}${url.pathname}`, REDIRECT_URI);
  return url.searchParams;
}

export function exchange(params) {
  return postForm(`${config.base}/oauth/token`, { grant_type: 'authorization_code', redirect_uri: REDIRECT_URI, ...params });
}

/** A complete flow up to an approved redirect. */
export async function approvedCode(device, clientId) {
  const id = clientId ?? (await registerClient());
  const pkce = newPkce();
  const page = await openAuthorize({ clientId: id, pkce, deviceId: device.deviceId });
  const approval = await approveNext(device);
  const status = await pollUntilDecided(page.pollUrl);
  assert.equal(status.status, 'approved');
  const code = redirectParams(status.redirect).get('code');
  return { clientId: id, pkce, code, approval, pairingId: approval.request.id, page };
}
