/**
 * Helpers for the Worker tests (they run inside workerd): device identities, a device socket
 * opened through the Worker, MCP and OAuth requests.
 */
import { createHash, randomBytes } from 'node:crypto';
import { SELF, env as testEnv } from 'cloudflare:test';
import type { Env } from '../src/worker/config.js';
import type { DeviceObject } from '../src/worker/deviceObject.js';

export const BASE = 'http://relay.test';
export const env = testEnv as unknown as Env;

export interface Identity {
  readonly secret: string;
  readonly deviceId: string;
}

export function newIdentity(): Identity {
  const secret = randomBytes(32);
  const digest = createHash('sha256').update(secret).digest().subarray(0, 16);
  const alphabet = 'abcdefghijklmnopqrstuvwxyz234567';
  let bits = '';
  for (const byte of digest) bits += byte.toString(2).padStart(8, '0');
  let deviceId = '';
  for (let i = 0; i < bits.length; i += 5) deviceId += alphabet[parseInt(bits.slice(i, i + 5).padEnd(5, '0'), 2)];
  return { secret: secret.toString('base64url'), deviceId };
}

export function tokenFor(deviceId: string): string {
  return `pb1.${deviceId}.${newIdentity().deviceId.slice(0, 12)}.${randomBytes(32).toString('base64url')}`;
}

export function stub(deviceId: string): DurableObjectStub<DeviceObject> {
  return env.DEVICES.getByName(deviceId);
}

export type Frame = Record<string, unknown> & { type: string };

/** The test's end of a device WebSocket. */
export class Socket {
  readonly frames: Frame[] = [];
  readonly closed: Promise<{ code: number; reason: string }>;
  private readonly waiters: Array<{ type: string; resolve: (f: Frame) => void }> = [];

  constructor(
    readonly ws: WebSocket,
    readonly identity: Identity,
  ) {
    this.closed = new Promise((resolve) => ws.addEventListener('close', (e) => resolve({ code: e.code, reason: e.reason })));
    ws.addEventListener('message', (e) => {
      const frame = JSON.parse(String(e.data)) as Frame;
      const index = this.waiters.findIndex((w) => w.type === frame.type);
      if (index >= 0) this.waiters.splice(index, 1)[0]?.resolve(frame);
      else this.frames.push(frame);
    });
  }

  next<T = Frame>(type: string, ms = 3_000): Promise<T> {
    const index = this.frames.findIndex((f) => f.type === type);
    if (index >= 0) return Promise.resolve(this.frames.splice(index, 1)[0] as T);
    return new Promise<T>((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error(`no "${type}" frame`)), ms);
      this.waiters.push({ type, resolve: (f) => (clearTimeout(timer), resolve(f as T)) });
    });
  }

  send(frame: unknown): void {
    this.ws.send(typeof frame === 'string' ? frame : JSON.stringify(frame));
  }

  hello(overrides: Record<string, unknown> = {}): void {
    this.send({
      type: 'hello',
      protocolVersion: 1,
      deviceId: this.identity.deviceId,
      deviceSecret: this.identity.secret,
      platform: 'vitest',
      appVersion: '0.0.1',
      deviceName: 'Test phone',
      ...overrides,
    });
  }
}

/** Upgrades `/v1/device?deviceId=…` through the Worker; [query] null omits the parameter. */
export async function openSocket(identity: Identity = newIdentity(), query: string | null = `deviceId=${identity.deviceId}`): Promise<Socket> {
  const res = await SELF.fetch(`${BASE}/v1/device${query === null ? '' : `?${query}`}`, { headers: { Upgrade: 'websocket' } });
  if (res.status !== 101 || res.webSocket === null) throw new Error(`upgrade refused: ${res.status}`);
  res.webSocket.accept();
  return new Socket(res.webSocket, identity);
}

/** A welcomed device. */
export async function connect(identity: Identity = newIdentity()): Promise<Socket> {
  const socket = await openSocket(identity);
  socket.hello();
  await socket.next('welcome');
  return socket;
}

export function postMcp(deviceId: string, token: string | null = tokenFor(deviceId), body = '{"jsonrpc":"2.0","id":1,"method":"ping"}'): Promise<Response> {
  return SELF.fetch(`${BASE}/d/${deviceId}/mcp`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', ...(token === null ? {} : { authorization: `Bearer ${token}` }) },
    body,
  });
}

export const REDIRECT_URI = 'http://localhost:33418/callback';

export async function register(): Promise<string> {
  const res = await SELF.fetch(`${BASE}/oauth/register`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ client_name: 'Vitest', redirect_uris: [REDIRECT_URI] }),
  });
  return ((await res.json()) as { client_id: string }).client_id;
}

export function pkce(): { verifier: string; challenge: string } {
  const verifier = randomBytes(32).toString('base64url');
  return { verifier, challenge: createHash('sha256').update(verifier).digest('base64url') };
}

/** Opens the authorize page; returns the absolute poll URL. */
export async function authorize(deviceId: string, clientId: string, challenge: string): Promise<string> {
  const url = new URL(`${BASE}/oauth/authorize`);
  for (const [k, v] of Object.entries({
    response_type: 'code',
    client_id: clientId,
    redirect_uri: REDIRECT_URI,
    code_challenge: challenge,
    code_challenge_method: 'S256',
    state: 's',
    resource: `${BASE}/d/${deviceId}/mcp`,
  })) {
    url.searchParams.set(k, v);
  }
  const res = await SELF.fetch(url);
  const html = await res.text();
  if (res.status !== 200) throw new Error(`authorize: ${res.status} ${html.slice(0, 300)}`);
  const poll = /data-poll="([^"]+)"/.exec(html)?.[1];
  if (poll === undefined) throw new Error('no poll URL');
  return new URL(poll.replace(/&amp;/g, '&'), BASE).toString();
}

export async function pollStatus(pollUrl: string): Promise<{ status: string; redirect?: string }> {
  return (await (await SELF.fetch(pollUrl)).json()) as { status: string; redirect?: string };
}

export function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
