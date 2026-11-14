import type { Duplex } from 'node:stream';

/**
 * Bounds what device sockets cost before their hello is accepted (docs/PROTOCOL.md §3.1): how many
 * may wait for their hello at once (relay-wide and per client IP), and how many bytes each may send
 * meanwhile. Without it, a client with no identity or access key could open sockets and make the
 * relay buffer a 16 MiB frame on each.
 */

export interface HandshakeLimits {
  /** Sockets waiting for their hello, relay-wide. */
  readonly maxPending: number;
  /** Sockets waiting for their hello, per rate-limit key (client IP or IPv6 /64). */
  readonly maxPendingPerKey: number;
  /** Bytes a socket may send before its hello is accepted. */
  readonly maxBytes: number;
}

/** A reserved handshake slot for one socket. */
export interface HandshakeSlot {
  /**
   * Starts counting the bytes the client sends and destroys the socket (after calling [onExceeded])
   * once they exceed the limit. Call it when the WebSocket has taken over the socket, so the
   * WebSocket's own reader sees every byte first.
   */
  limitBytes(onExceeded: () => void): void;
  /** The hello was accepted: frees the slot and lifts the byte limit. Idempotent. */
  release(): void;
}

export type HandshakeRefusal = 'global_limit' | 'key_limit';

export class HandshakeGuard {
  private total = 0;
  private readonly perKey = new Map<string, number>();

  constructor(private readonly limits: HandshakeLimits) {}

  /** Sockets currently waiting for their hello. */
  get pending(): number {
    return this.total;
  }

  /**
   * Reserves a slot for a new socket from [key], or names the limit that is reached. The slot is
   * freed by [HandshakeSlot.release] or when [socket] closes, whichever comes first.
   */
  admit(key: string, socket: Duplex): HandshakeSlot | HandshakeRefusal {
    if (this.total >= this.limits.maxPending) return 'global_limit';
    const count = this.perKey.get(key) ?? 0;
    if (count >= this.limits.maxPendingPerKey) return 'key_limit';
    this.total += 1;
    this.perKey.set(key, count + 1);

    let released = false;
    let meter: ((chunk: Buffer) => void) | null = null;
    const release = (): void => {
      if (meter !== null) socket.off('data', meter);
      meter = null;
      if (released) return;
      released = true;
      this.total -= 1;
      const left = (this.perKey.get(key) ?? 1) - 1;
      if (left <= 0) this.perKey.delete(key);
      else this.perKey.set(key, left);
    };
    socket.once('close', release);

    return {
      limitBytes: (onExceeded) => {
        if (released || meter !== null) return;
        let received = 0;
        meter = (chunk) => {
          // Removing a listener does not skip it for the chunk being emitted (whose hello may
          // just have been accepted).
          if (released) return;
          received += chunk.length;
          if (received <= this.limits.maxBytes) return;
          release();
          onExceeded();
          socket.destroy();
        };
        socket.on('data', meter);
      },
      release,
    };
  }
}
