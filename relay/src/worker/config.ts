/**
 * Configuration of the Cloudflare Worker relay. The variables are the Node relay's
 * (relay/README.md), parsed and validated by the same code ([loadConfigFromEnv]), plus a few that
 * only make sense here. Values come from `[vars]` in wrangler.toml and from secrets
 * (`wrangler secret put RELAY_SECRET`).
 */
import { ConfigError, loadConfigFromEnv, resolveConfig, type RelayConfig } from '../config.js';
import { HEARTBEAT_TIMEOUT_MS } from '../protocol.js';
import type { DeviceObject } from './deviceObject.js';

/** Bindings and variables of the Worker (see wrangler.toml). */
export interface Env {
  /** One Durable Object per device, named by its deviceId. */
  readonly DEVICES: DurableObjectNamespace<DeviceObject>;
  /** Optional service binding to the API Worker (api/), used to fetch its public keys. */
  readonly ACCOUNTS?: Fetcher;
  /** Variables and secrets; only string values are read. */
  readonly [name: string]: unknown;
}

export interface WorkerConfig {
  /** The shared relay settings. `relaySecret` is always set here. */
  readonly relay: RelayConfig;
  /** HMAC key for stateless OAuth client ids (also keys the encryption of pairing state at rest). */
  readonly relaySecret: Buffer;
  /**
   * `APK_URL`: where the setup page's Install button points, or null. Validated by the Node relay's
   * config (an http(s) URL without credentials or fragment), so both relays accept the same values.
   */
  readonly apkUrl: string | null;
  /**
   * `HEARTBEAT_TIMEOUT_MS`: a device that sends heartbeats (§3.4) but has been silent this long is
   * treated as offline.
   */
  readonly heartbeatTimeoutMs: number;
  /** Path prefix of PUBLIC_URL (`/relay`), or `''`. */
  readonly prefix: string;
}

/**
 * Setting a variable the Worker cannot honor is reported instead of silently ignored. `MAX_DEVICES`
 * caps the Node relay's in-memory registry; here every phone has its own Durable Object, and there
 * is no relay-wide count to check it against.
 */
const UNSUPPORTED = ['ALLOW_IMPLICIT_DEVICE', 'TRUST_PROXY', 'DATA_DIR', 'PUBLIC_DIR', 'MAX_DEVICES'] as const;

const cache = new WeakMap<object, WorkerConfig | ConfigError>();

/**
 * The validated configuration for [env], cached per isolate. Throws [ConfigError] (also on later
 * calls) when a value is invalid or RELAY_SECRET is missing: the Worker then answers every request
 * with 500 and the reason, the equivalent of the Node relay refusing to start.
 */
export function workerConfig(env: Env): WorkerConfig {
  let cached = cache.get(env);
  if (cached === undefined) {
    try {
      cached = parse(env);
    } catch (err) {
      if (!(err instanceof ConfigError)) throw err;
      cached = err;
    }
    cache.set(env, cached);
  }
  if (cached instanceof ConfigError) throw cached;
  return cached;
}

/** Names of set variables the Worker relay ignores (for a startup warning). */
export function unsupportedSettings(env: Env): string[] {
  return UNSUPPORTED.filter((name) => {
    const value = env[name];
    return typeof value === 'string' && value.trim() !== '' && !['0', 'false', 'no', 'off'].includes(value.trim().toLowerCase());
  });
}

function parse(env: Env): WorkerConfig {
  const strings: Record<string, string> = {};
  for (const [name, value] of Object.entries(env)) if (typeof value === 'string') strings[name] = value;
  // Node-only settings must not change behavior here (e.g. TRUST_PROXY, which would make the relay
  // trust X-Forwarded-For; Cloudflare supplies the client address itself).
  for (const name of UNSUPPORTED) delete strings[name];

  const base = loadConfigFromEnv(strings);
  if (base.relaySecret === null) {
    throw new ConfigError(
      'RELAY_SECRET must be set: it signs OAuth client ids. Run `npx wrangler secret put RELAY_SECRET` ' +
        '(use the output of `openssl rand -base64 32`), or put it in .dev.vars for `wrangler dev`.',
    );
  }
  const relay = resolveConfig({
    ...base,
    pairingTtlMs: int(strings, 'PAIRING_TTL_MS') ?? base.pairingTtlMs,
    authCodeTtlMs: int(strings, 'AUTH_CODE_TTL_MS') ?? base.authCodeTtlMs,
  });
  return {
    relay,
    relaySecret: Buffer.from(base.relaySecret, 'utf8'),
    apkUrl: relay.apkUrl,
    heartbeatTimeoutMs: int(strings, 'HEARTBEAT_TIMEOUT_MS') ?? HEARTBEAT_TIMEOUT_MS,
    prefix: relay.publicUrl === null ? '' : new URL(relay.publicUrl).pathname.replace(/\/+$/, ''),
  };
}

function int(env: Record<string, string>, name: string): number | undefined {
  const value = env[name]?.trim();
  if (value === undefined || value === '') return undefined;
  if (!/^\d+$/.test(value) || Number(value) < 1 || !Number.isSafeInteger(Number(value))) {
    throw new ConfigError(`${name} must be a positive integer, got "${value}"`);
  }
  return Number(value);
}
