import assert from 'node:assert/strict';
import { request } from 'node:http';
import { test } from 'node:test';
import { createRelay } from '../src/relay.js';
import { connectDevice, connectReady, httpRequest, postMcp, startRelay, testRelaySecret, tokenFor, waitFor } from './helpers.js';

test('a newer connection replaces the old one: old closed 4009, its pending request -> 502', async (t) => {
  const relay = await startRelay(t);
  const oldDevice = await connectReady(relay);
  const token = tokenFor(oldDevice.identity.deviceId);

  const pending = postMcp(relay, { token });
  await oldDevice.nextRequest();

  const newDevice = await connectReady(relay, { identity: oldDevice.identity });
  assert.equal((await oldDevice.closed).code, 4009);
  const res = await pending;
  assert.equal(res.status, 502);
  assert.equal((JSON.parse(res.body) as { error: { code: number } }).error.code, -32000);
  assert.equal(relay.deviceCount(), 1);

  // New requests go to the new connection, and the old socket's close did not unregister it.
  const next = postMcp(relay, { token });
  const request = await newDevice.nextRequest();
  newDevice.respond(request.id, 200, '{"from":"new"}', { 'content-type': 'application/json' });
  assert.equal((await next).body, '{"from":"new"}');
});

test('device disconnect mid-request -> 502, then 503 while offline', async (t) => {
  const relay = await startRelay(t);
  const device = await connectReady(relay);
  const token = tokenFor(device.identity.deviceId);

  const pending = postMcp(relay, { token });
  await device.nextRequest();
  device.ws.terminate();
  const res = await pending;
  assert.equal(res.status, 502);
  assert.equal((JSON.parse(res.body) as { error: { message: string } }).error.message, 'Device disconnected before responding');

  await waitFor(() => relay.deviceCount() === 0, 'device removal');
  assert.equal((await postMcp(relay, { token })).status, 503);
});

test('a clean device close also fails its pending requests with 502', async (t) => {
  const relay = await startRelay(t);
  const device = await connectReady(relay);
  const pending = postMcp(relay, { token: tokenFor(device.identity.deviceId) });
  await device.nextRequest();
  device.ws.close(1000, 'bye');
  assert.equal((await pending).status, 502);
});

test('an agent that disconnects does not break the device connection', async (t) => {
  const relay = await startRelay(t);
  const device = await connectReady(relay);
  const token = tokenFor(device.identity.deviceId);
  const req = request(`${relay.url}/mcp`, {
    method: 'POST',
    agent: false,
    headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json' },
  });
  req.on('error', () => {});
  req.end('{"jsonrpc":"2.0","id":1,"method":"ping"}');
  const tunneled = await device.nextRequest();
  req.destroy();
  await new Promise((r) => setTimeout(r, 20));
  device.respond(tunneled.id, 200, '{}', { 'content-type': 'application/json' });

  const next = postMcp(relay, { token });
  const second = await device.nextRequest();
  device.respond(second.id, 200, '{"still":"up"}', { 'content-type': 'application/json' });
  assert.equal((await next).body, '{"still":"up"}');
});

test('graceful shutdown: devices closed with 1001, pending requests -> 503', async () => {
  const relay = await createRelay({ port: 0, host: '127.0.0.1', logLevel: 'silent', relaySecret: testRelaySecret() });
  const device = await connectReady(relay);
  const idle = await connectDevice(relay, { rawHello: null });
  const pending = postMcp(relay, { token: tokenFor(device.identity.deviceId) });
  await device.nextRequest();

  const closing = relay.close();
  const res = await pending;
  assert.equal(res.status, 503);
  assert.ok(Number(res.headers['retry-after']) > 0);
  assert.equal((await device.closed).code, 1001);
  assert.equal((await idle.closed).code, 1001, 'sockets still in the handshake are closed too');
  await closing;
  await relay.close(); // idempotent
  assert.equal(relay.server.listening, false);
  await assert.rejects(httpRequest(`${relay.url}/healthz`));
});
