/**
 * What unauthenticated device sockets may cost (DOS-1): concurrent handshakes per IP and relay-wide,
 * bytes before the hello, and how long a refused socket may linger. Large frames stay possible
 * after the hello.
 */
import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { once } from 'node:events';
import { connect, type Socket } from 'node:net';
import { PassThrough } from 'node:stream';
import { test } from 'node:test';
import WebSocket from 'ws';
import { HandshakeGuard, type HandshakeSlot } from '../src/handshakeGuard.js';
import { MAX_PRE_HELLO_BYTES } from '../src/protocol.js';
import type { Relay } from '../src/relay.js';
import { connectDevice, connectReady, postMcp, startRelay, tokenFor, type FakeDevice } from './helpers.js';

function withTimeout<T>(promise: Promise<T>, what: string, ms = 5_000): Promise<T> {
  let timer: NodeJS.Timeout | undefined;
  const timeout = new Promise<never>((_, reject) => {
    timer = setTimeout(() => reject(new Error(`timed out waiting for ${what}`)), ms);
  });
  return Promise.race([promise, timeout]).finally(() => clearTimeout(timer));
}

/**
 * Completes the WebSocket upgrade on a raw TCP socket, so the test controls every byte. [rest] is
 * what arrived after the 101 response.
 */
async function rawUpgrade(relay: Relay): Promise<{ socket: Socket; rest: Buffer }> {
  const { port } = relay.address;
  const socket = connect(port, '127.0.0.1');
  socket.on('error', () => {
    /* the relay may reset the connection; observed through 'close' */
  });
  await once(socket, 'connect');
  socket.write(
    `GET /v1/device HTTP/1.1\r\nHost: 127.0.0.1:${port}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n` +
      `Sec-WebSocket-Key: ${randomBytes(16).toString('base64')}\r\nSec-WebSocket-Version: 13\r\n\r\n`,
  );
  const received = await withTimeout(
    new Promise<Buffer>((resolve) => {
      let data = Buffer.alloc(0);
      const onData = (chunk: Buffer): void => {
        data = Buffer.concat([data, chunk]);
        if (!data.includes('\r\n\r\n')) return;
        socket.off('data', onData);
        resolve(data);
      };
      socket.on('data', onData);
    }),
    'the upgrade response',
  );
  const end = received.indexOf('\r\n\r\n') + 4;
  assert.match(received.subarray(0, end).toString('latin1'), /^HTTP\/1\.1 101 /);
  return { socket, rest: received.subarray(end) };
}

/** Resolves when [socket] closes, however (events.once would reject on a reset). */
function closed(socket: Socket): Promise<void> {
  return new Promise((resolve) => socket.once('close', () => resolve()));
}

/** Header of a masked client text frame that declares [length] payload bytes. */
function frameHeader(length: number): Buffer {
  const header = Buffer.alloc(14);
  header[0] = 0x81; // FIN, text
  header[1] = 0x80 | 127; // masked, 64-bit length
  header.writeBigUInt64BE(BigInt(length), 2);
  randomBytes(4).copy(header, 10);
  return header;
}

/** HTTP status of a device upgrade attempt (101 when it succeeds; the socket is then dropped). */
function upgradeStatus(relay: Relay): Promise<number> {
  const ws = new WebSocket(`${relay.url.replace(/^http/, 'ws')}/v1/device`);
  return new Promise((resolve) => {
    ws.on('open', () => {
      ws.terminate();
      resolve(101);
    });
    ws.on('unexpected-response', (_req, res) => resolve(res.statusCode ?? 0));
    ws.on('error', () => resolve(-1));
  });
}

function sendHello(device: FakeDevice): void {
  device.send({ type: 'hello', protocolVersion: 1, deviceId: device.identity.deviceId, deviceSecret: device.identity.secret });
}

/** Retries [attempt] until it resolves, for slots the relay frees once it sees a socket close. */
async function eventually<T>(attempt: () => Promise<T>, what: string): Promise<T> {
  const deadline = Date.now() + 5_000;
  for (;;) {
    try {
      return await attempt();
    } catch (err) {
      if (Date.now() > deadline) throw new Error(`${what}: ${String(err)}`);
      await new Promise((r) => setTimeout(r, 10));
    }
  }
}

test('HandshakeGuard: per-key and global caps; slots free on release or close, once', () => {
  const guard = new HandshakeGuard({ maxPending: 3, maxPendingPerKey: 2, maxBytes: 10 });
  const a1 = guard.admit('a', new PassThrough());
  const a2Socket = new PassThrough();
  const a2 = guard.admit('a', a2Socket);
  assert.equal(guard.admit('a', new PassThrough()), 'key_limit');
  const b1 = guard.admit('b', new PassThrough());
  assert.equal(guard.admit('c', new PassThrough()), 'global_limit');
  assert.equal(guard.pending, 3);

  (a1 as HandshakeSlot).release();
  (a1 as HandshakeSlot).release();
  assert.equal(guard.pending, 2, 'release is idempotent');
  a2Socket.emit('close');
  (a2 as HandshakeSlot).release();
  assert.equal(guard.pending, 1, 'a close frees the slot, once');
  assert.notEqual(guard.admit('a', new PassThrough()), 'key_limit');
  assert.equal(typeof b1, 'object');
});

test('HandshakeGuard: bytes beyond the limit destroy the socket; a released slot stops counting', () => {
  const guard = new HandshakeGuard({ maxPending: 10, maxPendingPerKey: 10, maxBytes: 10 });
  const socket = new PassThrough();
  const slot = guard.admit('a', socket) as HandshakeSlot;
  let exceeded = 0;
  slot.limitBytes(() => exceeded++);
  socket.emit('data', Buffer.alloc(10));
  assert.equal(socket.destroyed, false, 'up to the limit is fine');
  socket.emit('data', Buffer.alloc(1));
  assert.equal(socket.destroyed, true);
  assert.equal(exceeded, 1);
  assert.equal(guard.pending, 0);

  const authed = new PassThrough();
  const authedSlot = guard.admit('a', authed) as HandshakeSlot;
  authedSlot.limitBytes(() => exceeded++);
  authedSlot.release();
  authed.emit('data', Buffer.alloc(1_000));
  assert.equal(authed.destroyed, false, 'no limit after the hello');
  assert.equal(exceeded, 1);

  // The WebSocket reads each chunk first and may accept the hello in it; the rest of that chunk is free.
  const sameChunk = new PassThrough();
  const sameChunkSlot = guard.admit('a', sameChunk) as HandshakeSlot;
  sameChunk.on('data', () => sameChunkSlot.release());
  sameChunkSlot.limitBytes(() => exceeded++);
  sameChunk.emit('data', Buffer.alloc(1_000));
  assert.equal(sameChunk.destroyed, false);
  assert.equal(exceeded, 1);
});

test('a socket that declares a huge frame before its hello is dropped after 16 KiB', async (t) => {
  const relay = await startRelay(t, { helloTimeoutMs: 60_000 });
  const { socket } = await rawUpgrade(relay);
  const dropped = closed(socket);
  socket.write(frameHeader(15 * 1024 * 1024));
  socket.write(Buffer.alloc(MAX_PRE_HELLO_BYTES));
  socket.write(Buffer.alloc(1024));
  await withTimeout(dropped, 'the relay to drop the socket');

  // The relay is fine and its handshake slot is free again.
  const device = await connectReady(relay);
  assert.equal(relay.deviceCount(), 1);
  device.ws.close();
});

test('after the hello, frames far larger than the pre-hello limit are accepted (e.g. screenshots)', async (t) => {
  const relay = await startRelay(t);
  const device = await connectReady(relay);
  const pending = postMcp(relay, { token: tokenFor(device.identity.deviceId) });
  const request = await device.nextRequest();
  const body = JSON.stringify({ jsonrpc: '2.0', id: 1, result: { data: 'x'.repeat(4 * 1024 * 1024) } });
  device.respond(request.id, 200, body, { 'content-type': 'application/json' });
  const res = await pending;
  assert.equal(res.status, 200);
  assert.equal(res.body.length, body.length);
});

test('at most MAX_PENDING_HANDSHAKES_PER_IP sockets per IP may wait for their hello', async (t) => {
  const relay = await startRelay(t, { maxPendingHandshakesPerIp: 2, helloTimeoutMs: 60_000 });
  const first = await connectDevice(relay, { rawHello: null });
  const second = await connectDevice(relay, { rawHello: null });
  assert.equal(await upgradeStatus(relay), 429);

  // An accepted hello frees its slot; authenticated devices do not count.
  sendHello(second);
  await second.nextFrame('welcome');
  const third = await connectDevice(relay, { rawHello: null });
  assert.equal(await upgradeStatus(relay), 429);

  // So does a socket that goes away.
  first.ws.terminate();
  await eventually(() => connectReady(relay), 'a slot after a close');
  assert.equal(relay.deviceCount(), 2);
  third.ws.terminate();
});

test('at most MAX_PENDING_HANDSHAKES sockets relay-wide may wait for their hello', async (t) => {
  const relay = await startRelay(t, { maxPendingHandshakes: 1, helloTimeoutMs: 60_000 });
  const waiting = await connectDevice(relay, { rawHello: null });
  assert.equal(await upgradeStatus(relay), 503);
  sendHello(waiting);
  await waiting.nextFrame('welcome');
  await connectReady(relay);
  assert.equal(relay.deviceCount(), 2);
});

test('a socket that ignores the close handshake after a hello timeout is dropped within seconds', async (t) => {
  const relay = await startRelay(t, { helloTimeoutMs: 50 });
  const { socket, rest } = await rawUpgrade(relay);
  const received: Buffer[] = [rest];
  socket.on('data', (chunk: Buffer) => received.push(chunk));
  const started = Date.now();
  await withTimeout(closed(socket), 'the relay to drop the socket');
  assert.ok(Date.now() - started < 4_000, 'well before ws’s own 30 s close timeout');
  const frame = Buffer.concat(received);
  assert.equal(frame[0], 0x88, 'a close frame was sent first');
  assert.equal(frame.readUInt16BE(2), 4008);
});
