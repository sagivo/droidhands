/**
 * PairingStore state that relays without long-lived memory (a Cloudflare Durable Object) persist:
 * snapshot/restore, the next deadline to wake up for, and routable opaque values.
 */
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createLogger } from '../src/logger.js';
import type { RelayPairFrame } from '../src/oauth/pairingFrames.js';
import { PairingStore, type AuthorizationRequest } from '../src/oauth/pairingStore.js';
import { newIdentity, tokenFor } from './helpers.js';

function store(clock: { now: number }, sent: RelayPairFrame[], channel: { conn: string }) {
  return new PairingStore({
    ttlMs: 180_000,
    codeTtlMs: 120_000,
    retainMs: 600_000,
    now: () => clock.now,
    logger: createLogger('silent'),
    deliver: (_deviceId, frame) => {
      sent.push(frame);
      return channel;
    },
    newPageToken: (deviceId) => `${deviceId}PAGE`,
    newCode: (deviceId) => `${deviceId}CODE`,
  });
}

function request(deviceId: string): AuthorizationRequest {
  return {
    deviceId,
    clientId: 'client',
    clientName: 'Agent',
    redirectUri: 'https://agent.example/cb',
    redirectUriProvided: true,
    codeChallenge: 'challenge',
    state: 'st',
    issuer: 'https://relay.example',
  };
}

const origin = { ipKey: '203.0.113.7', remoteAddress: '203.0.113.7', userAgent: 'UA' };

test('a restored store continues a pairing: poll, approval, code exchange, notices', () => {
  const clock = { now: 1_000_000 };
  const sent: RelayPairFrame[] = [];
  const channel = { conn: 'c1' };
  const a = store(clock, sent, channel);
  const { deviceId } = newIdentity();
  const created = a.create(request(deviceId), origin);
  assert.ok(created.ok);
  assert.equal(created.pairing.pageToken, `${deviceId}PAGE`);
  assert.equal(a.nextDeadline(), 1_000_000 + 180_000);

  const snapshot = JSON.parse(JSON.stringify(a.snapshot((c) => (c as { conn: string }).conn)));
  const restoredChannels = new Map<string, object>();
  const b = store(clock, sent, channel);
  b.restore(snapshot, (key) => {
    const c = restoredChannels.get(key) ?? { conn: key };
    restoredChannels.set(key, c);
    return c;
  });
  assert.equal(b.poll(created.pairing.id, `${deviceId}PAGE`)?.status, 'pending');
  assert.equal(b.poll(created.pairing.id, 'wrong'), null);

  clock.now += 1_000;
  const token = tokenFor(deviceId);
  const tokenId = token.split('.')[2] as string;
  b.handleResponse(deviceId, { type: 'pair_response', id: created.pairing.id, approved: true, accessToken: token, tokenId });
  const approved = b.poll(created.pairing.id, `${deviceId}PAGE`);
  assert.equal(approved?.status, 'approved');
  assert.equal(new URL(approved.redirect as string).searchParams.get('code'), `${deviceId}CODE`);
  assert.equal(b.nextDeadline(), clock.now + 120_000);

  const c = store(clock, sent, channel);
  c.restore(JSON.parse(JSON.stringify(b.snapshot((ch) => (ch as { conn: string }).conn))), (key) => ({ conn: key }));
  const grant = c.takeCode(`${deviceId}CODE`);
  assert.equal(grant?.accessToken, token);
  c.finishExchange(grant!, true);
  assert.deepEqual(sent.at(-1), { type: 'pair_complete', id: created.pairing.id, tokenId });
  assert.equal(c.takeCode(`${deviceId}CODE`), null);
});

test('restored channels keep identity semantics: closing the old connection expires its pairings', () => {
  const clock = { now: 5_000_000 };
  const sent: RelayPairFrame[] = [];
  const a = store(clock, sent, { conn: 'old' });
  const { deviceId } = newIdentity();
  const created = a.create(request(deviceId), origin);
  assert.ok(created.ok);
  const channels = new Map<string, object>();
  const channelFor = (key: string): object => {
    const existing = channels.get(key);
    if (existing !== undefined) return existing;
    const made = { conn: key };
    channels.set(key, made);
    return made;
  };
  const b = store(clock, sent, { conn: 'new' });
  b.restore(a.snapshot((c) => (c as { conn: string }).conn), channelFor);
  b.connectionClosed(channelFor('other'));
  assert.equal(b.poll(created.pairing.id, `${deviceId}PAGE`)?.status, 'pending');
  b.connectionClosed(channelFor('old'));
  assert.equal(b.poll(created.pairing.id, `${deviceId}PAGE`)?.status, 'expired');
  assert.equal(b.nextDeadline(), clock.now + 600_000);
});
