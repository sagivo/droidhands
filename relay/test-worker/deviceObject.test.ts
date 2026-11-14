/**
 * Durable Object behavior of the Worker relay that the black-box conformance suite cannot reach:
 * state restored after eviction (hibernation attachments, encrypted pairing storage), alarms,
 * heartbeats, replacement and limits.
 */
import { SELF, evictDurableObject, runDurableObjectAlarm, runInDurableObject } from 'cloudflare:test';
import { describe, expect, it } from 'vitest';
import { ConfigError } from '../src/config.js';
import { unsupportedSettings, workerConfig, type Env } from '../src/worker/config.js';
import {
  BASE,
  REDIRECT_URI,
  authorize,
  connect,
  newIdentity,
  openSocket,
  pkce,
  pollStatus,
  postMcp,
  register,
  sleep,
  stub,
  tokenFor,
} from './helpers.js';

interface Attachment {
  state: string;
  deviceId: string;
  connectedAt: number;
  info: { deviceName: string | null } | null;
}

describe('device socket', () => {
  it('restores the connection from its attachment after the object is evicted', async () => {
    const device = await connect();
    const { deviceId } = device.identity;
    await evictDurableObject(stub(deviceId));

    // A fresh instance: no in-memory state, only the hibernated socket and its attachment.
    const attachments = await runInDurableObject(stub(deviceId), (_instance, state) =>
      state.getWebSockets().map((ws) => ws.deserializeAttachment() as Attachment),
    );
    expect(attachments).toHaveLength(1);
    expect(attachments[0]).toMatchObject({ state: 'open', deviceId, info: { deviceName: 'Test phone' } });

    device.send({ type: 'ping', t: 42 });
    expect(await device.next('pong')).toEqual({ type: 'pong', t: 42 });
    const pending = postMcp(deviceId);
    const request = await device.next<{ type: 'request'; id: string; body: string }>('request');
    device.send({ type: 'response', id: request.id, status: 200, headers: { 'content-type': 'application/json' }, body: request.body });
    const res = await pending;
    expect(res.status).toBe(200);
    expect(await res.text()).toBe('{"jsonrpc":"2.0","id":1,"method":"ping"}');
  });

  it('answers the exact heartbeat frame without the object and records it', async () => {
    const device = await connect();
    device.send('{"type":"hb"}');
    expect(await device.next('hb_ack')).toEqual({ type: 'hb_ack' });
    const seen = await runInDurableObject(stub(device.identity.deviceId), (_instance, state) => {
      const [ws] = state.getWebSockets();
      return ws === undefined ? null : state.getWebSocketAutoResponseTimestamp(ws);
    });
    expect(seen).toBeInstanceOf(Date);
    // A variant is answered by the object itself.
    device.send('{ "type": "hb" }');
    expect(await device.next('hb_ack')).toEqual({ type: 'hb_ack' });
  });

  it('treats a heartbeating device that went silent as offline (503) and drops it', async () => {
    const device = await connect();
    device.send('{"type":"hb"}');
    await device.next('hb_ack');
    await sleep(600); // HEARTBEAT_TIMEOUT_MS is 400 in the test config.
    const res = await postMcp(device.identity.deviceId);
    expect(res.status).toBe(503);
    expect(await res.json()).toEqual({ jsonrpc: '2.0', id: null, error: { code: -32000, message: 'Device offline' } });
    expect((await device.closed).code).toBe(1001);
  });

  it('closes a socket that sends no hello with 4008 from the alarm', async () => {
    const identity = newIdentity();
    const socket = await openSocket(identity);
    await sleep(350);
    await runDurableObjectAlarm(stub(identity.deviceId));
    expect((await socket.closed).code).toBe(4008);
  });

  it('drops a socket that sends more than 16 KiB before its hello', async () => {
    const socket = await openSocket();
    socket.send(`{"type":"hello","pad":"${'x'.repeat(17 * 1024)}"}`);
    expect((await socket.closed).code).toBe(1009);
  });

  it('caps sockets waiting for their hello per device and client address (429)', async () => {
    const identity = newIdentity();
    await openSocket(identity);
    await openSocket(identity);
    await expect(openSocket(identity)).rejects.toThrow(/429/);
  });

  it('a newer connection replaces the old one with 4009 and fails its pending request with 502', async () => {
    const identity = newIdentity();
    const first = await connect(identity);
    const pending = postMcp(identity.deviceId);
    await first.next('request');
    const second = await connect(identity);
    expect((await first.closed).code).toBe(4009);
    const res = await pending;
    expect(res.status).toBe(502);
    expect(((await res.json()) as { error: { message: string } }).error.message).toMatch(/may already have performed/);
    second.ws.close();
  });

  it('caps requests in flight per device (429)', async () => {
    const device = await connect();
    const { deviceId } = device.identity;
    const held = [postMcp(deviceId), postMcp(deviceId)];
    const first = await device.next<{ id: string }>('request');
    const second = await device.next<{ id: string }>('request');
    const over = await postMcp(deviceId);
    expect(over.status).toBe(429);
    expect(over.headers.get('retry-after')).toBe('1');
    for (const r of [first, second]) device.send({ type: 'response', id: r.id, status: 200, headers: {}, body: '{}' });
    expect((await Promise.all(held)).map((r) => r.status)).toEqual([200, 200]);
  });

  it('refuses a device URL without deviceId (4002, update the app) or with a malformed one (4000)', async () => {
    const missing = await openSocket(newIdentity(), null);
    const error = await missing.next<{ code: string; message: string }>('error');
    expect(error.code).toBe('protocol_version');
    expect(error.message).toMatch(/Update the PhoneBridge app/);
    expect((await missing.closed).code).toBe(4002);
    const malformed = await openSocket(newIdentity(), 'deviceId=NOT-AN-ID');
    expect((await malformed.next<{ code: string }>('error')).code).toBe('bad_request');
    expect((await malformed.closed).code).toBe(4000);
  });
});

describe('pairing state', () => {
  it('survives eviction between the prompt and the phone answer, and is encrypted at rest', async () => {
    const device = await connect();
    const { deviceId } = device.identity;
    const clientId = await register();
    const { verifier, challenge } = pkce();
    const pollUrl = await authorize(deviceId, clientId, challenge);
    const prompt = await device.next<{ id: string }>('pair_request');
    expect(new URL(pollUrl).searchParams.get('t')?.startsWith(deviceId)).toBe(true);

    await evictDurableObject(stub(deviceId));
    const accessToken = tokenFor(deviceId);
    const tokenId = accessToken.split('.')[2];
    device.send({ type: 'pair_response', id: prompt.id, approved: true, accessToken, tokenId });
    let status = await pollStatus(pollUrl);
    for (let i = 0; i < 50 && status.status === 'pending'; i++) {
      await sleep(20);
      status = await pollStatus(pollUrl);
    }
    expect(status.status).toBe('approved');

    const stored = await runInDurableObject(stub(deviceId), (_instance, state) => state.storage.kv.get<string>('pairings'));
    expect(stored).toMatch(/^v1\./);
    expect(stored).not.toContain(tokenId);
    expect(stored).not.toContain(accessToken.split('.')[3]);

    await evictDurableObject(stub(deviceId));
    const code = new URL(status.redirect as string).searchParams.get('code') as string;
    expect(code.startsWith(deviceId)).toBe(true);
    const res = await SELF.fetch(`${BASE}/oauth/token`, {
      method: 'POST',
      headers: { 'content-type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ grant_type: 'authorization_code', code, client_id: clientId, redirect_uri: REDIRECT_URI, code_verifier: verifier }),
    });
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ access_token: accessToken, token_type: 'Bearer', scope: 'phone' });
    expect(await device.next('pair_complete')).toEqual({ type: 'pair_complete', id: prompt.id, tokenId });
  });

  it('expires an unanswered pairing from the alarm (pair_cancel), without any poll', async () => {
    const device = await connect();
    const { deviceId } = device.identity;
    const { challenge } = pkce();
    await authorize(deviceId, await register(), challenge);
    const prompt = await device.next<{ id: string }>('pair_request');
    await evictDurableObject(stub(deviceId));
    await sleep(1_600); // PAIRING_TTL_MS is 1500.
    await runDurableObjectAlarm(stub(deviceId));
    expect(await device.next('pair_cancel')).toEqual({ type: 'pair_cancel', id: prompt.id });
    const status = await runInDurableObject(stub(deviceId), (instance) => instance.status());
    expect(status.online).toBe(true);
  });

  it('abandons a code nobody exchanged from the alarm armed at approval', async () => {
    const device = await connect();
    const { deviceId } = device.identity;
    await authorize(deviceId, await register(), pkce().challenge);
    const prompt = await device.next<{ id: string }>('pair_request');
    const accessToken = tokenFor(deviceId);
    const tokenId = accessToken.split('.')[2];
    device.send({ type: 'pair_response', id: prompt.id, approved: true, accessToken, tokenId });
    await device.next('pair_request', 200).catch(() => undefined); // let the approval land
    const alarm = await runInDurableObject(stub(deviceId), (_instance, state) => state.storage.getAlarm());
    expect(alarm).not.toBeNull();
    await evictDurableObject(stub(deviceId));
    await sleep(1_600); // AUTH_CODE_TTL_MS is 1500.
    await runDurableObjectAlarm(stub(deviceId));
    expect(await device.next('pair_abandoned')).toEqual({ type: 'pair_abandoned', id: prompt.id, tokenId });
  });

  it('discards pairing state it cannot decrypt', async () => {
    const device = await connect();
    const { deviceId } = device.identity;
    await authorize(deviceId, await register(), pkce().challenge);
    await device.next('pair_request');
    await runInDurableObject(stub(deviceId), (_instance, state) => {
      state.storage.kv.put('pairings', 'v1.AAAAAAAAAAAAAAAA.BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB');
    });
    await evictDurableObject(stub(deviceId));
    const status = await runInDurableObject(stub(deviceId), (instance) => instance.status());
    expect(status.pairings).toBe(0);
  });
});

describe('configuration', () => {
  it('refuses to serve without RELAY_SECRET, with a clear error', () => {
    const fake = {} as Env;
    expect(() => workerConfig(fake)).toThrow(ConfigError);
    expect(() => workerConfig(fake)).toThrow(/RELAY_SECRET must be set.*wrangler secret put RELAY_SECRET/);
  });

  it('validates values like the Node relay and reads the Worker-only ones', () => {
    expect(() => workerConfig({ RELAY_SECRET: 'short' } as unknown as Env)).toThrow(/at least 32 characters/);
    expect(() => workerConfig({ RELAY_SECRET: 'x'.repeat(40), REQUEST_TIMEOUT_MS: 'soon' } as unknown as Env)).toThrow(
      /REQUEST_TIMEOUT_MS/,
    );
    expect(() => workerConfig({ RELAY_SECRET: 'x'.repeat(40), APK_URL: 'ftp://x' } as unknown as Env)).toThrow(/APK_URL/);
    // The same APK_URL rules as the Node relay: no credentials or fragment.
    expect(() => workerConfig({ RELAY_SECRET: 'x'.repeat(40), APK_URL: 'https://u:p@example.com/a.apk' } as unknown as Env)).toThrow(
      /APK_URL/,
    );
    const cfg = workerConfig({
      RELAY_SECRET: 'x'.repeat(40),
      PUBLIC_URL: 'https://relay.example.com/pb/',
      APK_URL: 'https://github.com/o/r/releases/latest/download/phonebridge.apk',
      TRUST_PROXY: 'true',
      MAX_DEVICES: 'lots',
    } as unknown as Env);
    expect(cfg.relay.publicUrl).toBe('https://relay.example.com/pb');
    expect(cfg.prefix).toBe('/pb');
    expect(cfg.apkUrl).toBe('https://github.com/o/r/releases/latest/download/phonebridge.apk');
    expect(cfg.relay.trustProxy).toBe(false);
    // MAX_DEVICES can't be enforced across Durable Objects: ignored (even when invalid) and reported.
    expect(cfg.relay.maxDevices).toBe(1000);
    expect(unsupportedSettings({ TRUST_PROXY: 'true', MAX_DEVICES: '5' } as unknown as Env)).toEqual(['TRUST_PROXY', 'MAX_DEVICES']);
    expect(cfg.heartbeatTimeoutMs).toBe(75_000);
  });

  it('the setup page links APK_URL and /download/phonebridge.apk forwards to it', async () => {
    // The test Worker has no APK_URL: no install button and no download.
    const page = await (await SELF.fetch(`${BASE}/setup`)).text();
    expect(page).toContain('phonebridge://configure?relay=');
    expect(page).not.toContain('Install PhoneBridge</a>');
    expect((await SELF.fetch(`${BASE}/download/phonebridge.apk`)).status).toBe(404);
  });
});
