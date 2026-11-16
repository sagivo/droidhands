// Black-box conformance suite for DroidHands relays (docs/PROTOCOL.md §2-5). Runs against any
// relay base URL with a scripted fake device:
//
//   RELAY_URL=http://127.0.0.1:8787 node --test conformance/suite.test.mjs
//
// The runners (run-node.mjs, run-worker.mjs) start a relay with short timeouts and tell the suite
// about them through CONFORMANCE_* variables (see lib.mjs); tests that need a setting the relay
// was not started with are skipped.
import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  REDIRECT_URI,
  approveNext,
  approvedCode,
  config,
  connectDevice,
  connectReady,
  connectorUrl,
  exchange,
  http,
  newIdentity,
  newPkce,
  openAuthorize,
  pollStatus,
  pollUntilDecided,
  postForm,
  postJson,
  postMcp,
  redirectParams,
  refusal,
  registerClient,
  authorizeUrl,
  rpcMessage,
  sleep,
  tokenFor,
  withTimeout,
} from './lib.mjs';

const { base } = config;
const UNAUTHORIZED_BODY = '{"jsonrpc":"2.0","id":null,"error":{"code":-32001,"message":"Unauthorized"}}';
const MAY_HAVE_RUN = /may already have performed the action/;

/** A connected device that is closed when the test ends. */
async function device(t, options) {
  const d = await connectReady(options);
  t.after(() => d.ws.terminate());
  return d;
}

const resourceMetadataUrl = (deviceId) => `${base}/.well-known/oauth-protected-resource/d/${deviceId}/mcp`;

// ---- §3.1 handshake ---------------------------------------------------------------------------

test('handshake: a valid hello is welcomed with the relay URLs', async (t) => {
  const d = await device(t);
  assert.equal(d.welcome.deviceId, d.deviceId);
  assert.equal(d.welcome.mcpUrl, `${base}/mcp`);
  assert.equal(d.welcome.connectUrl, connectorUrl(d.deviceId));
  assert.equal(typeof d.welcome.serverTime, 'number');
  assert.ok(Math.abs(d.welcome.serverTime - Date.now()) < 60_000);
});

test('handshake: a secret that does not match the deviceId → unauthorized, 4001', async () => {
  const claimed = newIdentity();
  const other = newIdentity();
  const d = await connectDevice({ identity: claimed, hello: { deviceSecret: other.secret } });
  const { error, closed } = await refusal(d);
  assert.equal(error.code, 'unauthorized');
  assert.equal(closed.code, 4001);
});

test('handshake: a hello for another device than the URL names → bad_request, 4000', async () => {
  const urlIdentity = newIdentity();
  const d = await connectDevice({ identity: newIdentity(), urlDeviceId: urlIdentity.deviceId });
  const { error, closed } = await refusal(d);
  assert.equal(error.code, 'bad_request');
  assert.equal(closed.code, 4000);
});

test('handshake: missing or wrong access key → access_key, 4001', { skip: config.accessKey === null && 'relay has no access key' }, async () => {
  for (const hello of [{ accessKey: undefined }, { accessKey: `${config.accessKey}x` }]) {
    const d = await connectDevice({ hello });
    const { error, closed } = await refusal(d);
    assert.equal(error.code, 'access_key');
    assert.equal(closed.code, 4001);
  }
});

test('handshake: unsupported protocolVersion → protocol_version, 4002', async () => {
  const d = await connectDevice({ hello: { protocolVersion: 2 } });
  const { error, closed } = await refusal(d);
  assert.equal(error.code, 'protocol_version');
  assert.equal(closed.code, 4002);
});

test('handshake: a first frame that is not a hello → bad_request, 4000', async () => {
  for (const rawHello of ['not json', '{"type":"ping","t":1}', '{"type":"hello","protocolVersion":1}']) {
    const d = await connectDevice({ rawHello });
    const { error, closed } = await refusal(d);
    assert.equal(error.code, 'bad_request', rawHello);
    assert.equal(closed.code, 4000, rawHello);
  }
});

test('handshake: no hello in time → 4008', { skip: !(config.helloTimeoutMs <= 5_000) && 'hello timeout not short' }, async () => {
  const d = await connectDevice({ rawHello: null });
  const closed = await withTimeout(d.closed, 'close', config.helloTimeoutMs + 5_000);
  assert.equal(closed.code, 4008);
});

test('handshake: a newer connection replaces the old one (4009); its pending request fails with 502', async (t) => {
  const identity = newIdentity();
  const first = await device(t, { identity });
  const pending = postMcp(connectorUrl(identity.deviceId), { token: tokenFor(identity.deviceId) });
  await first.nextRequest();
  const second = await device(t, { identity });
  const closed = await withTimeout(first.closed, 'replaced close');
  assert.equal(closed.code, 4009);
  const res = await pending;
  assert.equal(res.status, 502);
  assert.match(rpcMessage(res), MAY_HAVE_RUN);
  // The new connection serves requests.
  second.serve(() => ({ status: 200, body: '{"jsonrpc":"2.0","id":1,"result":{}}' }));
  assert.equal((await postMcp(connectorUrl(identity.deviceId), { token: tokenFor(identity.deviceId) })).status, 200);
});

test('handshake: the device socket ignores Origin (the hello authenticates it)', async (t) => {
  const d = await device(t, { headers: { origin: 'https://evil.example' } });
  assert.equal(d.welcome.deviceId, d.deviceId);
});

test('handshake: a disconnect with a request in flight fails it with 502', async (t) => {
  const d = await device(t);
  const pending = postMcp(connectorUrl(d.deviceId), { token: tokenFor(d.deviceId) });
  await d.nextRequest();
  d.ws.terminate();
  const res = await pending;
  assert.equal(res.status, 502);
  assert.match(rpcMessage(res), MAY_HAVE_RUN);
});

// ---- §3.2-3.5 tunnel --------------------------------------------------------------------------

test('tunnel: MCP round trip forwards the allowlisted headers and copies the safe ones back', async (t) => {
  const d = await device(t);
  const token = tokenFor(d.deviceId);
  const body = '{"jsonrpc":"2.0","id":7,"method":"tools/list"}';
  const pending = postMcp(connectorUrl(d.deviceId), {
    token,
    body,
    headers: { 'mcp-protocol-version': '2025-06-18', 'user-agent': 'conformance/1.0', cookie: 'secret=1', 'x-custom': 'no' },
  });
  const request = await d.nextRequest();
  assert.equal(request.method, 'POST');
  assert.equal(request.path, '/mcp');
  assert.match(request.id, /^\S+$/);
  assert.equal(request.body, body);
  assert.equal(typeof request.remoteAddress, 'string');
  assert.deepEqual(Object.keys(request.headers).sort(), ['accept', 'authorization', 'content-type', 'mcp-protocol-version', 'user-agent']);
  assert.equal(request.headers.authorization, `Bearer ${token}`);
  assert.equal(request.headers['mcp-protocol-version'], '2025-06-18');
  d.respond(request.id, 200, '{"jsonrpc":"2.0","id":7,"result":{"tools":[]}}', {
    'content-type': 'application/json',
    'mcp-session-id': 'sess-1',
    'x-internal': 'dropped',
    'set-cookie': 'a=b',
  });
  const res = await pending;
  assert.equal(res.status, 200);
  assert.equal(res.body, '{"jsonrpc":"2.0","id":7,"result":{"tools":[]}}');
  assert.equal(res.headers['content-type'], 'application/json');
  assert.equal(res.headers['mcp-session-id'], 'sess-1');
  assert.equal(res.headers['x-internal'], undefined);
  assert.equal(res.headers['set-cookie'], undefined);
  assert.equal(res.headers['cache-control'], 'no-store');
  assert.equal(res.headers['x-content-type-options'], 'nosniff');
});

test('tunnel: POST /mcp routes by the token; responses may arrive out of order', async (t) => {
  const d = await device(t);
  const token = tokenFor(d.deviceId);
  const a = postMcp(`${base}/mcp`, { token, body: '{"jsonrpc":"2.0","id":"a","method":"ping"}' });
  const b = postMcp(`${base}/mcp`, { token, body: '{"jsonrpc":"2.0","id":"b","method":"ping"}' });
  const first = await d.nextRequest();
  const second = await d.nextRequest();
  d.respond(second.id, 200, second.body);
  d.respond(first.id, 200, first.body);
  const results = await Promise.all([a, b]);
  assert.deepEqual(results.map((r) => JSON.parse(r.body).id).sort(), ['a', 'b']);
});

test('tunnel: POST /mcp/<token> when ALLOW_TOKEN_IN_URL is on', { skip: !config.tokenInUrl && 'token in URL off' }, async (t) => {
  const d = await device(t);
  const token = tokenFor(d.deviceId);
  const pending = postMcp(`${base}/mcp/${token}`);
  const request = await d.nextRequest();
  assert.equal(request.headers.authorization, `Bearer ${token}`);
  d.respond(request.id, 202, null, {});
  const res = await pending;
  assert.equal(res.status, 202);
  assert.equal(res.body, '');
});

test('tunnel: /mcp/<token> is 404 when ALLOW_TOKEN_IN_URL is off', { skip: config.tokenInUrl && 'token in URL on' }, async () => {
  const res = await postMcp(`${base}/mcp/${tokenFor(newIdentity().deviceId)}`);
  assert.equal(res.status, 404);
});

test('tunnel: 202 with a null body is relayed as an empty 202', async (t) => {
  const d = await device(t);
  d.serve(() => ({ status: 202, body: null, headers: {} }));
  const res = await postMcp(connectorUrl(d.deviceId), { token: tokenFor(d.deviceId), body: '{"jsonrpc":"2.0","method":"notifications/initialized"}' });
  assert.equal(res.status, 202);
  assert.equal(res.body, '');
});

test('tunnel: a malformed response frame fails the request with 502', async (t) => {
  const d = await device(t);
  const pending = postMcp(connectorUrl(d.deviceId), { token: tokenFor(d.deviceId) });
  const request = await d.nextRequest();
  d.send({ type: 'response', id: request.id, status: 99, headers: {}, body: '' });
  const res = await pending;
  assert.equal(res.status, 502);
  assert.match(rpcMessage(res), MAY_HAVE_RUN);
});

test('tunnel: a frame over 16 MiB closes the connection (1009) and fails its request with 502', async (t) => {
  const d = await device(t);
  const pending = postMcp(connectorUrl(d.deviceId), { token: tokenFor(d.deviceId) });
  const request = await d.nextRequest();
  d.respond(request.id, 200, 'x'.repeat(16 * 1024 * 1024));
  const closed = await withTimeout(d.closed, 'close after an oversized frame', 15_000);
  assert.equal(closed.code, 1009);
  const res = await pending;
  assert.equal(res.status, 502);
  assert.match(rpcMessage(res), MAY_HAVE_RUN);
});

test('tunnel: offline device → 503 Device offline, Retry-After 30', async () => {
  const id = newIdentity().deviceId;
  for (const url of [connectorUrl(id), `${base}/mcp`]) {
    const res = await postMcp(url, { token: tokenFor(id) });
    assert.equal(res.status, 503);
    assert.deepEqual(JSON.parse(res.body), { jsonrpc: '2.0', id: null, error: { code: -32000, message: 'Device offline' } });
    assert.equal(res.headers['retry-after'], '30');
  }
});

test('tunnel: no answer within REQUEST_TIMEOUT_MS → 504', { skip: !(config.requestTimeoutMs <= 10_000) && 'request timeout not short' }, async (t) => {
  const d = await device(t);
  const started = Date.now();
  const res = await postMcp(connectorUrl(d.deviceId), { token: tokenFor(d.deviceId) });
  assert.equal(res.status, 504);
  assert.match(rpcMessage(res), /did not answer in time/);
  assert.match(rpcMessage(res), MAY_HAVE_RUN);
  assert.ok(Date.now() - started >= config.requestTimeoutMs - 50);
  assert.equal(res.headers['retry-after'], undefined);
});

test('tunnel: requests beyond the in-flight cap → 429, Retry-After 1', { skip: config.maxInflight > 64 && 'cap too high' }, async (t) => {
  const d = await device(t);
  const token = tokenFor(d.deviceId);
  const held = [];
  for (let i = 0; i < config.maxInflight; i++) held.push(postMcp(connectorUrl(d.deviceId), { token }));
  const requests = [];
  for (let i = 0; i < config.maxInflight; i++) requests.push(await d.nextRequest());
  const over = await postMcp(connectorUrl(d.deviceId), { token });
  assert.equal(over.status, 429);
  assert.equal(over.headers['retry-after'], '1');
  for (const r of requests) d.respond(r.id, 200, '{}');
  assert.deepEqual((await Promise.all(held)).map((r) => r.status), Array(config.maxInflight).fill(200));
});

test('tunnel: GET → 405 Allow: POST; a body over MAX_BODY_BYTES → 413', async (t) => {
  const d = await device(t);
  const token = tokenFor(d.deviceId);
  const get = await http(connectorUrl(d.deviceId), { headers: { authorization: `Bearer ${token}` } });
  assert.equal(get.status, 405);
  assert.equal(get.headers.allow, 'POST');
  const big = await postMcp(connectorUrl(d.deviceId), { token, body: 'x'.repeat(config.maxBodyBytes + 1) });
  assert.equal(big.status, 413);
  assert.equal(await d.noFrame('request'), true);
});

test('keepalive: hb → hb_ack, ping → pong with the same t', async (t) => {
  const d = await device(t);
  d.send('{"type":"hb"}');
  assert.deepEqual(await d.nextFrame('hb_ack'), { type: 'hb_ack' });
  d.send({ type: 'ping', t: 1731500000123 });
  assert.deepEqual(await d.nextFrame('pong'), { type: 'pong', t: 1731500000123 });
  // Unknown frames are ignored and the connection keeps working.
  d.send({ type: 'future_frame', x: 1 });
  await d.sync();
});

// ---- §5.1 connector URL and 401s --------------------------------------------------------------

test('connector: 401 without a token or with another device token names the resource metadata', async (t) => {
  const d = await device(t);
  const challenge = `Bearer realm="droidhands", resource_metadata="${resourceMetadataUrl(d.deviceId)}"`;
  for (const token of [undefined, tokenFor(newIdentity().deviceId), 'pb1.bad']) {
    const res = await postMcp(connectorUrl(d.deviceId), { token });
    assert.equal(res.status, 401);
    assert.equal(res.headers['www-authenticate'], challenge);
    assert.equal(res.body, UNAUTHORIZED_BODY);
  }
  assert.equal(await d.noFrame('request'), true);
  const plain = await postMcp(`${base}/mcp`);
  assert.equal(plain.status, 401);
  assert.equal(plain.headers['www-authenticate'], 'Bearer realm="droidhands"');
});

test('connector: a 401 from the device gets the resource_metadata challenge', async (t) => {
  const d = await device(t);
  d.serve(() => ({ status: 401, body: UNAUTHORIZED_BODY, headers: { 'content-type': 'application/json', 'www-authenticate': 'Bearer realm="droidhands"' } }));
  const res = await postMcp(connectorUrl(d.deviceId), { token: tokenFor(d.deviceId) });
  assert.equal(res.status, 401);
  assert.equal(res.headers['www-authenticate'], `Bearer realm="droidhands", resource_metadata="${resourceMetadataUrl(d.deviceId)}"`);
});

// ---- CORS and Origin --------------------------------------------------------------------------

test('cors: MCP endpoints refuse other origins and answer allowlisted ones', { skip: config.allowedOrigin === null && 'no allowed origin' }, async (t) => {
  const d = await device(t);
  const url = connectorUrl(d.deviceId);
  const denied = await postMcp(url, { token: tokenFor(d.deviceId), headers: { origin: 'https://evil.example' } });
  assert.equal(denied.status, 403);
  assert.equal(JSON.parse(denied.body).error.code, -32000);

  const preflight = await http(url, {
    method: 'OPTIONS',
    headers: { origin: config.allowedOrigin, 'access-control-request-method': 'POST' },
  });
  assert.equal(preflight.status, 204);
  assert.equal(preflight.headers['access-control-allow-origin'], config.allowedOrigin);
  assert.equal(preflight.headers['access-control-allow-methods'], 'POST, OPTIONS');
  assert.match(preflight.headers['access-control-allow-headers'], /Authorization/);
  assert.equal(preflight.headers['access-control-allow-credentials'], undefined);

  d.serve(() => ({ status: 200, body: '{}' }));
  const ok = await postMcp(url, { token: tokenFor(d.deviceId), headers: { origin: config.allowedOrigin } });
  assert.equal(ok.status, 200);
  assert.equal(ok.headers['access-control-allow-origin'], config.allowedOrigin);
  assert.match(ok.headers['access-control-expose-headers'], /WWW-Authenticate/);
});

test('cors: OAuth discovery, registration and token endpoints allow any origin', async () => {
  for (const path of ['/.well-known/oauth-authorization-server', '/oauth/register', '/oauth/token']) {
    const res = await http(`${base}${path}`, { method: 'OPTIONS', headers: { origin: 'https://evil.example' } });
    assert.equal(res.status, 204, path);
    assert.equal(res.headers['access-control-allow-origin'], '*', path);
    assert.match(res.headers['access-control-allow-methods'], /OPTIONS/, path);
  }
  const metadata = await http(`${base}/.well-known/oauth-authorization-server`, { headers: { origin: 'https://evil.example' } });
  assert.equal(metadata.headers['access-control-allow-origin'], '*');
});

// ---- §5.2 discovery and registration ----------------------------------------------------------

test('discovery: protected resource and authorization server metadata', async () => {
  const id = newIdentity().deviceId;
  const prm = await http(resourceMetadataUrl(id));
  assert.equal(prm.status, 200);
  const resource = JSON.parse(prm.body);
  assert.equal(resource.resource, connectorUrl(id));
  assert.deepEqual(resource.authorization_servers, [base]);
  assert.deepEqual(resource.bearer_methods_supported, ['header']);
  assert.equal(resource.resource_name, 'DroidHands');

  for (const path of ['/.well-known/oauth-authorization-server', '/.well-known/openid-configuration']) {
    const res = await http(`${base}${path}`);
    assert.equal(res.status, 200, path);
    const as = JSON.parse(res.body);
    assert.equal(as.issuer, base);
    assert.equal(as.authorization_endpoint, `${base}/oauth/authorize`);
    assert.equal(as.token_endpoint, `${base}/oauth/token`);
    assert.equal(as.registration_endpoint, `${base}/oauth/register`);
    assert.deepEqual(as.response_types_supported, ['code']);
    assert.deepEqual(as.grant_types_supported, ['authorization_code']);
    assert.deepEqual(as.code_challenge_methods_supported, ['S256']);
    assert.deepEqual(as.token_endpoint_auth_methods_supported, ['none']);
    assert.deepEqual(as.scopes_supported, ['phone']);
  }
});

test('registration: public client, stateless client_id, unsupported values replaced', async () => {
  const res = await postJson(`${base}/oauth/register`, {
    client_name: 'Claude',
    redirect_uris: [REDIRECT_URI, 'https://claude.ai/api/mcp/auth_callback', 'com.example.app:/cb'],
    token_endpoint_auth_method: 'client_secret_basic',
    grant_types: ['authorization_code', 'refresh_token'],
  });
  assert.equal(res.status, 201, res.body);
  const client = JSON.parse(res.body);
  assert.match(client.client_id, /^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]{43}$/);
  assert.equal(client.client_name, 'Claude');
  assert.equal(client.token_endpoint_auth_method, 'none');
  assert.deepEqual(client.grant_types, ['authorization_code']);
  assert.deepEqual(client.response_types, ['code']);
  assert.equal(typeof client.client_id_issued_at, 'number');
  assert.equal(client.redirect_uris.length, 3);
});

test('registration: redirect URI denylist and malformed metadata', async () => {
  const denied = [
    'javascript:alert(1)',
    'data:text/html,x',
    'file:///etc/passwd',
    'intent://x#Intent;end',
    'content://x',
    'ws://localhost/cb',
    'mailto:a@b.c',
    'tel:123',
    'http://example.com/cb',
    'https://example.com/cb#frag',
  ];
  for (const uri of denied) {
    const res = await postJson(`${base}/oauth/register`, { client_name: 'x', redirect_uris: [uri] });
    assert.equal(res.status, 400, uri);
    assert.equal(JSON.parse(res.body).error, 'invalid_redirect_uri', uri);
  }
  const bad = await http(`${base}/oauth/register`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: '{not json' });
  assert.equal(bad.status, 400);
  assert.equal(JSON.parse(bad.body).error, 'invalid_client_metadata');
  const get = await http(`${base}/oauth/register`);
  assert.equal(get.status, 405);
});

// ---- §5.3-5.4 pairing -------------------------------------------------------------------------

test('pairing: approve, exchange with PKCE, pair_complete; the code is single-use', async (t) => {
  const d = await device(t);
  const clientId = await registerClient({ client_name: 'Conformance Agent', redirect_uris: [REDIRECT_URI] });
  const pkce = newPkce();
  const page = await openAuthorize({ clientId, pkce, deviceId: d.deviceId, state: 'st-1' });
  assert.match(page.res.headers['content-security-policy'], /default-src 'none'/);
  assert.match(page.res.body, /Conformance device/);

  const approval = await approveNext(d);
  const { request } = approval;
  assert.match(request.id, /^p_[A-Za-z0-9_-]{22}$/);
  assert.equal(request.clientName, 'Conformance Agent');
  assert.equal(request.redirectHost, 'localhost:33418');
  assert.equal(request.displayCode, page.displayCode);
  assert.match(request.displayCode, /^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{4}$/);
  assert.equal(request.userAgent, 'ConformanceBrowser/1.0');
  assert.equal(typeof request.remoteAddress, 'string');
  assert.ok(request.expiresAt > d.welcome.serverTime);

  const status = await pollUntilDecided(page.pollUrl);
  assert.equal(status.status, 'approved');
  const params = redirectParams(status.redirect);
  assert.equal(params.get('state'), 'st-1');
  assert.equal(params.get('iss'), base);
  const code = params.get('code');
  assert.ok(code);

  const html = await http(page.pageUrl);
  assert.equal(html.status, 303);
  assert.equal(html.headers.location, status.redirect);
  assert.equal(html.headers['referrer-policy'], 'no-referrer');

  const res = await exchange({ code, client_id: clientId, code_verifier: pkce.verifier, resource: connectorUrl(d.deviceId) });
  assert.equal(res.status, 200, res.body);
  assert.deepEqual(JSON.parse(res.body), { access_token: approval.accessToken, token_type: 'Bearer', scope: 'phone' });
  assert.equal(res.headers['cache-control'], 'no-store');
  assert.equal(res.headers.pragma, 'no-cache');
  const complete = await d.nextFrame('pair_complete');
  assert.deepEqual(complete, { type: 'pair_complete', id: request.id, tokenId: approval.tokenId });

  const again = await exchange({ code, client_id: clientId, code_verifier: pkce.verifier });
  assert.equal(again.status, 400);
  assert.equal(JSON.parse(again.body).error, 'invalid_grant');

  // The issued token works on the connector.
  d.serve((r) => ({ status: 200, body: JSON.stringify({ auth: r.headers.authorization }) }));
  const mcp = await postMcp(connectorUrl(d.deviceId), { token: approval.accessToken });
  assert.equal(JSON.parse(mcp.body).auth, `Bearer ${approval.accessToken}`);
});

test('pairing: deny redirects with access_denied, state and iss', async (t) => {
  const d = await device(t);
  const clientId = await registerClient();
  const page = await openAuthorize({ clientId, pkce: newPkce(), deviceId: d.deviceId, state: 'st-deny' });
  const request = await d.nextFrame('pair_request');
  d.send({ type: 'pair_response', id: request.id, approved: false, reason: 'denied' });
  const status = await pollUntilDecided(page.pollUrl);
  assert.equal(status.status, 'denied');
  const params = redirectParams(status.redirect);
  assert.equal(params.get('error'), 'access_denied');
  assert.equal(params.get('state'), 'st-deny');
  assert.equal(params.get('iss'), base);
  assert.equal(params.get('code'), null);
});

test('pairing: a wrong code_verifier burns the code and abandons the token', async (t) => {
  const d = await device(t);
  const flow = await approvedCode(d);
  const res = await exchange({ code: flow.code, client_id: flow.clientId, code_verifier: newPkce().verifier });
  assert.equal(res.status, 400);
  assert.equal(JSON.parse(res.body).error, 'invalid_grant');
  const abandoned = await d.nextFrame('pair_abandoned');
  assert.deepEqual(abandoned, { type: 'pair_abandoned', id: flow.pairingId, tokenId: flow.approval.tokenId });
  const retry = await exchange({ code: flow.code, client_id: flow.clientId, code_verifier: flow.pkce.verifier });
  assert.equal(JSON.parse(retry.body).error, 'invalid_grant');
});

test('pairing: a resource for another device at the token endpoint → invalid_target', async (t) => {
  const d = await device(t);
  const flow = await approvedCode(d);
  const res = await exchange({
    code: flow.code,
    client_id: flow.clientId,
    code_verifier: flow.pkce.verifier,
    resource: connectorUrl(newIdentity().deviceId),
  });
  assert.equal(res.status, 400);
  assert.equal(JSON.parse(res.body).error, 'invalid_target');
  assert.equal((await d.nextFrame('pair_abandoned')).tokenId, flow.approval.tokenId);
});

test('pairing: token endpoint errors (grant type, client, Basic auth)', async (t) => {
  const d = await device(t);
  const unsupported = await postForm(`${base}/oauth/token`, { grant_type: 'refresh_token', refresh_token: 'x' });
  assert.equal(JSON.parse(unsupported.body).error, 'unsupported_grant_type');
  const missing = await postForm(`${base}/oauth/token`, { grant_type: 'authorization_code' });
  assert.equal(JSON.parse(missing.body).error, 'invalid_request');
  const json = await postJson(`${base}/oauth/token`, { grant_type: 'authorization_code' });
  assert.equal(JSON.parse(json.body).error, 'invalid_request');
  const unknown = await exchange({ code: 'x'.repeat(69), client_id: 'nope.nope', code_verifier: newPkce().verifier });
  assert.equal(JSON.parse(unknown.body).error, 'invalid_client');
  const bogus = await exchange({ code: 'not-a-code', client_id: await registerClient(), code_verifier: newPkce().verifier });
  assert.equal(JSON.parse(bogus.body).error, 'invalid_grant');

  // client_id via HTTP Basic auth.
  const flow = await approvedCode(d);
  const basic = Buffer.from(`${encodeURIComponent(flow.clientId)}:`).toString('base64');
  const res = await postForm(
    `${base}/oauth/token`,
    { grant_type: 'authorization_code', code: flow.code, redirect_uri: REDIRECT_URI, code_verifier: flow.pkce.verifier },
    { authorization: `Basic ${basic}` },
  );
  assert.equal(res.status, 200, res.body);
});

test('pairing: request errors are pages, never redirects', async (t) => {
  const d = await device(t);
  const clientId = await registerClient();
  const pkce = newPkce();
  const cases = [
    [authorizeUrl({ clientId: 'bogus.client', pkce, deviceId: d.deviceId }), 400],
    [authorizeUrl({ clientId, pkce, deviceId: d.deviceId, redirectUri: 'https://evil.example/cb' }), 400],
    [authorizeUrl({ clientId, pkce, deviceId: d.deviceId, extra: { code_challenge_method: 'plain' } }), 400],
    [authorizeUrl({ clientId, pkce, deviceId: d.deviceId, extra: { response_type: 'token' } }), 400],
    [authorizeUrl({ clientId, pkce, deviceId: null }), 400],
    [authorizeUrl({ clientId, pkce, deviceId: d.deviceId, extra: { resource: 'https://other.example/d/x/mcp' } }), 400],
    [authorizeUrl({ clientId, pkce, deviceId: newIdentity().deviceId }), 503],
  ];
  for (const [url, status] of cases) {
    const res = await http(url);
    assert.equal(res.status, status, url);
    assert.equal(res.headers.location, undefined, url);
    assert.match(res.headers['content-type'], /text\/html/, url);
  }
  assert.match((await http(cases[6][0])).body, /Your phone is offline/);
  assert.equal(await d.noFrame('pair_request'), true);
});

test('pairing: a repeated authorization request reuses the pending pairing', async (t) => {
  const d = await device(t);
  const clientId = await registerClient();
  const pkce = newPkce();
  const first = await openAuthorize({ clientId, pkce, deviceId: d.deviceId });
  const second = await openAuthorize({ clientId, pkce, deviceId: d.deviceId });
  assert.equal(second.pollUrl, first.pollUrl);
  assert.equal(second.displayCode, first.displayCode);
  await d.nextFrame('pair_request');
  assert.equal(await d.noFrame('pair_request'), true);
  assert.equal((await pollStatus(first.pollUrl)).status, 'pending');
  // A wrong page token is not found.
  const wrong = first.pollUrl.replace(/t=([^&]+)/, (_, t) => `t=${t.slice(0, -2)}AA`);
  assert.equal((await http(wrong)).status, 404);
});

test('pairing: a disconnect expires pending pairings; a reconnect re-sends them', async (t) => {
  const identity = newIdentity();
  const d = await device(t, { identity });
  const clientId = await registerClient();
  const kept = await openAuthorize({ clientId, pkce: newPkce(), deviceId: identity.deviceId });
  const original = await d.nextFrame('pair_request');

  // Reconnect while the old socket still exists: the pending request moves to the new connection.
  const again = await device(t, { identity });
  const resent = await again.nextFrame('pair_request');
  assert.equal(resent.id, original.id);
  assert.equal(resent.displayCode, original.displayCode);
  assert.equal(resent.expiresAt, original.expiresAt);
  assert.equal((await pollStatus(kept.pollUrl)).status, 'pending');

  // The phone's connection drops: the pairing it was showing expires.
  again.ws.terminate();
  await again.closed;
  const status = await pollUntilDecided(kept.pollUrl);
  assert.equal(status.status, 'expired');
  assert.equal(redirectParams(status.redirect).get('error'), 'access_denied');
});

test('pairing: an unanswered request expires (pair_cancel); a late approval is abandoned', { skip: !(config.pairingTtlMs <= 10_000) && 'pairing TTL not short' }, async (t) => {
  const d = await device(t);
  const page = await openAuthorize({ clientId: await registerClient(), pkce: newPkce(), deviceId: d.deviceId });
  const request = await d.nextFrame('pair_request');
  const cancel = await d.nextFrame('pair_cancel', config.pairingTtlMs + 5_000);
  assert.deepEqual(cancel, { type: 'pair_cancel', id: request.id });
  const status = await pollStatus(page.pollUrl);
  assert.equal(status.status, 'expired');
  assert.equal(redirectParams(status.redirect).get('error'), 'access_denied');

  const accessToken = tokenFor(d.deviceId);
  const tokenId = accessToken.split('.')[2];
  d.send({ type: 'pair_response', id: request.id, approved: true, accessToken, tokenId });
  assert.deepEqual(await d.nextFrame('pair_abandoned'), { type: 'pair_abandoned', id: request.id, tokenId });
});

test('pairing: a code never exchanged expires and the token is abandoned', { skip: !(config.codeTtlMs <= 10_000) && 'code TTL not short' }, async (t) => {
  const d = await device(t);
  const flow = await approvedCode(d);
  const abandoned = await d.nextFrame('pair_abandoned', config.codeTtlMs + 5_000);
  assert.deepEqual(abandoned, { type: 'pair_abandoned', id: flow.pairingId, tokenId: flow.approval.tokenId });
  const res = await exchange({ code: flow.code, client_id: flow.clientId, code_verifier: flow.pkce.verifier });
  assert.equal(JSON.parse(res.body).error, 'invalid_grant');
});

test('pairing: a repeated approval is ignored; another token for the same pairing is abandoned', async (t) => {
  const d = await device(t);
  const flow = await approvedCode(d);
  const { request, accessToken, tokenId } = flow.approval;
  d.send({ type: 'pair_response', id: request.id, approved: true, accessToken, tokenId });
  const other = tokenFor(d.deviceId);
  d.send({ type: 'pair_response', id: request.id, approved: true, accessToken: other, tokenId: other.split('.')[2] });
  assert.deepEqual(await d.nextFrame('pair_abandoned'), { type: 'pair_abandoned', id: request.id, tokenId: other.split('.')[2] });
  await d.sync();
  assert.equal(d.buffered.some((f) => f.type === 'pair_abandoned'), false, 'the token handed to the relay is kept');
  const res = await exchange({ code: flow.code, client_id: flow.clientId, code_verifier: flow.pkce.verifier });
  assert.equal(JSON.parse(res.body).access_token, accessToken);
});

test('pairing: notices for an offline phone are delivered when it reconnects', async (t) => {
  const identity = newIdentity();
  const d = await device(t, { identity });
  const flow = await approvedCode(d);
  d.ws.terminate();
  await d.closed;
  await sleep(300); // let the relay notice the disconnect
  // The relay holds the token: the exchange works while the phone is offline.
  const res = await exchange({ code: flow.code, client_id: flow.clientId, code_verifier: flow.pkce.verifier });
  assert.equal(res.status, 200, res.body);
  const back = await device(t, { identity });
  assert.deepEqual(await back.nextFrame('pair_complete'), { type: 'pair_complete', id: flow.pairingId, tokenId: flow.approval.tokenId });
});

test('pairing: at most 3 pending pairings per phone; the next one gets an error page (429)', async (t) => {
  const d = await device(t);
  const clientId = await registerClient();
  for (let i = 0; i < 3; i++) await openAuthorize({ clientId, pkce: newPkce(), deviceId: d.deviceId, state: `busy-${i}` });
  const res = await http(authorizeUrl({ clientId, pkce: newPkce(), deviceId: d.deviceId, state: 'busy-3' }));
  assert.equal(res.status, 429);
  assert.match(res.headers['content-type'], /text\/html/);
  assert.match(res.body, /several connection requests waiting/);
  for (let i = 0; i < 3; i++) await d.nextFrame('pair_request');
  assert.equal(await d.noFrame('pair_request'), true);
});

test('pairing: a pair_response for an unknown pairing with a token is abandoned', async (t) => {
  const d = await device(t);
  const accessToken = tokenFor(d.deviceId);
  const tokenId = accessToken.split('.')[2];
  d.send({ type: 'pair_response', id: 'p_AAAAAAAAAAAAAAAAAAAAAA', approved: true, accessToken, tokenId });
  assert.deepEqual(await d.nextFrame('pair_abandoned'), { type: 'pair_abandoned', id: 'p_AAAAAAAAAAAAAAAAAAAAAA', tokenId });
});

// ---- §5.5 setup and misc ----------------------------------------------------------------------

test('setup: page with the configure deep link; QR endpoint checks the access key', async () => {
  const res = await http(`${base}/setup`);
  assert.equal(res.status, 200);
  assert.match(res.headers['content-type'], /text\/html/);
  assert.match(res.headers['content-security-policy'], /default-src 'none'/);
  assert.ok(res.body.includes(`droidhands://configure?relay=${encodeURIComponent(base)}`));
  assert.ok(res.body.includes(`data-relay="${base}"`));
  if (config.apkUrl !== null) assert.ok(res.body.includes(`href="${config.apkUrl}"`), 'install button links APK_URL');
  if (config.accessKey !== null) {
    assert.match(res.body, /data-key-required="true"/);
    assert.ok(!res.body.includes(config.accessKey), 'the access key is never in the page');
    const wrong = await postJson(`${base}/setup/qr`, { key: 'wrong' });
    assert.equal(wrong.status, 403);
    const right = await postJson(`${base}/setup/qr`, { key: config.accessKey });
    assert.equal(right.status, 200);
    const qr = JSON.parse(right.body);
    assert.equal(typeof qr.size, 'number');
    assert.match(qr.path, /^M\d/);
  }
});

test('misc: health, banner, unknown paths, device endpoint without upgrade', async () => {
  const health = await http(`${base}/healthz`);
  assert.equal(health.status, 200);
  assert.deepEqual(JSON.parse(health.body), { ok: true });
  const banner = await http(`${base}/`);
  assert.equal(banner.status, 200);
  assert.match(banner.body, /DroidHands relay/);
  assert.equal((await http(`${base}/nope`)).status, 404);
  const device = await http(`${base}/v1/device`);
  assert.equal(device.status, 426);
  // Every response carries these.
  assert.equal(health.headers['cache-control'], 'no-store');
  assert.equal(health.headers['x-content-type-options'], 'nosniff');
  // Origin: checked on misc endpoints, not on the relay's own pages.
  assert.equal((await http(`${base}/healthz`, { headers: { origin: 'https://evil.example' } })).status, 403);
  assert.equal((await http(`${base}/oauth/authorize`, { headers: { origin: 'https://evil.example' } })).status, 400);
});

test('misc: wrong methods and oversized bodies on the OAuth endpoints', async () => {
  const discovery = await http(`${base}/.well-known/oauth-authorization-server`, { method: 'POST' });
  assert.equal(discovery.status, 405);
  assert.equal(discovery.headers.allow, 'GET, HEAD, OPTIONS');
  assert.equal(JSON.parse(discovery.body).error, 'invalid_request');
  const token = await http(`${base}/oauth/token`);
  assert.equal(token.status, 405);
  assert.equal(token.headers.allow, 'POST, OPTIONS');
  assert.equal(token.headers.pragma, 'no-cache');
  const authorize = await http(`${base}/oauth/authorize`, { method: 'POST' });
  assert.equal(authorize.status, 405);
  assert.equal(authorize.headers.allow, 'GET');
  const poll = await http(`${base}/oauth/pair/p_AAAAAAAAAAAAAAAAAAAAAA?t=x`, { method: 'POST' });
  assert.equal(poll.status, 405);
  assert.equal(poll.headers.allow, 'GET, HEAD');
  const unknown = await http(`${base}/oauth/pair/p_AAAAAAAAAAAAAAAAAAAAAA?t=x`);
  assert.equal(unknown.status, 404);
  assert.deepEqual(JSON.parse(unknown.body), { error: 'not_found' });
  const big = await postJson(`${base}/oauth/register`, { client_name: 'x'.repeat(17 * 1024), redirect_uris: [REDIRECT_URI] });
  assert.equal(big.status, 413);
  assert.equal(JSON.parse(big.body).error, 'invalid_client_metadata');
});

// Leaves time for close frames to settle before the runner stops the relay.
test('teardown', async () => {
  await sleep(50);
});
