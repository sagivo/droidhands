/**
 * Per-client abuse limits: a request budget per window and an authentication-failure budget that,
 * when exceeded, blocks the client for [RateLimiterOptions.blockMs]. Fixed windows keep the state
 * at a few numbers per key; [sweep] (called periodically) and [RateLimiterOptions.maxEntries]
 * bound the memory.
 */

export interface RateLimiterOptions {
  readonly windowMs: number;
  /** Requests allowed per window; the next one is refused. */
  readonly maxRequests: number;
  /** Auth failures allowed per window; the next one blocks the key. */
  readonly maxAuthFailures: number;
  readonly blockMs: number;
  /** Hard cap on tracked keys. New keys beyond it are not tracked (fail open) until a sweep. */
  readonly maxEntries?: number;
  readonly now?: () => number;
}

export type RateDecision =
  | { readonly allowed: true }
  | { readonly allowed: false; readonly reason: 'blocked' | 'rate'; readonly retryAfterSec: number };

interface Entry {
  windowStart: number;
  requests: number;
  authFailures: number;
  blockedUntil: number;
}

const ALLOWED: RateDecision = Object.freeze({ allowed: true });

export class RateLimiter {
  private readonly entries = new Map<string, Entry>();
  private readonly now: () => number;
  private readonly maxEntries: number;

  constructor(private readonly options: RateLimiterOptions) {
    this.now = options.now ?? Date.now;
    this.maxEntries = options.maxEntries ?? 100_000;
  }

  /** Number of tracked keys (for tests and metrics). */
  get size(): number {
    return this.entries.size;
  }

  /** Counts one request for [key] and decides whether it may proceed. */
  hit(key: string): RateDecision {
    const now = this.now();
    const entry = this.entry(key, now);
    if (entry === null) return ALLOWED;
    const blocked = this.blockedDecision(entry, now);
    if (blocked !== null) return blocked;
    entry.requests += 1;
    if (entry.requests > this.options.maxRequests) {
      return { allowed: false, reason: 'rate', retryAfterSec: seconds(entry.windowStart + this.options.windowMs - now) };
    }
    return ALLOWED;
  }

  /** Whether [key] is currently blocked for authentication failures, without counting a request. */
  check(key: string): RateDecision {
    const now = this.now();
    const entry = this.entries.get(key);
    return (entry && this.blockedDecision(entry, now)) ?? ALLOWED;
  }

  /** Records an authentication failure; exceeding the budget blocks [key] for `blockMs`. */
  recordAuthFailure(key: string): void {
    const now = this.now();
    const entry = this.entry(key, now);
    if (entry === null) return;
    entry.authFailures += 1;
    if (entry.authFailures > this.options.maxAuthFailures && entry.blockedUntil <= now) {
      entry.blockedUntil = now + this.options.blockMs;
    }
  }

  /** Drops keys whose window has expired and that are not blocked. */
  sweep(): void {
    const now = this.now();
    for (const [key, entry] of this.entries) {
      if (entry.blockedUntil <= now && now - entry.windowStart >= this.options.windowMs) {
        this.entries.delete(key);
      }
    }
  }

  private entry(key: string, now: number): Entry | null {
    let entry = this.entries.get(key);
    if (entry === undefined) {
      if (this.entries.size >= this.maxEntries) {
        this.sweep();
        if (this.entries.size >= this.maxEntries) return null;
      }
      entry = { windowStart: now, requests: 0, authFailures: 0, blockedUntil: 0 };
      this.entries.set(key, entry);
    } else if (now - entry.windowStart >= this.options.windowMs) {
      entry.windowStart = now;
      entry.requests = 0;
      entry.authFailures = 0;
    }
    return entry;
  }

  private blockedDecision(entry: Entry, now: number): RateDecision | null {
    if (entry.blockedUntil <= now) return null;
    return { allowed: false, reason: 'blocked', retryAfterSec: seconds(entry.blockedUntil - now) };
  }
}

function seconds(ms: number): number {
  return Math.max(1, Math.ceil(ms / 1000));
}
