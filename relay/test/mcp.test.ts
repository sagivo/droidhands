import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  UNAUTHORIZED_BODY,
  connectReady,
  httpRequest,
  newIdentity,
  postMcp,
  startRelay,
  tokenFor,
  type HttpResult,
} from './helpers.js';

function assertCommonHeaders(res: HttpResult): void {
  assert.equal(res.headers['cache-control'], 'no-store');
  assert.equal(res.headers['x-content-type-options'], 'nosniff');
  assert.equal(res.headers['access-control-allow-origin'], undefined, 'no CORS');
}

function rpcError(res: HttpResult): { code: number; message: string } {
  const parsed = JSON.parse(res.body) as { jsonrpc: string; id: unknown; error: { code: number; message: string } };
  assert.equal(parsed.jsonrpc, '2.0');
  assert.equal(parsed.id, null);
  return parsed.error;
}

test('POST /mcp is tunneled to the device and the response is mapped back', async (t) => {
  const relay = await startRelay(t);
  const device = await connectReady(relay);
  const token = tokenFor(device.identity.deviceId);
  const body = '{"jsonrpc":"2.0","id":1,"method":"tools/list"}';

  const pending = postMcp(relay, {
    token,
    body,
    headers: {
      'mcp-protocol-version': '2025-06-18',
      'mcp-session-id': 'sess-1',
      'user-agent': 'claude-code/2.0',
      cookie: 'secret=1',
      'x-custom': 'nope',
      'x-forwarded-for': '1.2.3.4',
    },
  });
  const request = await device.nextRequest();
  assert.equal(request.method, 'POST');
  assert.equal(request.path, '/mcp');
  assert.equal(request.body, body);
  assert.equal(request.remoteAddress, '127.0.0.1');
  assert.match(request.id, /^r_[A-Za-z0-9_-]+$/);
  assert.deepEqual(request.headers, {
    authorization: `Bearer ${token}`,
    'content-type': 'application/json',
    accept: 'application/json, text/event-stream',
    'mcp-protocol-version': '2025-06-18',
    'mcp-session-id': 'sess-1',
    'user-agent': 'claude-code/2.0',
  });

  const responseBody = `{"jsonrpc":"2.0","id":1,"result":{"echo":${JSON.stringify(request.body)}}}`;
  device.respond(request.id, 200, responseBody, {
    'content-type': 'application/json',
    'mcp-session-id': 'sess-2',
    'set-cookie': 'evil=1',
    'access-control-allow-origin': '*',
    'x-device': 'internal',
    'retry-after': 'not\r\ninjected',
  });
  const res = await pending;
  assert.equal(res.status, 200);
  assert.equal(res.body, responseBody);
  assert.equal(res.headers['content-type'], 'application/json');
  assert.equal(res.headers['mcp-session-id'], 'sess-2');
  assert.equal(res.headers['content-length'], String(Buffer.byteLength(responseBody)));
  assert.equal(res.headers['set-cookie'], undefined);
  assert.equal(res.headers['x-device'], undefined);
  assert.equal(res.headers['retry-after'], undefined, 'invalid header values are dropped');
  assertCommonHeaders(res);
});

test('UTF-8 bodies survive the tunnel byte-for-byte', async (t) => {
  const relay = await startRelay(t);
  const device = await connectReady(relay);
  const body = '{"jsonrpc":"2.0","id":"ä","method":"tools/call","params":{"text":"שלום 👋"}}';
  const pending = postMcp(relay, { token: tokenFor(device.identity.deviceId), body });
  const request = await device.nextRequest();
  assert.equal(request.body, body);
  device.respond(request.id, 200, request.body, { 'content-type': 'application/json; charset=utf-8' });
  const res = await pending;
  assert.equal(res.body, body);
  assert.equal(res.headers['content-length'], String(Buffer.byteLength(body)));
});

test('authorization scheme is case-insensitive and forwarded normalized', async (t) => {
  const relay = await startRelay(t);
  const device = await connectReady(relay);
  const token = tokenFor(device.identity.deviceId);
  const pending = postMcp(relay, { headers: { authorization: `bEaReR  ${token}` } });
  const request = await device.nextRequest();
  assert.equal(request.headers.authorization, `Bearer ${token}`);
  device.respond(request.id, 202, null);
  assert.equal((await pending).status, 202);
});

test('202 with a null body becomes an empty response', async (t) => {
  const relay = await startRelay(t);
  const device = await connectReady(relay);
  const pending = postMcp(relay, {
    token: tokenFor(device.identity.deviceId),
    body: '{"jsonrpc":"2.0","method":"notifications/initialized"}',
  });
  const request = await device.nextRequest();
  device.respond(request.id, 202, null);
  const res = await pending;
  assert.equal(res.status, 202);
  assert.equal(res.body, '');
  assert.equal(res.headers['content-length'], '0');
  assert.equal(res.headers['content-type'], undefined);
  assertCommonHeaders(res);
});

test('device-returned 401 propagates with WWW-Authenticate', async (t) => {
  const relay = await startRelay(t);
  const device = await connectReady(relay);
  const pending = postMcp(relay, { token: tokenFor(device.identity.deviceId) });
  const request = await device.nextRequest();
  device.respond(request.id, 401, UNAUTHORIZED_BODY, {
    'content-type': 'application/json',
    'www-authenticate': 'Bearer realm="phonebridge"',
  });
  const res = await pending;
  assert.equal(res.status, 401);
  assert.equal(res.headers['www-authenticate'], 'Bearer realm="phonebridge"');
  assert.equal(res.body, UNAUTHORIZED_BODY);
});

test('missing or malformed tokens get 401 without contacting any device', async (t) => {
  const relay = await startRelay(t);
  const device = await connectReady(relay);
  const valid = tokenFor(device.identity.deviceId);
  const variants: Array<Record<string, string>> = [
    {},
    { authorization: '' },
    { authorization: valid },
    { authorization: `Basic ${valid}` },
    { authorization: `Bearer ${valid}x` },
    { authorization: `Bearer ${valid.replace('pb1.', 'pb2.')}` },
    { authorization: `Bearer ${valid.slice(0, -1)}` },
    { authorization: 'Bearer pb1.short.abc.def' },
  ];
  for (const headers of variants) {
    const res = await postMcp(relay, { headers });
    assert.equal(res.status, 401, JSON.stringify(headers));
    assert.equal(res.headers['www-authenticate'], 'Bearer realm="phonebridge"');
    assert.equal(res.headers['content-type'], 'application/json');
    assert.equal(res.body, UNAUTHORIZED_BODY);
    assertCommonHeaders(res);
  }
  assert.equal(device.bufferedRequests(), 0);
});

test('device offline -> 503 with Retry-After and a JSON-RPC error', async (t) => {
  const relay = await startRelay(t);
  const res = await postMcp(relay, { token: tokenFor(newIdentity().deviceId) });
  assert.equal(res.status, 503);
  assert.equal(res.headers['retry-after'], '30');
  assert.deepEqual(rpcError(res), { code: -32000, message: 'Device offline' });
  assertCommonHeaders(res);
});

test('no response within REQUEST_TIMEOUT_MS -> 504; a late response is ignored', async (t) => {
  const relay = await startRelay(t, { requestTimeoutMs: 150 });
  const device = await connectReady(relay);
  const token = tokenFor(device.identity.deviceId);
  const pending = postMcp(relay, { token });
  const request = await device.nextRequest();
  const res = await pending;
  assert.equal(res.status, 504);
  assert.deepEqual(rpcError(res), { code: -32000, message: 'Device did not respond in time' });

  device.respond(request.id, 200, '{}', { 'content-type': 'application/json' });
  // The relay is still healthy and the device still routable.
  const next = postMcp(relay, { token });
  const second = await device.nextRequest();
  device.respond(second.id, 200, '{"ok":1}', { 'content-type': 'application/json' });
  assert.equal((await next).body, '{"ok":1}');
});

test('malformed device response -> 502', async (t) => {
  const relay = await startRelay(t);
  const device = await connectReady(relay);
  const token = tokenFor(device.identity.deviceId);
  for (const bad of [{ status: '200' }, { status: 99 }, { status: 200, body: 5 }, { status: 200, headers: [] }]) {
    const pending = postMcp(relay, { token });
    const request = await device.nextRequest();
    device.send({ type: 'response', id: request.id, body: null, ...bad });
    const res = await pending;
    assert.equal(res.status, 502, JSON.stringify(bad));
    assert.equal(rpcError(res).code, -32000);
  }
});

test('concurrent requests are multiplexed and may be answered out of order', async (t) => {
  const relay = await startRelay(t);
  const device = await connectReady(relay);
  const token = tokenFor(device.identity.deviceId);
  const count = 8;
  const pending = Array.from({ length: count }, (_, i) =>
    postMcp(relay, { token, body: `{"jsonrpc":"2.0","id":${i},"method":"ping"}` }),
  );
  const requests = [];
  for (let i = 0; i < count; i++) requests.push(await device.nextRequest());
  assert.equal(new Set(requests.map((r) => r.id)).size, count, 'request ids are unique');
  for (const request of requests.reverse()) {
    const id = (JSON.parse(request.body) as { id: number }).id;
    device.respond(request.id, 200, `{"jsonrpc":"2.0","id":${id},"result":{}}`, { 'content-type': 'application/json' });
  }
  const results = await Promise.all(pending);
  results.forEach((res, i) => {
    assert.equal(res.status, 200);
    assert.equal((JSON.parse(res.body) as { id: number }).id, i);
  });
});

test('in-flight cap per device -> 429', async (t) => {
  const relay = await startRelay(t, { maxInflightPerDevice: 2 });
  const device = await connectReady(relay);
  const token = tokenFor(device.identity.deviceId);
  const first = postMcp(relay, { token });
  const second = postMcp(relay, { token });
  const r1 = await device.nextRequest();
  const r2 = await device.nextRequest();

  const third = await postMcp(relay, { token });
  assert.equal(third.status, 429);
  assert.equal(third.headers['retry-after'], '1');
  assert.equal(rpcError(third).code, -32000);
  assert.equal(device.bufferedRequests(), 0, 'the over-cap request never reached the device');

  device.respond(r1.id, 200, '{}', { 'content-type': 'application/json' });
  device.respond(r2.id, 200, '{}', { 'content-type': 'application/json' });
  assert.equal((await first).status, 200);
  assert.equal((await second).status, 200);

  // Slots are released once answered.
  const fourth = postMcp(relay, { token });
  const r4 = await device.nextRequest();
  device.respond(r4.id, 200, '{}', { 'content-type': 'application/json' });
  assert.equal((await fourth).status, 200);
});

test('path token disabled -> 404 without contacting the device', async (t) => {
  const relay = await startRelay(t);
  const device = await connectReady(relay);
  const token = tokenFor(device.identity.deviceId);
  const res = await postMcp(relay, { path: `/mcp/${token}` });
  assert.equal(res.status, 404);
  assert.equal(device.bufferedRequests(), 0);
});

test('path token enabled -> routed with an authorization header and path /mcp', async (t) => {
  const relay = await startRelay(t, { allowTokenInUrl: true });
  const device = await connectReady(relay);
  const token = tokenFor(device.identity.deviceId);
  const pending = postMcp(relay, { path: `/mcp/${token}?x=1` });
  const request = await device.nextRequest();
  assert.equal(request.path, '/mcp');
  assert.equal(request.headers.authorization, `Bearer ${token}`);
  device.respond(request.id, 200, '{}', { 'content-type': 'application/json' });
  assert.equal((await pending).status, 200);

  const bad = await postMcp(relay, { path: '/mcp/not-a-token' });
  assert.equal(bad.status, 401);
  assert.equal(bad.body, UNAUTHORIZED_BODY);
  assert.equal((await postMcp(relay, { path: `/mcp/${token}/extra` })).status, 404);
});

test('requests with a non-allowlisted Origin -> 403; allowlisted origins are forwarded', async (t) => {
  const relay = await startRelay(t, { allowedOrigins: ['https://good.example'] });
  const device = await connectReady(relay);
  const token = tokenFor(device.identity.deviceId);

  const evil = await postMcp(relay, { token, headers: { origin: 'https://evil.example' } });
  assert.equal(evil.status, 403);
  assert.equal(evil.headers['access-control-allow-origin'], undefined);
  assert.equal(device.bufferedRequests(), 0);

  const pending = postMcp(relay, { token, headers: { origin: 'https://good.example' } });
  const request = await device.nextRequest();
  assert.equal(request.headers.origin, 'https://good.example');
  device.respond(request.id, 200, '{}', { 'content-type': 'application/json' });
  assert.equal((await pending).status, 200);
});

test('any Origin is rejected when no origins are allowlisted', async (t) => {
  const relay = await startRelay(t);
  const device = await connectReady(relay);
  const res = await postMcp(relay, { token: tokenFor(device.identity.deviceId), headers: { origin: 'null' } });
  assert.equal(res.status, 403);
});

test('bodies over MAX_BODY_BYTES -> 413 (declared and chunked)', async (t) => {
  const relay = await startRelay(t, { maxBodyBytes: 1024 });
  const device = await connectReady(relay);
  const headers = { authorization: `Bearer ${tokenFor(device.identity.deviceId)}`, 'content-type': 'application/json' };
  const big = 'x'.repeat(2048);

  const declared = await httpRequest(`${relay.url}/mcp`, { method: 'POST', headers, body: big });
  assert.equal(declared.status, 413);
  assert.equal(declared.headers.connection, 'close');
  assert.equal(rpcError(declared).code, -32000);

  const chunked = await httpRequest(`${relay.url}/mcp`, { method: 'POST', headers, body: big, chunked: true });
  assert.equal(chunked.status, 413);
  assert.equal(device.bufferedRequests(), 0);

  // Exactly at the limit is fine.
  const pending = httpRequest(`${relay.url}/mcp`, { method: 'POST', headers, body: 'y'.repeat(1024) });
  const request = await device.nextRequest();
  assert.equal(request.body.length, 1024);
  device.respond(request.id, 200, '{}', { 'content-type': 'application/json' });
  assert.equal((await pending).status, 200);
});

test('GET and DELETE /mcp -> 405 with Allow: POST', async (t) => {
  const relay = await startRelay(t);
  for (const method of ['GET', 'DELETE', 'PUT', 'OPTIONS']) {
    const res = await httpRequest(`${relay.url}/mcp`, { method });
    assert.equal(res.status, 405, method);
    assert.equal(res.headers.allow, 'POST');
    assertCommonHeaders(res);
  }
});

test('healthz, root, unknown paths and the device path over plain HTTP', async (t) => {
  const relay = await startRelay(t);
  const health = await httpRequest(`${relay.url}/healthz`);
  assert.equal(health.status, 200);
  assert.deepEqual(JSON.parse(health.body), { ok: true });
  assert.equal(health.headers['content-type'], 'application/json');
  assertCommonHeaders(health);

  const root = await httpRequest(`${relay.url}/`);
  assert.equal(root.status, 200);
  assert.match(root.body, /PhoneBridge relay/);

  assert.equal((await httpRequest(`${relay.url}/nope`)).status, 404);
  assert.equal((await httpRequest(`${relay.url}/mcpx`, { method: 'POST' })).status, 404);
  assert.equal((await httpRequest(`${relay.url}/healthz`, { method: 'POST' })).status, 405);
  assert.equal((await httpRequest(`${relay.url}/v1/device`)).status, 426);
});

test('more than 30 auth failures per minute -> 429 for that IP', async (t) => {
  const relay = await startRelay(t);
  const device = await connectReady(relay);
  for (let i = 0; i < 30; i++) {
    assert.equal((await postMcp(relay, { token: 'garbage' })).status, 401, `attempt ${i + 1}`);
  }
  assert.equal((await postMcp(relay, { token: 'garbage' })).status, 401, 'the 31st failure is still answered');
  const blocked = await postMcp(relay, { token: tokenFor(device.identity.deviceId) });
  assert.equal(blocked.status, 429, 'even a well-formed token is refused while blocked');
  assert.ok(Number(blocked.headers['retry-after']) > 0);
  assert.equal(device.bufferedRequests(), 0);
});

test('device-returned 401s count toward the auth-failure limit', async (t) => {
  const relay = await startRelay(t, { rateLimitAuthFailuresPerMin: 2 });
  const device = await connectReady(relay);
  const token = tokenFor(device.identity.deviceId);
  for (let i = 0; i < 3; i++) {
    const pending = postMcp(relay, { token });
    const request = await device.nextRequest();
    device.respond(request.id, 401, UNAUTHORIZED_BODY, { 'www-authenticate': 'Bearer realm="phonebridge"' });
    assert.equal((await pending).status, 401);
  }
  assert.equal((await postMcp(relay, { token })).status, 429);
});

test('more than RATE_LIMIT_REQUESTS_PER_MIN requests -> 429', async (t) => {
  const relay = await startRelay(t, { rateLimitRequestsPerMin: 5 });
  for (let i = 0; i < 5; i++) assert.equal((await postMcp(relay, { token: tokenFor(newIdentity().deviceId) })).status, 503);
  const res = await postMcp(relay, { token: tokenFor(newIdentity().deviceId) });
  assert.equal(res.status, 429);
  assert.ok(Number(res.headers['retry-after']) > 0);
});
