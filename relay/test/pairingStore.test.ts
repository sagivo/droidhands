import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createLogger } from '../src/logger.js';
import type { RelayPairFrame } from '../src/oauth/pairingFrames.js';
import { PairingStore, type AuthorizationRequest, type PairingLimits } from '../src/oauth/pairingStore.js';
import { newIdentity, tokenFor } from './helpers.js';

interface Harness {
  readonly store: PairingStore;
  readonly sent: Array<{ deviceId: string; frame: RelayPairFrame; channel: object }>;
  readonly online: Set<string>;
  advance(ms: number): void;
  /** Devices' frames go out on a new connection from now on; returns it. */
  reconnect(): object;
}

const CHANNEL = {};

function harness(limits: Partial<PairingLimits> = {}): Harness {
  let now = 1_000_000;
  let channel: object = CHANNEL;
  const sent: Harness['sent'] = [];
  const online = new Set<string>();
  const store = new PairingStore({
    ttlMs: 180_000,
    codeTtlMs: 120_000,
    retainMs: 600_000,
    limits,
    now: () => now,
    logger: createLogger('silent'),
    deliver: (deviceId, frame) => {
      if (!online.has(deviceId)) return null;
      sent.push({ deviceId, frame, channel });
      return channel;
    },
  });
  return { store, sent, online, advance: (ms) => (now += ms), reconnect: () => (channel = {}) };
}

function request(deviceId: string, overrides: Partial<AuthorizationRequest> = {}): AuthorizationRequest {
  return {
    deviceId,
    clientId: 'client',
    clientName: 'Agent',
    redirectUri: 'https://agent.example/cb',
    redirectUriProvided: true,
    codeChallenge: `c${Math.random()}`,
    state: 'st',
    issuer: 'https://relay.example',
    ...overrides,
  };
}

const origin = (ip = '203.0.113.7') => ({ ipKey: ip, remoteAddress: ip, userAgent: undefined });

function approve(h: Harness, deviceId: string, id: string): { token: string; tokenId: string } {
  const token = tokenFor(deviceId);
  const tokenId = token.split('.')[2] as string;
  h.store.handleResponse(deviceId, { type: 'pair_response', id, approved: true, accessToken: token, tokenId });
  return { token, tokenId };
}

test('create sends pair_request; offline devices are refused', () => {
  const h = harness();
  const { deviceId } = newIdentity();
  assert.deepEqual(h.store.create(request(deviceId), origin()), { ok: false, reason: 'offline' });
  h.online.add(deviceId);
  const result = h.store.create(request(deviceId), origin());
  assert.ok(result.ok);
  assert.equal(h.sent.length, 1);
  const frame = h.sent[0]?.frame;
  assert.equal(frame?.type, 'pair_request');
  assert.equal(frame?.type === 'pair_request' && frame.redirectHost, 'agent.example');
  assert.equal(frame?.type === 'pair_request' && frame.expiresAt, 1_000_000 + 180_000);
});

test('per-device and per-IP limits; a failed create does not consume the hourly budget', () => {
  const h = harness({ maxPendingPerIp: 4, maxPerIpPerHour: 6 });
  const a = newIdentity().deviceId;
  const b = newIdentity().deviceId;
  h.online.add(a).add(b);
  for (let i = 0; i < 3; i++) assert.ok(h.store.create(request(a), origin()).ok);
  assert.deepEqual(h.store.create(request(a), origin()), { ok: false, reason: 'device_busy' });
  assert.ok(h.store.create(request(b), origin()).ok);
  assert.deepEqual(h.store.create(request(b), origin()), { ok: false, reason: 'ip_busy' });
  assert.ok(h.store.create(request(b), origin('198.51.100.1')).ok, 'another IP is fine');

  // Let everything expire: pending slots free up, but the hourly IP budget (6) remains.
  h.advance(180_000);
  assert.ok(h.store.create(request(b), origin()).ok);
  assert.ok(h.store.create(request(b), origin()).ok);
  assert.deepEqual(h.store.create(request(a), origin()), { ok: false, reason: 'ip_hourly' });
  h.advance(3_600_000);
  assert.ok(h.store.create(request(a), origin()).ok, 'budgets reset after an hour');
});

test('20 pairings per device per hour', () => {
  const h = harness({ maxPerIpPerHour: 100 });
  const { deviceId } = newIdentity();
  h.online.add(deviceId);
  for (let i = 0; i < 20; i++) {
    const result = h.store.create(request(deviceId), origin());
    assert.ok(result.ok, `#${i}`);
    h.store.handleResponse(deviceId, { type: 'pair_response', id: result.pairing.id, approved: false, reason: 'denied' });
  }
  assert.deepEqual(h.store.create(request(deviceId), origin()), { ok: false, reason: 'device_hourly' });
});

test('relay-wide capacity bounds the number of tracked pairings', () => {
  const h = harness({ maxRecords: 2, maxPendingPerIp: 10, maxPerIpPerHour: 10 });
  const ids = [newIdentity().deviceId, newIdentity().deviceId, newIdentity().deviceId];
  for (const id of ids) h.online.add(id);
  assert.ok(h.store.create(request(ids[0] as string), origin()).ok);
  assert.ok(h.store.create(request(ids[1] as string), origin()).ok);
  assert.deepEqual(h.store.create(request(ids[2] as string), origin()), { ok: false, reason: 'capacity' });
  h.advance(180_000 + 600_000);
  h.store.sweep();
  assert.equal(h.store.size, 0, 'decided pairings are forgotten after the retention period');
});

test('expiry sends pair_cancel once and decides access_denied', () => {
  const h = harness();
  const { deviceId } = newIdentity();
  h.online.add(deviceId);
  const created = h.store.create(request(deviceId), origin());
  assert.ok(created.ok);
  h.advance(179_999);
  h.store.sweep();
  assert.equal(h.store.poll(created.pairing.id, created.pairing.pageToken)?.status, 'pending');
  h.advance(1);
  h.store.sweep();
  h.store.sweep();
  const cancels = h.sent.filter((s) => s.frame.type === 'pair_cancel');
  assert.deepEqual(cancels.map((s) => s.frame), [{ type: 'pair_cancel', id: created.pairing.id }]);
  const view = h.store.poll(created.pairing.id, created.pairing.pageToken);
  assert.equal(view?.status, 'expired');
  assert.match(view?.redirect ?? '', /^https:\/\/agent\.example\/cb\?error=access_denied&error_description=.+&state=st&iss=https%3A%2F%2Frelay\.example$/);
});

test('approval issues a single-use code bound to the request; unexchanged codes are abandoned', () => {
  const h = harness();
  const { deviceId } = newIdentity();
  h.online.add(deviceId);
  const created = h.store.create(request(deviceId), origin());
  assert.ok(created.ok);
  const { token, tokenId } = approve(h, deviceId, created.pairing.id);
  const view = h.store.poll(created.pairing.id, created.pairing.pageToken);
  assert.equal(view?.status, 'approved');
  const code = new URL(view?.redirect ?? '').searchParams.get('code') as string;
  assert.equal(h.store.codeCount, 1);

  const grant = h.store.takeCode(code);
  assert.equal(grant?.accessToken, token);
  assert.equal(grant?.tokenId, tokenId);
  assert.equal(h.store.takeCode(code), null, 'single use');
  h.store.finishExchange(grant!, true);
  assert.equal(grant?.accessToken, '', 'the token is dropped from memory');
  assert.deepEqual(h.sent.at(-1)?.frame, { type: 'pair_complete', id: created.pairing.id, tokenId });

  // A second pairing whose code is never exchanged.
  const other = h.store.create(request(deviceId), origin());
  assert.ok(other.ok);
  const second = approve(h, deviceId, other.pairing.id);
  h.advance(120_000);
  h.store.sweep();
  assert.deepEqual(h.sent.at(-1)?.frame, { type: 'pair_abandoned', id: other.pairing.id, tokenId: second.tokenId });
  assert.equal(h.store.codeCount, 0);
});

test('a duplicated approval does not abandon the token that is about to be collected', () => {
  const h = harness();
  const { deviceId } = newIdentity();
  h.online.add(deviceId);
  const created = h.store.create(request(deviceId), origin());
  assert.ok(created.ok);
  const token = tokenFor(deviceId);
  const tokenId = token.split('.')[2] as string;
  const frame = { type: 'pair_response', id: created.pairing.id, approved: true, accessToken: token, tokenId };
  h.store.handleResponse(deviceId, frame);
  const sent = h.sent.length;
  h.store.handleResponse(deviceId, frame);
  assert.equal(h.sent.length, sent, 'nothing sent for the duplicate');
  assert.equal(h.store.codeCount, 1);

  // A second, different token for the same pairing is abandoned.
  const other = tokenFor(deviceId);
  const otherId = other.split('.')[2] as string;
  h.store.handleResponse(deviceId, { ...frame, accessToken: other, tokenId: otherId });
  assert.deepEqual(h.sent.at(-1)?.frame, { type: 'pair_abandoned', id: created.pairing.id, tokenId: otherId });
});

test('an expired code presented to takeCode is abandoned, not returned', () => {
  const h = harness();
  const { deviceId } = newIdentity();
  h.online.add(deviceId);
  const created = h.store.create(request(deviceId), origin());
  assert.ok(created.ok);
  const { tokenId } = approve(h, deviceId, created.pairing.id);
  const code = new URL(h.store.poll(created.pairing.id, created.pairing.pageToken)?.redirect ?? '').searchParams.get('code') as string;
  h.advance(120_000);
  assert.equal(h.store.takeCode(code), null);
  assert.deepEqual(h.sent.at(-1)?.frame, { type: 'pair_abandoned', id: created.pairing.id, tokenId });
});

test('notices for offline devices are queued and delivered on reconnect (bounded, with TTL)', () => {
  const h = harness({ maxNoticesPerDevice: 2 });
  const { deviceId } = newIdentity();
  h.online.add(deviceId);
  const grants = [];
  for (let i = 0; i < 3; i++) {
    const created = h.store.create(request(deviceId), origin());
    assert.ok(created.ok);
    approve(h, deviceId, created.pairing.id);
    const code = new URL(h.store.poll(created.pairing.id, created.pairing.pageToken)?.redirect ?? '').searchParams.get('code');
    grants.push(h.store.takeCode(code as string));
  }
  h.online.delete(deviceId);
  for (const grant of grants) h.store.finishExchange(grant!, true);
  const before = h.sent.length;
  h.online.add(deviceId);
  h.store.deviceConnected(deviceId, CHANNEL);
  const delivered = h.sent.slice(before).map((s) => s.frame.type);
  assert.deepEqual(delivered, ['pair_complete', 'pair_complete'], 'oldest notice dropped beyond the cap');

  h.online.delete(deviceId);
  h.store.finishExchange({ ...grants[0]!, accessToken: '' }, false);
  h.advance(3_600_000);
  h.store.sweep();
  h.online.add(deviceId);
  const count = h.sent.length;
  h.store.deviceConnected(deviceId, CHANNEL);
  assert.equal(h.sent.length, count, 'stale notices are dropped');
});

test('a closed connection ends its pending pairings; responses for other devices are ignored', () => {
  const h = harness();
  const a = newIdentity().deviceId;
  const b = newIdentity().deviceId;
  h.online.add(a).add(b);
  const created = h.store.create(request(a), origin());
  assert.ok(created.ok);

  // Device b cannot answer device a's pairing, and its token is abandoned.
  const foreign = approve(h, b, created.pairing.id);
  assert.equal(h.store.poll(created.pairing.id, created.pairing.pageToken)?.status, 'pending');
  assert.deepEqual(h.sent.at(-1), {
    deviceId: b,
    frame: { type: 'pair_abandoned', id: created.pairing.id, tokenId: foreign.tokenId },
    channel: CHANNEL,
  });

  h.store.connectionClosed(CHANNEL);
  assert.equal(h.store.poll(created.pairing.id, created.pairing.pageToken)?.status, 'expired');
});

test('a reconnect re-sends pending pair_requests from an older connection, which then no longer expires them', () => {
  const h = harness();
  const { deviceId } = newIdentity();
  const other = newIdentity().deviceId;
  h.online.add(deviceId).add(other);
  const pending = h.store.create(request(deviceId), { ipKey: 'k', remoteAddress: '203.0.113.9', userAgent: 'Browser/1' });
  const denied = h.store.create(request(deviceId), origin());
  const foreign = h.store.create(request(other), origin('198.51.100.1'));
  assert.ok(pending.ok && denied.ok && foreign.ok);
  const original = h.sent.find((s) => s.frame.type === 'pair_request' && s.frame.id === pending.pairing.id)?.frame;
  h.store.handleResponse(deviceId, { type: 'pair_response', id: denied.pairing.id, approved: false, reason: 'denied' });

  // The phone reconnects while the relay still holds the old (possibly dead) connection.
  h.advance(60_000);
  const fresh = h.reconnect();
  const before = h.sent.length;
  h.store.deviceConnected(deviceId, fresh);
  assert.deepEqual(
    h.sent.slice(before),
    [{ deviceId, frame: original, channel: fresh }],
    'only the pending pairing of this device, unchanged (same id, code, origin and expiry)',
  );

  h.store.connectionClosed(CHANNEL);
  assert.equal(h.store.poll(pending.pairing.id, pending.pairing.pageToken)?.status, 'pending', 'the old close no longer ends it');
  assert.equal(h.store.poll(foreign.pairing.id, foreign.pairing.pageToken)?.status, 'expired', "another device's pairing still ends");

  const count = h.sent.length;
  h.store.deviceConnected(deviceId, fresh);
  assert.equal(h.sent.length, count, 'nothing is re-sent on the connection that already has it');

  h.store.connectionClosed(fresh);
  assert.equal(h.store.poll(pending.pairing.id, pending.pairing.pageToken)?.status, 'expired');
});

test('a reconnect does not re-send pairings that are past their expiry; they are cancelled', () => {
  const h = harness();
  const { deviceId } = newIdentity();
  h.online.add(deviceId);
  const created = h.store.create(request(deviceId), origin());
  assert.ok(created.ok);
  h.advance(180_000);
  const fresh = h.reconnect();
  const before = h.sent.length;
  h.store.deviceConnected(deviceId, fresh);
  assert.deepEqual(h.sent.slice(before).map((s) => s.frame), [{ type: 'pair_cancel', id: created.pairing.id }]);
  assert.equal(h.store.poll(created.pairing.id, created.pairing.pageToken)?.status, 'expired');
});

test('poll requires the page token', () => {
  const h = harness();
  const { deviceId } = newIdentity();
  h.online.add(deviceId);
  const created = h.store.create(request(deviceId), origin());
  assert.ok(created.ok);
  assert.equal(h.store.poll(created.pairing.id, 'wrong'), null);
  assert.equal(h.store.poll('p_unknown', created.pairing.pageToken), null);
});

test('shutdown cancels prompts, abandons codes and forgets everything', () => {
  const h = harness();
  const { deviceId } = newIdentity();
  h.online.add(deviceId);
  const pending = h.store.create(request(deviceId), origin());
  const approved = h.store.create(request(deviceId), origin());
  assert.ok(pending.ok && approved.ok);
  const { tokenId } = approve(h, deviceId, approved.pairing.id);
  h.store.shutdown();
  assert.deepEqual(
    h.sent.slice(-2).map((s) => s.frame),
    [
      { type: 'pair_cancel', id: pending.pairing.id },
      { type: 'pair_abandoned', id: approved.pairing.id, tokenId },
    ],
  );
  assert.equal(h.store.size, 0);
  assert.equal(h.store.codeCount, 0);
});
