/**
 * Protocol additions for relays that must route before the hello (Cloudflare Workers): the
 * `?deviceId=` routing hint on the device URL and the fixed `hb`/`hb_ack` heartbeat (§3.1, §3.4).
 */
import assert from 'node:assert/strict';
import { test } from 'node:test';
import WebSocket from 'ws';
import { checkHello } from '../src/handshake.js';
import { HEARTBEAT_ACK_FRAME, HEARTBEAT_FRAME } from '../src/protocol.js';
import { FakeDevice, newIdentity, startRelay, type Identity } from './helpers.js';
import type { Relay } from '../src/relay.js';

async function connectWithQuery(relay: Relay, identity: Identity, query: string): Promise<FakeDevice> {
  const ws = new WebSocket(`${relay.url.replace(/^http/, 'ws')}/v1/device${query}`);
  const device = new FakeDevice(identity, ws);
  await new Promise<void>((resolve, reject) => {
    ws.once('open', () => resolve());
    ws.once('error', reject);
  });
  device.send({ type: 'hello', protocolVersion: 1, deviceId: identity.deviceId, deviceSecret: identity.secret });
  return device;
}

test('the Node relay accepts a matching ?deviceId= and refuses a hello for another device (4000)', async (t) => {
  const relay = await startRelay(t);
  const identity = newIdentity();
  const ok = await connectWithQuery(relay, identity, `?deviceId=${identity.deviceId}`);
  assert.equal((await ok.nextFrame('welcome')).deviceId, identity.deviceId);

  const other = newIdentity();
  const wrong = await connectWithQuery(relay, other, `?deviceId=${identity.deviceId}`);
  const error = await wrong.nextFrame('error');
  assert.equal(error.code, 'bad_request');
  assert.equal((await wrong.closed).code, 4000);
  // The device that holds the id is still connected.
  assert.equal(relay.deviceCount(), 1);
});

test('without ?deviceId= the Node relay works as before', async (t) => {
  const relay = await startRelay(t);
  const identity = newIdentity();
  const device = await connectWithQuery(relay, identity, '');
  assert.equal((await device.nextFrame('welcome')).deviceId, identity.deviceId);
});

test('the heartbeat frame is answered with hb_ack', async (t) => {
  const relay = await startRelay(t);
  const identity = newIdentity();
  const device = await connectWithQuery(relay, identity, `?deviceId=${identity.deviceId}`);
  await device.nextFrame('welcome');
  assert.equal(HEARTBEAT_FRAME, '{"type":"hb"}');
  device.ws.send(HEARTBEAT_FRAME);
  assert.deepEqual(await device.nextFrame('hb_ack'), JSON.parse(HEARTBEAT_ACK_FRAME));
});

test('checkHello: order of checks and the URL device id', () => {
  const identity = newIdentity();
  const other = newIdentity();
  const hello = (fields: Record<string, unknown> = {}): string =>
    JSON.stringify({ type: 'hello', protocolVersion: 1, deviceId: identity.deviceId, deviceSecret: identity.secret, ...fields });
  const open = { accessKey: null, urlDeviceId: null };
  assert.deepEqual(checkHello(hello({ deviceName: ' Pixel‮ 8 ' }), open), {
    ok: true,
    deviceId: identity.deviceId,
    accountToken: null,
    info: { platform: undefined, appVersion: undefined, deviceName: 'Pixel 8' },
  });
  assert.equal(checkHello(hello(), { accessKey: null, urlDeviceId: identity.deviceId }).ok, true);
  const mismatch = checkHello(hello(), { accessKey: null, urlDeviceId: other.deviceId });
  assert.ok(!mismatch.ok);
  assert.equal(mismatch.code, 'bad_request');
  assert.equal(mismatch.closeCode, 4000);
  assert.equal(mismatch.authFailure, false);
  // A bad secret is unauthorized whatever the URL says.
  const forged = checkHello(hello({ deviceSecret: other.secret }), { accessKey: null, urlDeviceId: other.deviceId });
  assert.ok(!forged.ok);
  assert.equal(forged.code, 'unauthorized');
  assert.equal(forged.authFailure, true);
  const keyed = checkHello(hello({ accessKey: 'nope' }), { accessKey: 'k3y', urlDeviceId: identity.deviceId });
  assert.ok(!keyed.ok);
  assert.equal(keyed.code, 'access_key');
  assert.equal(keyed.closeCode, 4001);
  assert.equal(checkHello(null, open).ok, false);
});
