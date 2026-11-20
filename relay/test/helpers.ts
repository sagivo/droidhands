/**
 * Test helpers: a relay on an ephemeral port, a scriptable fake device speaking the tunnel
 * protocol over a real WebSocket, and a raw HTTP client with full control over headers.
 */
import { createHash, randomBytes } from 'node:crypto';
import { request, type IncomingHttpHeaders } from 'node:http';
import type { TestContext } from 'node:test';
import WebSocket from 'ws';
import { createRelay, type Relay, type RelayConfig, type RelayOptions } from '../src/relay.js';

export interface Identity {
  readonly secret: string;
  readonly deviceId: string;
}

/** Independent implementation of the PROTOCOL.md §1 derivation (not the relay's own code). */
export function identityFrom(secretBytes: Buffer): Identity {
  const digest = createHash('sha256').update(secretBytes).digest().subarray(0, 16);
  const alphabet = 'abcdefghijklmnopqrstuvwxyz234567';
  let bits = '';
  for (const byte of digest) bits += byte.toString(2).padStart(8, '0');
  let deviceId = '';
  for (let i = 0; i < bits.length; i += 5) deviceId += alphabet[parseInt(bits.slice(i, i + 5).padEnd(5, '0'), 2)];
  return { secret: secretBytes.toString('base64url'), deviceId };
}

export function newIdentity(): Identity {
  return identityFrom(randomBytes(32));
}

/** A well-formed agent token for [deviceId] (the relay never checks the secret part). */
export function tokenFor(deviceId: string): string {
  const tokenId = identityFrom(randomBytes(32)).deviceId.slice(0, 12);
  return `pb1.${deviceId}.${tokenId}.${randomBytes(32).toString('base64url')}`;
}

/** A random RELAY_SECRET, so tests never touch DATA_DIR. */
export function testRelaySecret(): string {
  return randomBytes(32).toString('base64url');
}

/** Starts a quiet relay on 127.0.0.1:0 and closes it when the test ends. */
export async function startRelay(t: TestContext, overrides: Partial<RelayConfig> = {}, options: RelayOptions = {}): Promise<Relay> {
  const relay = await createRelay(
    {
      port: 0,
      host: '127.0.0.1',
      logLevel: 'silent',
      relaySecret: testRelaySecret(),
      ...overrides,
    },
    options,
  );
  t.after(() => relay.close());
  return relay;
}

export type Frame = Record<string, unknown> & { type: string };

export interface RequestFrame extends Frame {
  type: 'request';
  id: string;
  method: string;
  path: string;
  headers: Record<string, string>;
  body: string;
  remoteAddress: string;
}

export interface CloseInfo {
  readonly code: number;
  readonly reason: string;
}

export interface ConnectOptions {
  readonly identity?: Identity;
  /** Fields merged into (and overriding) the default hello. */
  readonly hello?: Record<string, unknown>;
  /** Raw first frame instead of the hello; `null` sends nothing. */
  readonly rawHello?: string | null;
  /** Answer WebSocket pings automatically (default true). */
  readonly autoPong?: boolean;
  readonly headers?: Record<string, string>;
}

const WAIT_MS = 5_000;

/** A device that speaks the tunnel protocol and lets the test decide how to answer. */
export class FakeDevice {
  readonly closed: Promise<CloseInfo>;
  private readonly buffered: Frame[] = [];
  private readonly waiters: Array<{ type: string; resolve: (frame: Frame) => void }> = [];
  private handler: ((request: RequestFrame) => void) | null = null;

  constructor(
    readonly identity: Identity,
    readonly ws: WebSocket,
  ) {
    this.closed = new Promise((resolve) => {
      ws.on('close', (code, reason) => resolve({ code, reason: reason.toString() }));
    });
    ws.on('error', () => {
      /* surfaced through `closed` */
    });
    ws.on('message', (data, isBinary) => {
      if (isBinary) return;
      const frame = JSON.parse(String(data)) as Frame;
      if (frame.type === 'request' && this.handler !== null) {
        this.handler(frame as RequestFrame);
        return;
      }
      const index = this.waiters.findIndex((w) => w.type === frame.type);
      if (index >= 0) this.waiters.splice(index, 1)[0]?.resolve(frame);
      else this.buffered.push(frame);
    });
  }

  /** The next frame of [type] (buffered or future); rejects after a few seconds. */
  nextFrame<T extends Frame = Frame>(type: string): Promise<T> {
    const index = this.buffered.findIndex((f) => f.type === type);
    if (index >= 0) return Promise.resolve(this.buffered.splice(index, 1)[0] as T);
    return new Promise<T>((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error(`timed out waiting for "${type}" frame`)), WAIT_MS);
      this.waiters.push({
        type,
        resolve: (frame) => {
          clearTimeout(timer);
          resolve(frame as T);
        },
      });
    });
  }

  /** Answers every (current and future) request with [handler] instead of buffering it. */
  serve(handler: (request: RequestFrame) => { status: number; body: string | null; headers?: Record<string, string> }): void {
    this.handler = (request) => {
      const { status, body, headers } = handler(request);
      this.respond(request.id, status, body, headers);
    };
    for (let i = this.buffered.length - 1; i >= 0; i--) {
      const frame = this.buffered[i];
      if (frame?.type === 'request') {
        this.buffered.splice(i, 1);
        this.handler(frame as RequestFrame);
      }
    }
  }

  nextRequest(): Promise<RequestFrame> {
    return this.nextFrame<RequestFrame>('request');
  }

  /** Requests received so far that no test has consumed yet. */
  bufferedRequests(): number {
    return this.bufferedFrames('request');
  }

  /** Frames of [type] received so far that no test has consumed yet. */
  bufferedFrames(type: string): number {
    return this.buffered.filter((f) => f.type === type).length;
  }

  /** JSON ping round trip: afterwards, every frame the relay sent before it has arrived. */
  async sync(): Promise<void> {
    this.send({ type: 'ping', t: Date.now() });
    await this.nextFrame('pong');
  }

  send(frame: Record<string, unknown>): void {
    this.ws.send(JSON.stringify(frame));
  }

  respond(id: string, status: number, body: string | null, headers: Record<string, string> = {}): void {
    this.send({ type: 'response', id, status, headers, body });
  }
}

/** Opens a device socket and sends the hello; does not wait for the answer. */
export async function connectDevice(relay: Relay, options: ConnectOptions = {}): Promise<FakeDevice> {
  const identity = options.identity ?? newIdentity();
  const ws = new WebSocket(`${relay.url.replace(/^http/, 'ws')}/v1/device`, {
    autoPong: options.autoPong ?? true,
    headers: options.headers,
  });
  const device = new FakeDevice(identity, ws);
  await new Promise<void>((resolve, reject) => {
    ws.once('open', () => resolve());
    ws.once('error', reject);
  });
  if (options.rawHello !== null) {
    ws.send(
      options.rawHello ??
        JSON.stringify({
          type: 'hello',
          protocolVersion: 1,
          deviceId: identity.deviceId,
          deviceSecret: identity.secret,
          platform: 'test',
          appVersion: '0.0.1',
          deviceName: 'Fake device',
          ...options.hello,
        }),
    );
  }
  return device;
}

/** Connects a device and waits for its welcome. */
export async function connectReady(relay: Relay, options: ConnectOptions = {}): Promise<FakeDevice> {
  const device = await connectDevice(relay, options);
  await device.nextFrame('welcome');
  return device;
}

export interface HttpResult {
  readonly status: number;
  readonly headers: IncomingHttpHeaders;
  readonly body: string;
}

export interface HttpOptions {
  readonly method?: string;
  readonly headers?: Record<string, string>;
  readonly body?: string | Buffer;
  /** Send the body with chunked transfer encoding (no Content-Length). */
  readonly chunked?: boolean;
}

/** Plain HTTP request on a fresh connection, with exactly the given headers. */
export function httpRequest(url: string, options: HttpOptions = {}): Promise<HttpResult> {
  return new Promise((resolve, reject) => {
    const req = request(url, { method: options.method ?? 'GET', headers: options.headers, agent: false }, (res) => {
      const chunks: Buffer[] = [];
      res.on('data', (chunk: Buffer) => chunks.push(chunk));
      res.on('end', () =>
        resolve({ status: res.statusCode ?? 0, headers: res.headers, body: Buffer.concat(chunks).toString('utf8') }),
      );
      res.on('error', reject);
    });
    req.on('error', reject);
    if (options.body === undefined) req.end();
    else if (options.chunked) {
      req.write(options.body);
      req.end();
    } else req.end(options.body);
  });
}

export interface McpOptions {
  readonly token?: string | null;
  readonly body?: string;
  readonly headers?: Record<string, string>;
  readonly path?: string;
}

/** `POST /mcp` the way an MCP client does. */
export function postMcp(relay: Relay, options: McpOptions = {}): Promise<HttpResult> {
  const headers: Record<string, string> = {
    'content-type': 'application/json',
    accept: 'application/json, text/event-stream',
    ...(options.token ? { authorization: `Bearer ${options.token}` } : {}),
    ...options.headers,
  };
  return httpRequest(`${relay.url}${options.path ?? '/mcp'}`, {
    method: 'POST',
    headers,
    body: options.body ?? '{"jsonrpc":"2.0","id":1,"method":"tools/list"}',
  });
}

/** Polls [condition] until true or fails after a few seconds. */
export async function waitFor(condition: () => boolean, what: string): Promise<void> {
  const deadline = Date.now() + WAIT_MS;
  while (!condition()) {
    if (Date.now() > deadline) throw new Error(`timed out waiting for ${what}`);
    await new Promise((r) => setTimeout(r, 5));
  }
}

export const UNAUTHORIZED_BODY = '{"jsonrpc":"2.0","id":null,"error":{"code":-32001,"message":"Unauthorized"}}';
