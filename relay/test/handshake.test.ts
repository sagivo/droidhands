import assert from 'node:assert/strict';
import { test } from 'node:test';
import WebSocket from 'ws';
import { connectDevice, connectReady, newIdentity, startRelay, waitFor } from './helpers.js';

test('welcome carries the deviceId, mcpUrl and connectUrl from PUBLIC_URL and the server time', async (t) => {
  const relay = await startRelay(t, { publicUrl: 'https://relay.example.com/' });
  const before = Date.now();
  const device = await connectDevice(relay);
  const welcome = await device.nextFrame('welcome');
  assert.deepEqual(Object.keys(welcome).sort(), ['connectUrl', 'deviceId', 'mcpUrl', 'serverTime', 'type']);
  assert.equal(welcome.deviceId, device.identity.deviceId);
  assert.equal(welcome.mcpUrl, 'https://relay.example.com/mcp');
  assert.equal(welcome.connectUrl, `https://relay.example.com/d/${device.identity.deviceId}/mcp`);
  assert.ok(typeof welcome.serverTime === 'number' && welcome.serverTime >= before && welcome.serverTime <= Date.now());
  assert.equal(relay.deviceCount(), 1);
});

test('mcpUrl and connectUrl are derived from the Host header when PUBLIC_URL is unset', async (t) => {
  const relay = await startRelay(t);
  const device = await connectDevice(relay);
  const welcome = await device.nextFrame('welcome');
  assert.equal(welcome.mcpUrl, `${relay.url}/mcp`);
  assert.equal(welcome.connectUrl, `${relay.url}/d/${device.identity.deviceId}/mcp`);
});

test('X-Forwarded-Proto/Host are used only with TRUST_PROXY', async (t) => {
  const headers = { 'x-forwarded-proto': 'https', 'x-forwarded-host': 'relay.example.org' };
  const untrusted = await startRelay(t);
  const a = await connectDevice(untrusted, { headers });
  assert.equal((await a.nextFrame('welcome')).mcpUrl, `${untrusted.url}/mcp`);

  const trusted = await startRelay(t, { trustProxy: true });
  const b = await connectDevice(trusted, { headers });
  assert.equal((await b.nextFrame('welcome')).mcpUrl, 'https://relay.example.org/mcp');
});

test('hello with an id that does not match the secret -> unauthorized, 4001', async (t) => {
  const relay = await startRelay(t);
  const other = newIdentity();
  const device = await connectDevice(relay, { hello: { deviceId: other.deviceId } });
  const error = await device.nextFrame('error');
  assert.equal(error.code, 'unauthorized');
  assert.equal(typeof error.message, 'string');
  assert.equal((await device.closed).code, 4001);
  assert.equal(relay.deviceCount(), 0);
});

test('malformed deviceId / deviceSecret -> unauthorized, 4001', async (t) => {
  const relay = await startRelay(t);
  for (const hello of [
    { deviceId: 'TOO-SHORT' },
    { deviceSecret: 'AAAA' },
    { deviceSecret: `${newIdentity().secret}=` },
  ]) {
    const device = await connectDevice(relay, { hello });
    assert.equal((await device.nextFrame('error')).code, 'unauthorized');
    assert.equal((await device.closed).code, 4001);
  }
});

test('unsupported protocol version -> protocol_version, 4002', async (t) => {
  const relay = await startRelay(t);
  for (const protocolVersion of [2, '1', undefined]) {
    const device = await connectDevice(relay, { hello: { protocolVersion } });
    assert.equal((await device.nextFrame('error')).code, 'protocol_version');
    assert.equal((await device.closed).code, 4002);
  }
});

test('first frame that is not a hello -> bad_request, 4000', async (t) => {
  const relay = await startRelay(t);
  for (const rawHello of ['not json', '[]', '{"type":"ping","t":1}', '{"protocolVersion":1}']) {
    const device = await connectDevice(relay, { rawHello });
    assert.equal((await device.nextFrame('error')).code, 'bad_request');
    assert.equal((await device.closed).code, 4000);
  }
  const wrongTypes = await connectDevice(relay, { hello: { deviceSecret: 42 } });
  assert.equal((await wrongTypes.nextFrame('error')).code, 'bad_request');
  assert.equal((await wrongTypes.closed).code, 4000);
});

test('no hello within HELLO_TIMEOUT_MS -> 4008', async (t) => {
  const relay = await startRelay(t, { helloTimeoutMs: 100 });
  const device = await connectDevice(relay, { rawHello: null });
  assert.equal((await device.closed).code, 4008);
});

test('access key: required, wrong, and correct', async (t) => {
  const relay = await startRelay(t, { accessKey: 'open-sesame' });

  const missing = await connectDevice(relay);
  assert.equal((await missing.nextFrame('error')).code, 'access_key');
  assert.equal((await missing.closed).code, 4001);

  const wrong = await connectDevice(relay, { hello: { accessKey: 'open-sesamE' } });
  assert.equal((await wrong.nextFrame('error')).code, 'access_key');
  assert.equal((await wrong.closed).code, 4001);

  const ok = await connectDevice(relay, { hello: { accessKey: 'open-sesame' } });
  assert.equal((await ok.nextFrame('welcome')).deviceId, ok.identity.deviceId);
});

test('access key is ignored when the relay does not require one', async (t) => {
  const relay = await startRelay(t);
  const device = await connectDevice(relay, { hello: { accessKey: 'whatever' } });
  await device.nextFrame('welcome');
});

test('JSON ping is answered with a pong carrying the same t', async (t) => {
  const relay = await startRelay(t);
  const device = await connectReady(relay);
  device.send({ type: 'ping', t: 1731500000123 });
  assert.deepEqual(await device.nextFrame('pong'), { type: 'pong', t: 1731500000123 });
});

test('unknown frame types, non-JSON and binary frames are ignored', async (t) => {
  const relay = await startRelay(t);
  const device = await connectReady(relay);
  device.send({ type: 'future-frame', data: 1 });
  device.ws.send('}{ not json');
  device.ws.send(Buffer.from([1, 2, 3]), { binary: true });
  device.send({ type: 'response', id: 'r_unknown', status: 200, headers: {}, body: null });
  device.send({ type: 'ping', t: 7 });
  assert.equal((await device.nextFrame('pong')).t, 7);
  assert.equal(relay.deviceCount(), 1);
});

test('upgrade on any other path is refused with 404', async (t) => {
  const relay = await startRelay(t);
  const ws = new WebSocket(`${relay.url.replace('http', 'ws')}/v1/other`);
  const status = await new Promise<number>((resolve) => {
    ws.on('unexpected-response', (_req, res) => resolve(res.statusCode ?? 0));
    ws.on('error', () => resolve(-1));
  });
  assert.equal(status, 404);
});

test('heartbeat: a device that stops answering WebSocket pings is dropped', async (t) => {
  const relay = await startRelay(t, { heartbeatIntervalMs: 40 });
  const device = await connectReady(relay, { autoPong: false });
  assert.equal(relay.deviceCount(), 1);
  const closed = await device.closed;
  assert.equal(closed.code, 1006, 'terminated without a close frame');
  await waitFor(() => relay.deviceCount() === 0, 'device removal');
});

test('heartbeat: a responsive device stays connected', async (t) => {
  const relay = await startRelay(t, { heartbeatIntervalMs: 20 });
  const device = await connectReady(relay);
  await new Promise((r) => setTimeout(r, 200));
  assert.equal(device.ws.readyState, WebSocket.OPEN);
  assert.equal(relay.deviceCount(), 1);
});

test('MAX_DEVICES: a new phone beyond the cap gets relay_full / 4013; connected phones may reconnect', async (t) => {
  const relay = await startRelay(t, { maxDevices: 2 });
  const first = await connectReady(relay);
  await connectReady(relay);
  assert.equal(relay.deviceCount(), 2);

  const third = await connectDevice(relay);
  const error = await third.nextFrame('error');
  assert.equal(error.code, 'relay_full');
  assert.match(String(error.message), /full/);
  assert.equal((await third.closed).code, 4013);
  assert.equal(relay.deviceCount(), 2);

  // A phone that is already connected replaces its own connection: that never counts as a new phone.
  const again = await connectReady(relay, { identity: first.identity });
  assert.equal((await first.closed).code, 4009);
  assert.equal(relay.deviceCount(), 2);

  // Once a phone leaves, the next one gets in.
  again.ws.close();
  await waitFor(() => relay.deviceCount() === 1, 'device removal');
  await connectReady(relay, { identity: third.identity });
  assert.equal(relay.deviceCount(), 2);
});

test('MAX_DEVICES is checked after authentication: strangers still get access_key / 4001', async (t) => {
  const relay = await startRelay(t, { maxDevices: 1, accessKey: 'k' });
  await connectReady(relay, { hello: { accessKey: 'k' } });
  const stranger = await connectDevice(relay, { hello: { accessKey: 'wrong' } });
  assert.equal((await stranger.nextFrame('error')).code, 'access_key');
  assert.equal((await stranger.closed).code, 4001);
});

test('an IP blocked for repeated failed hellos cannot open new device sockets (429)', async (t) => {
  const relay = await startRelay(t, { rateLimitAuthFailuresPerMin: 1 });
  for (let i = 0; i < 2; i++) {
    const device = await connectDevice(relay, { hello: { deviceId: newIdentity().deviceId } });
    assert.equal((await device.closed).code, 4001);
  }
  const ws = new WebSocket(`${relay.url.replace('http', 'ws')}/v1/device`);
  const status = await new Promise<number>((resolve) => {
    ws.on('unexpected-response', (_req, res) => resolve(res.statusCode ?? 0));
    ws.on('error', () => resolve(-1));
  });
  assert.equal(status, 429);
});
