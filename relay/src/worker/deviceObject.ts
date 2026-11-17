import { createCipheriv, createDecipheriv, createHmac, randomBytes } from 'node:crypto';
import { DurableObject } from 'cloudflare:workers';
import type { TunnelFailure, TunnelOutcome } from '../deviceConnection.js';
import { checkHello, welcomeFrame, type HelloInfo } from '../handshake.js';
import { createLogger, deviceTag, errorMessage, type Logger } from '../logger.js';
import { checkGrant, tokenResponseBody, INVALID_CODE_DESCRIPTION, type TokenError } from '../oauth/tokenRoute.js';
import {
  PairingStore,
  type AuthorizationRequest,
  type Channel,
  type CreateFailure,
  type PairingOrigin,
  type PairingSnapshot,
  type PairingView,
} from '../oauth/pairingStore.js';
import type { RelayPairFrame } from '../oauth/pairingFrames.js';
import { deviceTokenError, type DeviceFlowError } from '../oauth/deviceFlow.js';
import {
  CloseCode,
  HEARTBEAT_ACK_FRAME,
  HEARTBEAT_FRAME,
  MAX_FRAME_BYTES,
  MAX_PRE_HELLO_BYTES,
  parseFrame,
  parseResponseFrame,
  type DeviceResponse,
  type ErrorFrame,
  type RequestFrame,
} from '../protocol.js';
import { RateLimiter } from '../rateLimit.js';
import { workerConfig, type Env, type WorkerConfig } from './config.js';

/**
 * One Durable Object per device (`DEVICES.idFromName(deviceId)`), the Cloudflare counterpart of the
 * Node relay's DeviceConnection + PairingStore for that device (docs/PROTOCOL.md §3, §5):
 *
 * * owns the device WebSocket through the Hibernation API: per-socket state lives in the socket's
 *   attachment, so an idle phone costs (almost) nothing and survives the object being evicted. The
 *   `{"type":"hb"}` heartbeat is answered by the runtime (`setWebSocketAutoResponse`) without waking
 *   the object;
 * * multiplexes tunneled MCP requests (in memory: an open request keeps the object awake);
 * * keeps the device's pairings in a [PairingStore] whose state is written (encrypted with a key
 *   derived from RELAY_SECRET) to the object's SQLite storage after every change, because the phone
 *   may answer after the object hibernated;
 * * uses its alarm for hello timeouts and pairing expirations.
 *
 * The Worker (index.ts) does everything that needs no device state and calls the RPC methods below.
 */

/** Per-socket state, kept in the WebSocket attachment so it survives hibernation. */
interface SocketState {
  readonly v: 1;
  /** Connection id: names the socket in pending requests and pairing channels. */
  readonly conn: string;
  state: 'hello' | 'open' | 'closed';
  /** The device this object serves (from the connection URL). */
  readonly deviceId: string;
  readonly ip: string;
  readonly ipKey: string;
  /** `{relay}` as seen by the device's upgrade request (for the welcome's URLs). */
  readonly base: string;
  readonly helloDeadline: number;
  /** When the hello was accepted (0 before). */
  connectedAt: number;
  info: HelloInfo | null;
}

/** What the Worker forwards for one MCP request (already authenticated by shape, body read). */
export interface TunnelInput {
  readonly headers: Record<string, string>;
  readonly body: string;
  readonly remoteAddress: string;
}

export type TunnelResult =
  | { readonly kind: 'offline' }
  | { readonly kind: 'busy' }
  | { readonly kind: 'failed'; readonly failure: TunnelFailure }
  | { readonly kind: 'response'; readonly response: DeviceResponse };

export type AuthorizeResult =
  | { readonly kind: 'offline' }
  | { readonly kind: 'failure'; readonly reason: CreateFailure }
  | { readonly kind: 'waiting'; readonly pairing: PairingView; readonly deviceName: string | null };

/** A pairing found by its id and page token, with the phone's name for the waiting page. */
export interface PollResult {
  readonly pairing: PairingView;
  readonly deviceName: string | null;
}

export interface ExchangeInput {
  readonly code: string;
  readonly clientId: string;
  readonly verifier: string;
  /** The token request's form body (for redirect_uri and resource). */
  readonly form: string;
}

export type ExchangeResult =
  | { readonly ok: true; readonly body: string; readonly deviceId: string }
  | { readonly ok: false; readonly error: TokenError; readonly description: string; readonly deviceId: string | null };

export type RedeemOutcome =
  | { readonly ok: true; readonly body: string; readonly deviceId: string }
  | { readonly ok: false; readonly error: DeviceFlowError; readonly authFailure: boolean };

/** Headers the Worker sets on the internal upgrade request. */
export const INTERNAL = Object.freeze({ deviceId: 'x-pb-device-id', ip: 'x-pb-ip', ipKey: 'x-pb-ip-key', base: 'x-pb-base' });

const PAIRINGS_KEY = 'pairings';
const RATE_WINDOW_MS = 60_000;
/**
 * How long the pairing state can still hold something once no pairing or code is left: the hourly
 * pairing counters and undelivered notices (PairingStore keeps both for an hour), plus a margin.
 * An alarm then sweeps it, so nothing (client addresses included) stays in storage indefinitely.
 */
const LEFTOVER_SWEEP_MS = 3_601_000;

interface Pending {
  readonly conn: string;
  readonly resolve: (outcome: TunnelOutcome) => void;
  readonly timer: ReturnType<typeof setTimeout>;
}

export class DeviceObject extends DurableObject<Env> {
  private readonly cfg: WorkerConfig;
  private readonly logger: Logger;
  private readonly pending = new Map<string, Pending>();
  /** One object per connection id, so the pairing store can compare channels by identity. */
  private readonly channels = new Map<string, Channel>();
  /** Last frame received per connection, while this instance lives (heartbeats excluded). */
  private readonly lastFrameAt = new Map<string, number>();
  /** Hello failures per client IP: blocks access-key guessing through this device. */
  private readonly authFailures: RateLimiter;
  private readonly pairings: PairingStore;
  private readonly storageKey: Buffer;
  /** Plaintext of the last persisted pairing state, to skip writes that change nothing. */
  private persisted = '';

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    this.cfg = workerConfig(env);
    this.logger = createLogger(this.cfg.relay.logLevel, (line) => console.log(line));
    this.authFailures = new RateLimiter({
      windowMs: RATE_WINDOW_MS,
      maxRequests: Number.MAX_SAFE_INTEGER,
      maxAuthFailures: this.cfg.relay.rateLimitAuthFailuresPerMin,
      blockMs: RATE_WINDOW_MS,
    });
    this.storageKey = createHmac('sha256', this.cfg.relaySecret).update('droidhands/do-pairings/v1').digest();
    // Answered by the runtime itself: heartbeats never wake a hibernated object (§3.4).
    ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair(HEARTBEAT_FRAME, HEARTBEAT_ACK_FRAME));
    this.pairings = new PairingStore({
      ttlMs: this.cfg.relay.pairingTtlMs,
      codeTtlMs: this.cfg.relay.authCodeTtlMs,
      logger: this.logger,
      deliver: (_deviceId, frame) => this.deliver(frame),
      // The Worker routes the browser's polls and the client's code to this object by these prefixes.
      newPageToken: (deviceId) => `${deviceId}${randomBytes(16).toString('base64url')}`,
      newCode: (deviceId) => `${deviceId}${randomBytes(32).toString('base64url')}`,
    });
    this.loadPairings();
  }

  // ---- device WebSocket ----------------------------------------------------------------------

  /** The internal upgrade request from the Worker (`/v1/device?deviceId=…`, already rate-limited). */
  override async fetch(request: Request): Promise<Response> {
    const deviceId = request.headers.get(INTERNAL.deviceId) ?? '';
    const ip = request.headers.get(INTERNAL.ip) ?? 'unknown';
    const ipKey = request.headers.get(INTERNAL.ipKey) ?? ip;
    const base = request.headers.get(INTERNAL.base) ?? '';
    if (request.headers.get('upgrade')?.toLowerCase() !== 'websocket' || deviceId === '' || base === '') {
      return new Response('Bad Request\n', { status: 400 });
    }
    if (!this.authFailures.check(ipKey).allowed) return new Response(null, { status: 429 });
    // §3.1 pre-hello limits, per device here (the Node relay counts relay-wide and per IP). A socket
    // that was never welcomed counts until the runtime has let go of it, refused ones included: a
    // client that never answers our close frame keeps it around, and there is no way to cut it off
    // (the Node relay likewise counts a socket until its TCP connection ends).
    const waiting = this.sockets().filter(([, s]) => s.connectedAt === 0);
    if (waiting.length >= this.cfg.relay.maxPendingHandshakes) return new Response(null, { status: 503 });
    if (waiting.filter(([, s]) => s.ipKey === ipKey).length >= this.cfg.relay.maxPendingHandshakesPerIp) {
      this.logger.warn('device handshake refused', { ip, reason: 'key_limit', pending: waiting.length });
      return new Response(null, { status: 429 });
    }

    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];
    this.ctx.acceptWebSocket(server);
    const now = Date.now();
    const state: SocketState = {
      v: 1,
      conn: randomBytes(9).toString('base64url'),
      state: 'hello',
      deviceId,
      ip,
      ipKey,
      base,
      helloDeadline: now + this.cfg.relay.helloTimeoutMs,
      connectedAt: 0,
      info: null,
    };
    server.serializeAttachment(state);
    await this.scheduleAlarm();
    return new Response(null, { status: 101, webSocket: client });
  }

  /** Closes sockets whose hello is overdue with 4008 (§3.1). */
  private closeLateHellos(): void {
    const now = Date.now();
    for (const [ws, state] of this.sockets()) {
      if (state.state !== 'hello' || now < state.helloDeadline) continue;
      this.logger.info('device hello timeout', { ip: state.ip });
      this.markClosed(ws, state);
      closeQuietly(ws, CloseCode.HELLO_TIMEOUT, 'hello timeout');
    }
  }

  override async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer): Promise<void> {
    const state = socketState(ws);
    if (state === null) return closeQuietly(ws, CloseCode.BAD_REQUEST, 'unknown socket');
    if (state.state === 'hello') return this.onHello(ws, state, message);
    if (state.state !== 'open') return;
    if (frameBytes(message) > MAX_FRAME_BYTES) {
      // §3: frames are capped at 16 MiB. The runtime accepts up to 32 MiB, so enforce it here like
      // the Node relay (ws maxPayload): close with 1009 and fail what was pending on the connection.
      this.logger.warn('device frame too large', { device: deviceTag(state.deviceId) });
      this.dropConnection(ws, state, 1009, 'frame too large', 'disconnected');
      this.pairings.connectionClosed(this.channel(state.conn));
      return this.pairingsChanged();
    }
    this.lastFrameAt.set(state.conn, Date.now());
    const frame = typeof message === 'string' ? parseFrame(message) : null;
    if (frame === null) {
      this.logger.debug('ignored non-JSON or binary device frame', { device: deviceTag(state.deviceId) });
      return;
    }
    switch (frame.type) {
      case 'response': {
        const parsed = parseResponseFrame(frame);
        if (parsed === null) return;
        const outcome: TunnelOutcome =
          parsed.response === null ? { ok: false, failure: 'invalid_response' } : { ok: true, response: parsed.response };
        if (!this.settle(parsed.id, outcome, state.conn)) {
          this.logger.debug('ignored response for unknown request', { device: deviceTag(state.deviceId) });
        }
        return;
      }
      case 'pair_response':
        this.pairings.handleResponse(state.deviceId, frame);
        return this.pairingsChanged();
      case 'ping':
        return sendQuietly(ws, JSON.stringify({ type: 'pong', t: frame.t }));
      case 'hb':
        // Only an exact HEARTBEAT_FRAME is auto-answered; answer variants too.
        return sendQuietly(ws, HEARTBEAT_ACK_FRAME);
      case 'pong':
      case 'hb_ack':
        return;
      default:
        this.logger.debug('ignored device frame', { device: deviceTag(state.deviceId), type: frame.type.slice(0, 32) });
    }
  }

  override async webSocketClose(ws: WebSocket, code: number, reason: string): Promise<void> {
    await this.onSocketGone(ws, code, reason);
  }

  override async webSocketError(ws: WebSocket, error: unknown): Promise<void> {
    this.logger.debug('device socket error', { error: errorMessage(error) });
    await this.onSocketGone(ws, 1006, '');
  }

  private async onHello(ws: WebSocket, state: SocketState, message: string | ArrayBuffer): Promise<void> {
    if (frameBytes(message) > MAX_PRE_HELLO_BYTES) {
      this.logger.info('device sent too much before its hello', { ip: state.ip });
      this.markClosed(ws, state);
      closeQuietly(ws, 1009, 'too much data before hello');
      return this.scheduleAlarm();
    }
    const hello = checkHello(typeof message === 'string' ? message : null, {
      accessKey: this.cfg.relay.accessKey,
      urlDeviceId: state.deviceId,
    });
    if (!hello.ok) {
      if (hello.authFailure) this.authFailures.recordAuthFailure(state.ipKey);
      this.logger.info('device rejected', { ip: state.ip, code: hello.code });
      const frame: ErrorFrame = { type: 'error', code: hello.code, message: hello.message };
      sendQuietly(ws, JSON.stringify(frame));
      this.markClosed(ws, state);
      closeQuietly(ws, hello.closeCode, hello.code);
      return this.scheduleAlarm();
    }

    const now = Date.now();
    state.state = 'open';
    state.connectedAt = now;
    state.info = hello.info;
    ws.serializeAttachment(state);
    this.lastFrameAt.set(state.conn, now);
    const replaced: string[] = [];
    for (const [other, otherState] of this.sockets()) {
      if (otherState.conn === state.conn || otherState.state !== 'open') continue;
      // §3.1: the new connection wins; requests pending on the old one fail with 502.
      this.dropConnection(other, otherState, CloseCode.REPLACED, 'replaced by a newer connection', 'replaced');
      replaced.push(otherState.conn);
      this.logger.info('device connection replaced', { device: deviceTag(state.deviceId) });
    }
    sendQuietly(ws, JSON.stringify(welcomeFrame(state.base, state.deviceId, now)));
    this.logger.info('device connected', {
      device: deviceTag(state.deviceId),
      ip: state.ip,
      platform: hello.info.platform,
      appVersion: hello.info.appVersion,
    });
    // Re-sends pending pair_requests on the new connection before the old ones' prompts expire.
    this.pairings.deviceConnected(state.deviceId, this.channel(state.conn));
    for (const conn of replaced) this.pairings.connectionClosed(this.channel(conn));
    await this.pairingsChanged();
  }

  private async onSocketGone(ws: WebSocket, code: number, reason: string): Promise<void> {
    const state = socketState(ws);
    // Completes the close handshake (a no-op where the runtime already did).
    closeQuietly(ws, code === 1005 || code === 1006 ? 1000 : code, reason);
    if (state === null) return;
    const wasOpen = state.state === 'open';
    this.markClosed(ws, state);
    this.lastFrameAt.delete(state.conn);
    if (wasOpen) {
      this.failAll(state.conn, 'disconnected');
      this.pairings.connectionClosed(this.channel(state.conn));
      this.persistPairings();
      this.logger.info('device disconnected', {
        device: deviceTag(state.deviceId),
        code,
        connectedSec: Math.round((Date.now() - state.connectedAt) / 1000),
      });
    }
    this.channels.delete(state.conn);
    await this.scheduleAlarm();
  }

  /** Closes an authenticated connection from the relay side and fails its pending requests. */
  private dropConnection(ws: WebSocket, state: SocketState, code: number, reason: string, failure: TunnelFailure): void {
    this.markClosed(ws, state);
    this.failAll(state.conn, failure);
    this.lastFrameAt.delete(state.conn);
    closeQuietly(ws, code, reason);
  }

  private markClosed(ws: WebSocket, state: SocketState): void {
    state.state = 'closed';
    try {
      ws.serializeAttachment(state);
    } catch {
      // The socket is already gone; nothing to keep.
    }
  }

  // ---- alarm: hello timeouts and pairing expirations -------------------------------------------

  override async alarm(): Promise<void> {
    this.closeLateHellos();
    this.persistPairings();
    await this.scheduleAlarm(true);
  }

  /**
   * Sets the alarm to the next hello deadline or pairing expiration, or clears it. Only moves an
   * alarm earlier, except from the alarm handler itself ([fromAlarm]), which always re-arms.
   */
  private async scheduleAlarm(fromAlarm = false): Promise<void> {
    let next = this.pairings.nextDeadline();
    // Only counters and queued notices left in storage: sweep them once they have all expired.
    if (next === null && this.persisted !== '') next = Date.now() + LEFTOVER_SWEEP_MS;
    for (const [, state] of this.sockets()) {
      if (state.state === 'hello' && (next === null || state.helloDeadline < next)) next = state.helloDeadline;
    }
    const current = fromAlarm ? null : await this.ctx.storage.getAlarm();
    if (next === null) {
      if (current !== null || fromAlarm) await this.ctx.storage.deleteAlarm();
    } else if (current === null || current > next) {
      await this.ctx.storage.setAlarm(next);
    }
  }

  // ---- RPC: tunneled MCP requests --------------------------------------------------------------

  /** Forwards one MCP request to the device (§3.2-3.5) and waits for its answer. Never throws. */
  async tunnel(input: TunnelInput): Promise<TunnelResult> {
    const live = this.liveConnection();
    if (live === null) return { kind: 'offline' };
    const [ws, state] = live;
    let inflight = 0;
    for (const entry of this.pending.values()) if (entry.conn === state.conn) inflight += 1;
    if (inflight >= this.cfg.relay.maxInflightPerDevice) return { kind: 'busy' };

    let id = newRequestId();
    while (this.pending.has(id)) id = newRequestId();
    const outcome = await new Promise<TunnelOutcome>((resolve) => {
      const timer = setTimeout(() => this.settle(id, { ok: false, failure: 'timeout' }), this.cfg.relay.requestTimeoutMs);
      this.pending.set(id, { conn: state.conn, resolve, timer });
      const frame: RequestFrame = {
        type: 'request',
        id,
        method: 'POST',
        path: '/mcp',
        headers: input.headers,
        body: input.body,
        remoteAddress: input.remoteAddress,
      };
      try {
        ws.send(JSON.stringify(frame));
      } catch {
        this.settle(id, { ok: false, failure: 'send_failed' });
      }
    });
    return outcome.ok ? { kind: 'response', response: outcome.response } : { kind: 'failed', failure: outcome.failure };
  }

  /** Whether the device is connected (for tests and diagnostics). */
  async status(): Promise<{ online: boolean; sockets: number; inflight: number; pairings: number }> {
    return {
      online: this.liveConnection() !== null,
      sockets: this.sockets().filter(([, s]) => s.state !== 'closed').length,
      inflight: this.pending.size,
      pairings: this.pairings.size,
    };
  }

  private settle(id: string, outcome: TunnelOutcome, conn?: string): boolean {
    const entry = this.pending.get(id);
    // A response only settles requests sent on the same connection.
    if (entry === undefined || (conn !== undefined && entry.conn !== conn)) return false;
    this.pending.delete(id);
    clearTimeout(entry.timer);
    entry.resolve(outcome);
    return true;
  }

  private failAll(conn: string, failure: TunnelFailure): void {
    for (const [id, entry] of [...this.pending]) if (entry.conn === conn) this.settle(id, { ok: false, failure });
  }

  // ---- RPC: one-tap pairing (§5.3-5.4) ----------------------------------------------------------

  async authorize(request: AuthorizationRequest, origin: PairingOrigin): Promise<AuthorizeResult> {
    const live = this.liveConnection();
    if (live === null) return { kind: 'offline' };
    const result = this.pairings.create(request, origin);
    await this.pairingsChanged();
    if (!result.ok) return { kind: 'failure', reason: result.reason };
    return { kind: 'waiting', pairing: result.pairing, deviceName: live[1].info?.deviceName ?? null };
  }

  async poll(id: string, pageToken: string): Promise<PollResult | null> {
    const pairing = this.pairings.poll(id, pageToken);
    await this.pairingsChanged();
    if (pairing === null) return null;
    return { pairing, deviceName: this.liveConnection()?.[1].info?.deviceName ?? null };
  }

  /**
   * The code part of `POST /oauth/token` (the Worker checked everything else). The token is handed
   * out and `pair_complete` sent at once: unlike node:http, a Worker cannot tell whether its
   * response reached the client.
   */
  async exchange(input: ExchangeInput): Promise<ExchangeResult> {
    const grant = this.pairings.takeCode(input.code);
    if (grant === null) {
      await this.pairingsChanged();
      return { ok: false, error: 'invalid_grant', description: INVALID_CODE_DESCRIPTION, deviceId: null };
    }
    const problem = checkGrant(grant, input.clientId, new URLSearchParams(input.form), input.verifier);
    if (problem !== null) {
      this.pairings.finishExchange(grant, false);
      await this.pairingsChanged();
      return { ok: false, error: problem.error, description: problem.description, deviceId: grant.deviceId };
    }
    const body = tokenResponseBody(grant);
    this.pairings.finishExchange(grant, true);
    await this.pairingsChanged();
    return { ok: true, body, deviceId: grant.deviceId };
  }

  /**
   * The device-code grant of `POST /oauth/token` (agent sign-in, §5.6): the token once the phone
   * approved, handed out and `pair_complete` sent at once as in [exchange].
   */
  async redeem(id: string, pageToken: string): Promise<RedeemOutcome> {
    const result = this.pairings.redeem(id, pageToken);
    if (result.status !== 'approved') {
      await this.pairingsChanged();
      return { ok: false, error: deviceTokenError(result), authFailure: result.status === 'invalid' };
    }
    const body = tokenResponseBody(result.grant);
    this.pairings.finishExchange(result.grant, true);
    await this.pairingsChanged();
    return { ok: true, body, deviceId: result.grant.deviceId };
  }

  // ---- helpers ---------------------------------------------------------------------------------

  /** The authenticated connection, or null. A heartbeating device silent for too long is dropped. */
  private liveConnection(): [WebSocket, SocketState] | null {
    let live: [WebSocket, SocketState] | null = null;
    for (const entry of this.sockets()) {
      const [ws, state] = entry;
      if (state.state !== 'open' || ws.readyState !== WebSocket.OPEN) continue;
      if (live === null || state.connectedAt > live[1].connectedAt) live = entry;
    }
    if (live === null) return null;
    const [ws, state] = live;
    // Only devices known to send heartbeats can be judged by them; older apps are trusted as before.
    const heartbeat = this.ctx.getWebSocketAutoResponseTimestamp(ws)?.getTime() ?? null;
    if (heartbeat !== null) {
      const lastSeen = Math.max(heartbeat, this.lastFrameAt.get(state.conn) ?? 0, state.connectedAt);
      if (Date.now() - lastSeen > this.cfg.heartbeatTimeoutMs) {
        this.logger.info('device heartbeat timeout', { device: deviceTag(state.deviceId) });
        this.dropConnection(ws, state, CloseCode.GOING_AWAY, 'heartbeat timeout', 'disconnected');
        this.pairings.connectionClosed(this.channel(state.conn));
        this.persistPairings();
        return null;
      }
    }
    return live;
  }

  /** Sends a pairing frame to the live connection; the pairing store's `deliver`. */
  private deliver(frame: RelayPairFrame): Channel | null {
    let live: [WebSocket, SocketState] | null = null;
    for (const entry of this.sockets()) {
      if (entry[1].state === 'open' && entry[0].readyState === WebSocket.OPEN) {
        if (live === null || entry[1].connectedAt > live[1].connectedAt) live = entry;
      }
    }
    if (live === null) return null;
    try {
      live[0].send(JSON.stringify(frame));
    } catch {
      return null;
    }
    return this.channel(live[1].conn);
  }

  private channel(conn: string): Channel {
    let channel = this.channels.get(conn);
    if (channel === undefined) {
      channel = { conn };
      this.channels.set(conn, channel);
    }
    return channel;
  }

  private sockets(): Array<[WebSocket, SocketState]> {
    const out: Array<[WebSocket, SocketState]> = [];
    for (const ws of this.ctx.getWebSockets()) {
      const state = socketState(ws);
      if (state !== null) out.push([ws, state]);
    }
    return out;
  }

  /** After the pairing state may have changed: persists it and re-arms the alarm for its deadlines. */
  private async pairingsChanged(): Promise<void> {
    this.persistPairings();
    await this.scheduleAlarm();
  }

  /**
   * Applies due expirations, then writes the pairing state if it changed. Empty state is never
   * written, and deleted once everything in it expired: requests that reach an unpaired device's
   * object (any client can name any device id) must not leave rows behind.
   */
  private persistPairings(): void {
    this.pairings.sweep();
    const snapshot = this.pairings.snapshot((channel) => (channel as { conn: string }).conn);
    const empty =
      snapshot.records.length === 0 && snapshot.codes.length === 0 && snapshot.history.length === 0 && snapshot.notices.length === 0;
    const plain = empty ? '' : JSON.stringify(snapshot);
    if (plain === this.persisted) return;
    this.persisted = plain;
    if (empty) this.ctx.storage.kv.delete(PAIRINGS_KEY);
    else this.ctx.storage.kv.put(PAIRINGS_KEY, this.seal(plain));
  }

  private loadPairings(): void {
    const sealed = this.ctx.storage.kv.get<string>(PAIRINGS_KEY);
    if (sealed === undefined) return;
    const plain = typeof sealed === 'string' ? this.open(sealed) : null;
    if (plain === null) {
      this.logger.warn('discarded unreadable pairing state (was RELAY_SECRET changed?)');
      this.ctx.storage.kv.delete(PAIRINGS_KEY);
      return;
    }
    try {
      this.pairings.restore(JSON.parse(plain) as PairingSnapshot, (conn) => this.channel(conn));
      this.persisted = plain;
    } catch (err) {
      this.logger.warn('discarded malformed pairing state', { error: errorMessage(err) });
      this.ctx.storage.kv.delete(PAIRINGS_KEY);
    }
  }

  /** AES-256-GCM with a key derived from RELAY_SECRET: storage history alone reveals no tokens. */
  private seal(plain: string): string {
    const iv = randomBytes(12);
    const cipher = createCipheriv('aes-256-gcm', this.storageKey, iv);
    const data = Buffer.concat([cipher.update(plain, 'utf8'), cipher.final(), cipher.getAuthTag()]);
    return `v1.${iv.toString('base64url')}.${data.toString('base64url')}`;
  }

  private open(sealed: string): string | null {
    const [version, iv, data] = sealed.split('.');
    if (version !== 'v1' || iv === undefined || data === undefined) return null;
    try {
      const bytes = Buffer.from(data, 'base64url');
      const decipher = createDecipheriv('aes-256-gcm', this.storageKey, Buffer.from(iv, 'base64url'));
      decipher.setAuthTag(bytes.subarray(bytes.length - 16));
      return Buffer.concat([decipher.update(bytes.subarray(0, bytes.length - 16)), decipher.final()]).toString('utf8');
    } catch {
      return null;
    }
  }
}

function socketState(ws: WebSocket): SocketState | null {
  try {
    const value = ws.deserializeAttachment() as SocketState | null;
    return value !== null && typeof value === 'object' && value.v === 1 ? value : null;
  } catch {
    return null;
  }
}

/** Size of a received frame in bytes (UTF-8 for text). */
function frameBytes(message: string | ArrayBuffer): number {
  return typeof message === 'string' ? Buffer.byteLength(message, 'utf8') : message.byteLength;
}

function sendQuietly(ws: WebSocket, text: string): void {
  try {
    ws.send(text);
  } catch {
    // Closing; the close handler cleans up.
  }
}

function closeQuietly(ws: WebSocket, code: number, reason: string): void {
  try {
    ws.close(code, reason);
  } catch {
    try {
      ws.close();
    } catch {
      // Already closed.
    }
  }
}

/** Opaque, unguessable request id (`r_` + 96 random bits), as on the Node relay. */
function newRequestId(): string {
  return `r_${randomBytes(12).toString('base64url')}`;
}
