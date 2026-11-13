import { randomBytes } from 'node:crypto';
import WebSocket from 'ws';
import { parseResponseFrame, type DeviceResponse, type RequestFrame } from './protocol.js';

/** Why a tunneled request produced no device response. */
export type TunnelFailure =
  /** No response within REQUEST_TIMEOUT_MS. */
  | 'timeout'
  /** The device's socket closed or was dropped by the heartbeat. */
  | 'disconnected'
  /** A newer connection of the same device took over (close 4009). */
  | 'replaced'
  /** The relay is shutting down. */
  | 'shutdown'
  /** The frame could not be written to the socket. */
  | 'send_failed'
  /** The device answered with a malformed `response` frame. */
  | 'invalid_response';

export type TunnelOutcome =
  | { readonly ok: true; readonly response: DeviceResponse }
  | { readonly ok: false; readonly failure: TunnelFailure };

/** Descriptive, non-secret data from the device's hello (for logs only). */
export interface DeviceInfo {
  readonly platform: string | undefined;
  readonly appVersion: string | undefined;
}

interface Pending {
  readonly resolve: (outcome: TunnelOutcome) => void;
  readonly timer: NodeJS.Timeout;
}

/** WebSocket-level pings a device may leave unanswered before it is dropped. */
const MAX_MISSED_PONGS = 2;

/**
 * An authenticated device socket and the requests multiplexed over it (docs/PROTOCOL.md §3.2-3.5).
 * Every forwarded request settles exactly once: with the device's response, or with a
 * [TunnelFailure] on timeout, disconnect, replacement or shutdown.
 */
export class DeviceConnection {
  private readonly pending = new Map<string, Pending>();
  private closed = false;
  private missedPongs = 0;
  readonly connectedAt = Date.now();

  constructor(
    readonly deviceId: string,
    private readonly ws: WebSocket,
    private readonly requestTimeoutMs: number,
    readonly info: DeviceInfo,
  ) {}

  /** Requests forwarded to the device that have not settled yet. */
  get inflight(): number {
    return this.pending.size;
  }

  /** True while new requests can be forwarded. */
  get isOpen(): boolean {
    return !this.closed && this.ws.readyState === WebSocket.OPEN;
  }

  /**
   * Forwards one HTTP request to the device and resolves with its outcome. Never rejects.
   * Callers enforce the in-flight cap before calling.
   */
  forward(request: Omit<RequestFrame, 'type' | 'id'>): Promise<TunnelOutcome> {
    if (!this.isOpen) return Promise.resolve({ ok: false, failure: 'disconnected' });
    let id = newRequestId();
    while (this.pending.has(id)) id = newRequestId();

    return new Promise<TunnelOutcome>((resolve) => {
      const timer = setTimeout(() => this.settle(id, { ok: false, failure: 'timeout' }), this.requestTimeoutMs);
      this.pending.set(id, { resolve, timer });
      const frame: RequestFrame = { type: 'request', id, ...request };
      this.ws.send(JSON.stringify(frame), (err) => {
        if (err) this.settle(id, { ok: false, failure: 'send_failed' });
      });
    });
  }

  /**
   * Handles a `response` frame. Returns false when it matches no pending request (unknown,
   * timed-out or duplicate id); such frames are ignored.
   */
  handleResponse(frame: Record<string, unknown>): boolean {
    const parsed = parseResponseFrame(frame);
    if (parsed === null) return false;
    const outcome: TunnelOutcome =
      parsed.response === null ? { ok: false, failure: 'invalid_response' } : { ok: true, response: parsed.response };
    return this.settle(parsed.id, outcome);
  }

  /** Records a WebSocket pong: the device is alive. */
  onPong(): void {
    this.missedPongs = 0;
  }

  /**
   * One heartbeat tick: drops the socket if the previous [MAX_MISSED_PONGS] pings went unanswered,
   * otherwise sends a new ping.
   */
  heartbeat(): void {
    if (!this.isOpen) return;
    if (this.missedPongs >= MAX_MISSED_PONGS) {
      // terminate() emits 'close', which fails pending requests via onSocketClosed().
      this.ws.terminate();
      return;
    }
    this.missedPongs += 1;
    try {
      this.ws.ping();
    } catch {
      // Socket is closing; the 'close' event will clean up.
    }
  }

  /**
   * Closes the socket with [code] and fails every pending request with [failure].
   * Idempotent.
   */
  close(code: number, reason: string, failure: TunnelFailure): void {
    if (this.closed) return;
    this.closed = true;
    this.failAll(failure);
    try {
      this.ws.close(code, reason);
    } catch {
      this.ws.terminate();
    }
  }

  /** Must be called from the socket's 'close' event. */
  onSocketClosed(): void {
    this.closed = true;
    this.failAll('disconnected');
  }

  private failAll(failure: TunnelFailure): void {
    for (const id of [...this.pending.keys()]) this.settle(id, { ok: false, failure });
  }

  private settle(id: string, outcome: TunnelOutcome): boolean {
    const entry = this.pending.get(id);
    if (entry === undefined) return false;
    this.pending.delete(id);
    clearTimeout(entry.timer);
    entry.resolve(outcome);
    return true;
  }
}

/** Opaque, unguessable request id (`r_` + 96 random bits). */
function newRequestId(): string {
  return `r_${randomBytes(12).toString('base64url')}`;
}
