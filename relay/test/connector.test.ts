import assert from 'node:assert/strict';
import { test } from 'node:test';
import { UNAUTHORIZED_BODY, connectReady, httpRequest, newIdentity, postMcp, startRelay, tokenFor } from './helpers.js';

const challengeFor = (base: string, deviceId: string): string =>
  `Bearer realm="droidhands", resource_metadata="${base}/.well-known/oauth-protected-resource/d/${deviceId}/mcp"`;

test('POST /d/<id>/mcp is tunneled to that device as /mcp', async (t) => {
  const relay = await startRelay(t);
  const device = await connectReady(relay);
  const token = tokenFor(device.identity.deviceId);
  const pending = postMcp(relay, { path: `/d/${device.identity.deviceId}/mcp`, token });
  const request = await device.nextRequest();
  assert.equal(request.path, '/mcp');
  assert.equal(request.headers.authorization, `Bearer ${token}`);
  device.respond(request.id, 200, '{"ok":1}', { 'content-type': 'application/json' });
  const res = await pending;
  assert.equal(res.status, 200);
  assert.equal(res.body, '{"ok":1}');
});

test('connector 401s (no token, malformed, other device) carry resource_metadata; no device contacted', async (t) => {
  const relay = await startRelay(t, { publicUrl: 'https://relay.example.com' });
  const device = await connectReady(relay);
  const { deviceId } = device.identity;
  const variants: Array<Record<string, string>> = [
    {},
    { authorization: 'Bearer garbage' },
    { authorization: `Bearer ${tokenFor(newIdentity().deviceId)}` },
  ];
  for (const headers of variants) {
    const res = await postMcp(relay, { path: `/d/${deviceId}/mcp`, headers });
    assert.equal(res.status, 401, JSON.stringify(headers));
    assert.equal(res.headers['www-authenticate'], challengeFor('https://relay.example.com', deviceId));
    assert.equal(res.body, UNAUTHORIZED_BODY);
  }
  assert.equal(device.bufferedRequests(), 0);
});

test('a device-returned 401 on the connector gets resource_metadata (added or rewritten)', async (t) => {
  const relay = await startRelay(t);
  const device = await connectReady(relay);
  const { deviceId } = device.identity;
  const token = tokenFor(deviceId);
  const variants: Array<Record<string, string>> = [{}, { 'www-authenticate': 'Bearer realm="droidhands"' }, { 'www-authenticate': 'Basic' }];
  for (const deviceHeaders of variants) {
    const pending = postMcp(relay, { path: `/d/${deviceId}/mcp`, token });
    const request = await device.nextRequest();
    device.respond(request.id, 401, UNAUTHORIZED_BODY, { 'content-type': 'application/json', ...deviceHeaders });
    const res = await pending;
    assert.equal(res.status, 401);
    assert.equal(res.headers['www-authenticate'], challengeFor(relay.url, deviceId));
    assert.equal(res.body, UNAUTHORIZED_BODY);
  }
});

test('the legacy /mcp endpoint keeps its plain challenge', async (t) => {
  const relay = await startRelay(t);
  const res = await postMcp(relay);
  assert.equal(res.headers['www-authenticate'], 'Bearer realm="droidhands"');
});

test('connector: GET and DELETE -> 405; malformed ids -> 404; offline -> 503', async (t) => {
  const relay = await startRelay(t);
  const deviceId = newIdentity().deviceId;
  for (const method of ['GET', 'DELETE']) {
    const res = await httpRequest(`${relay.url}/d/${deviceId}/mcp`, { method });
    assert.equal(res.status, 405, method);
    assert.equal(res.headers.allow, 'POST');
  }
  for (const path of [`/d/${deviceId.toUpperCase()}/mcp`, `/d/${deviceId}x/mcp`, `/d/${deviceId}/mcp/extra`, `/d/${deviceId}`]) {
    assert.equal((await postMcp(relay, { path, token: tokenFor(deviceId) })).status, 404, path);
  }
  const offline = await postMcp(relay, { path: `/d/${deviceId}/mcp`, token: tokenFor(deviceId) });
  assert.equal(offline.status, 503);
  assert.equal(offline.headers['retry-after'], '30');
});

test('connector discovery requests without a token do not count as auth failures', async (t) => {
  const relay = await startRelay(t, { rateLimitAuthFailuresPerMin: 2 });
  const device = await connectReady(relay);
  for (let i = 0; i < 5; i++) {
    assert.equal((await postMcp(relay, { path: `/d/${device.identity.deviceId}/mcp` })).status, 401);
  }
  const pending = postMcp(relay, { path: `/d/${device.identity.deviceId}/mcp`, token: tokenFor(device.identity.deviceId) });
  const request = await device.nextRequest();
  device.respond(request.id, 200, '{}', { 'content-type': 'application/json' });
  assert.equal((await pending).status, 200);

  // A token for another device does count.
  for (let i = 0; i < 3; i++) {
    await postMcp(relay, { path: `/d/${device.identity.deviceId}/mcp`, token: tokenFor(newIdentity().deviceId) });
  }
  assert.equal((await postMcp(relay, { path: `/d/${device.identity.deviceId}/mcp` })).status, 429);
});

test('connector requests with a non-allowlisted Origin -> 403', async (t) => {
  const relay = await startRelay(t);
  const device = await connectReady(relay);
  const res = await postMcp(relay, {
    path: `/d/${device.identity.deviceId}/mcp`,
    token: tokenFor(device.identity.deviceId),
    headers: { origin: 'https://evil.example' },
  });
  assert.equal(res.status, 403);
  assert.equal(device.bufferedRequests(), 0);
});
