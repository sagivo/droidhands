/**
 * What a shared public relay must withstand: clients that name arbitrary device ids, sockets that
 * never finish their close handshake, oversized frames, and the device's object being reset while a
 * request is in flight.
 */
import { SELF, evictDurableObject, runDurableObjectAlarm, runInDurableObject } from 'cloudflare:test';
import { describe, expect, it } from 'vitest';
import type { PairingSnapshot } from '../src/oauth/pairingStore.js';
import { BASE, connect, newIdentity, openSocket, pkce, postMcp, register, sleep, stub, tokenFor } from './helpers.js';

const HOUR_MS = 3_600_000;

function storedPairings(deviceId: string): Promise<unknown> {
  return runInDurableObject(stub(deviceId), (_instance, state) => state.storage.kv.get('pairings'));
}

function alarmOf(deviceId: string): Promise<number | null> {
  return runInDurableObject(stub(deviceId), (_instance, state) => state.storage.getAlarm());
}

/** Writes [snapshot] the way the object stores it (sealed with its key), then evicts the object. */
async function storeSnapshot(deviceId: string, snapshot: PairingSnapshot): Promise<void> {
  await runInDurableObject(stub(deviceId), (instance, state) => {
    const sealed = (instance as unknown as { seal(plain: string): string }).seal(JSON.stringify(snapshot));
    state.storage.kv.put('pairings', sealed);
  });
  await evictDurableObject(stub(deviceId));
}

/** A poll with a well-formed but unknown pairing, routed to [deviceId]'s object by its page token. */
function forgedPoll(deviceId: string): Promise<Response> {
  return SELF.fetch(`${BASE}/oauth/pair/p_AAAAAAAAAAAAAAAAAAAAAA?t=${deviceId}AAAAAAAAAAAAAAAAAAAAAA`);
}

describe('unpaired device objects', () => {
  it('requests naming a device that never paired leave nothing in its storage', async () => {
    const deviceId = newIdentity().deviceId;
    expect((await forgedPoll(deviceId)).status).toBe(404);
    const token = await SELF.fetch(`${BASE}/oauth/token`, {
      method: 'POST',
      headers: { 'content-type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        grant_type: 'authorization_code',
        code: `${deviceId}${'A'.repeat(43)}`,
        client_id: await register(),
        code_verifier: pkce().verifier,
      }),
    });
    expect(token.status).toBe(400);
    expect((await postMcp(deviceId)).status).toBe(503);
    // An unauthenticated socket that never sends its hello.
    const socket = await openSocket({ deviceId, secret: '' });
    await sleep(350);
    await runDurableObjectAlarm(stub(deviceId));
    expect((await socket.closed).code).toBe(4008);

    expect(await storedPairings(deviceId)).toBeUndefined();
    expect(await alarmOf(deviceId)).toBeNull();
  });

  it('deletes stored pairing state once everything in it expired', async () => {
    const deviceId = newIdentity().deviceId;
    await storeSnapshot(deviceId, {
      v: 1,
      records: [],
      codes: [],
      history: [[`i:203.0.113.7`, [Date.now() - 2 * HOUR_MS]]],
      notices: [],
    });
    expect(await storedPairings(deviceId)).toMatch(/^v1\./);
    await forgedPoll(deviceId);
    expect(await storedPairings(deviceId)).toBeUndefined();
  });

  it('arms an alarm that sweeps leftover counters and notices after an hour', async () => {
    const deviceId = newIdentity().deviceId;
    const now = Date.now();
    await storeSnapshot(deviceId, {
      v: 1,
      records: [],
      codes: [],
      history: [[`d:${deviceId}`, [now - 60_000]]],
      notices: [[deviceId, [{ frame: { type: 'pair_complete', id: 'p_AAAAAAAAAAAAAAAAAAAAAA', tokenId: 'aaaaaaaaaaaa' }, queuedAt: now - 60_000 }]]],
    });
    await forgedPoll(deviceId);
    const alarm = await alarmOf(deviceId);
    expect(alarm).not.toBeNull();
    expect(alarm as number).toBeGreaterThan(now + HOUR_MS - 60_000);
    expect(alarm as number).toBeLessThan(now + HOUR_MS + 60_000);
    expect(await storedPairings(deviceId)).toMatch(/^v1\./);
  });
});

describe('token in the URL', () => {
  // The conformance runner turns ALLOW_TOKEN_IN_URL on; this checks the default.
  it('is off by default: /mcp/<token> is 404 and never reaches the phone', async () => {
    const device = await connect();
    const res = await SELF.fetch(`${BASE}/mcp/${tokenFor(device.identity.deviceId)}`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: '{"jsonrpc":"2.0","id":1,"method":"ping"}',
    });
    expect(res.status).toBe(404);
    await expect(device.next('request', 300)).rejects.toThrow(/no "request" frame/);
    device.ws.close();
  });
});

describe('device sockets', () => {
  it('a socket that never answers the close keeps counting toward the per-address cap', async () => {
    const deviceId = newIdentity().deviceId;
    const upgrade = (): Promise<Response> => SELF.fetch(`${BASE}/v1/device?deviceId=${deviceId}`, { headers: { Upgrade: 'websocket' } });
    // Two raw sockets (MAX_PENDING_HANDSHAKES_PER_IP is 2) whose client never reads anything. Kept
    // referenced: a collected client end would be released by the runtime.
    const held = [await upgrade(), await upgrade()];
    expect(held.map((r) => r.status)).toEqual([101, 101]);
    await sleep(350);
    await runDurableObjectAlarm(stub(deviceId));
    const states = await runInDurableObject(stub(deviceId), (_instance, state) =>
      state.getWebSockets().map((ws) => (ws.deserializeAttachment() as { state: string }).state),
    );
    // Closed with 4008 by the relay, but still held by the runtime.
    expect(states).toEqual(['closed', 'closed']);
    expect((await upgrade()).status).toBe(429);
    expect(held).toHaveLength(2);
  });

  it('connections that were welcomed do not count toward it once replaced', async () => {
    const identity = newIdentity();
    const first = await connect(identity);
    const second = await connect(identity);
    expect((await first.closed).code).toBe(4009);
    const third = await connect(identity);
    expect((await second.closed).code).toBe(4009);
    third.ws.close();
  });

  it('a request in flight when the object is reset fails with "may already have performed" (502)', async () => {
    const device = await connect();
    const { deviceId } = device.identity;
    const pending = postMcp(deviceId, tokenFor(deviceId));
    await device.next('request');
    await runInDurableObject(stub(deviceId), (_instance, state) => state.abort('test reset')).catch(() => undefined);
    const res = await pending;
    expect(res.status).toBe(502);
    expect(((await res.json()) as { error: { message: string } }).error.message).toMatch(/may already have performed/);
  });
});
