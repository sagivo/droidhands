import type { RelayPairFrame } from './pairingFrames.js';

/**
 * Bounded, in-memory queue of `pair_complete` / `pair_abandoned` notices for devices that were
 * offline when the pairing finished, delivered when they reconnect. Without it a phone could keep a
 * token that no agent ever received. Stale notices expire; the oldest are dropped beyond the caps.
 */
export class NoticeOutbox {
  private readonly queues = new Map<string, Array<{ frame: RelayPairFrame; queuedAt: number }>>();

  constructor(
    private readonly options: {
      readonly maxPerDevice: number;
      readonly maxDevices: number;
      readonly ttlMs: number;
      readonly now: () => number;
    },
  ) {}

  queue(deviceId: string, frame: RelayPairFrame): void {
    let queued = this.queues.get(deviceId);
    if (queued === undefined) {
      if (this.queues.size >= this.options.maxDevices) return;
      queued = [];
      this.queues.set(deviceId, queued);
    }
    queued.push({ frame, queuedAt: this.options.now() });
    if (queued.length > this.options.maxPerDevice) queued.shift();
  }

  /** Removes and returns the device's notices that are still fresh, oldest first. */
  drain(deviceId: string): RelayPairFrame[] {
    const queued = this.queues.get(deviceId);
    if (queued === undefined) return [];
    this.queues.delete(deviceId);
    const now = this.options.now();
    return queued.filter((n) => now - n.queuedAt < this.options.ttlMs).map((n) => n.frame);
  }

  /** Drops stale notices. */
  sweep(): void {
    const now = this.options.now();
    for (const [deviceId, queued] of this.queues) {
      const kept = queued.filter((n) => now - n.queuedAt < this.options.ttlMs);
      if (kept.length === 0) this.queues.delete(deviceId);
      else this.queues.set(deviceId, kept);
    }
  }

  clear(): void {
    this.queues.clear();
  }
}
