import type { IncomingMessage } from 'node:http';
import type { RelayConfig } from './config.js';
import type { DeviceRegistry } from './deviceRegistry.js';
import type { Logger } from './logger.js';
import { clientAddress, publicBaseUrl, rateLimitKey } from './net.js';
import type { PairingStore } from './oauth/pairingStore.js';
import type { RateDecision, RateLimiter } from './rateLimit.js';

/** Everything the HTTP routes need. */
export interface HttpDeps {
  readonly config: RelayConfig;
  readonly logger: Logger;
  readonly registry: DeviceRegistry;
  readonly limiter: RateLimiter;
  readonly pairings: PairingStore;
  /** HMAC key for stateless OAuth client ids. Never log it. */
  readonly relaySecret: Buffer;
  readonly isShuttingDown: () => boolean;
}

/** The client's address and its rate-limit key. */
export interface ClientInfo {
  readonly ip: string;
  readonly ipKey: string;
}

export function clientInfo(deps: HttpDeps, req: IncomingMessage): ClientInfo {
  const ip = clientAddress(req, deps.config.trustProxy);
  return { ip, ipKey: rateLimitKey(ip) };
}

/** Counts one request against the client's per-IP budget. */
export function admit(deps: HttpDeps, client: ClientInfo): RateDecision {
  const decision = deps.limiter.hit(client.ipKey);
  if (!decision.allowed) deps.logger.warn('rate limited', { ip: client.ip, reason: decision.reason });
  return decision;
}

/** `{relay}`: the public base URL as seen by this request's client. */
export function relayBase(deps: HttpDeps, req: IncomingMessage): string {
  return publicBaseUrl(req, deps.config.publicUrl, deps.config.trustProxy);
}

/** Path prefix of PUBLIC_URL (e.g. `/relay`), or `''`. */
export function publicPathPrefix(config: RelayConfig): string {
  return config.publicUrl === null ? '' : new URL(config.publicUrl).pathname.replace(/\/+$/, '');
}
