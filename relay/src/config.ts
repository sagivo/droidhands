import { LOG_LEVELS, type LogLevel } from './logger.js';

/** Relay configuration. See relay/README.md for the matching environment variables. */
export interface RelayConfig {
  /** TCP port to listen on (0 = ephemeral, used by tests). */
  readonly port: number;
  readonly host: string;
  /**
   * Public base URL (scheme + host [+ path prefix]) without trailing slash. `welcome.mcpUrl` is
   * `${publicUrl}/mcp`. When null it is derived from the device's upgrade request.
   */
  readonly publicUrl: string | null;
  /** Trust X-Forwarded-For/-Proto/-Host from a reverse proxy in front of the relay. */
  readonly trustProxy: boolean;
  /** When set, devices must present it as `hello.accessKey`. */
  readonly accessKey: string | null;
  /** Accept `POST /mcp/<token>` for clients that cannot send headers. */
  readonly allowTokenInUrl: boolean;
  /** Allowed `Origin` values (normalized, lowercase). `*` allows any. Empty = reject any Origin. */
  readonly allowedOrigins: readonly string[];
  readonly requestTimeoutMs: number;
  readonly maxBodyBytes: number;
  readonly maxInflightPerDevice: number;
  readonly helloTimeoutMs: number;
  readonly logLevel: LogLevel;
  /** WebSocket-level ping interval; a device missing 2 consecutive pongs is dropped. */
  readonly heartbeatIntervalMs: number;
  /** Per-IP request budget per minute on the MCP endpoint. */
  readonly rateLimitRequestsPerMin: number;
  /** Per-IP authentication failures per minute before the IP is blocked for a minute. */
  readonly rateLimitAuthFailuresPerMin: number;
}

export const DEFAULT_CONFIG: RelayConfig = Object.freeze({
  port: 8787,
  host: '0.0.0.0',
  publicUrl: null,
  trustProxy: false,
  accessKey: null,
  allowTokenInUrl: false,
  allowedOrigins: [],
  requestTimeoutMs: 150_000,
  maxBodyBytes: 1_048_576,
  maxInflightPerDevice: 32,
  helloTimeoutMs: 10_000,
  logLevel: 'info',
  heartbeatIntervalMs: 25_000,
  rateLimitRequestsPerMin: 600,
  rateLimitAuthFailuresPerMin: 30,
});

/** Largest delay `setTimeout` accepts. */
const MAX_TIMER_MS = 2_147_483_647;

/**
 * Upper bound for MAX_BODY_BYTES: a tunneled request (body JSON-escaped inside the frame) must stay
 * below the 16 MiB WebSocket frame limit.
 */
const MAX_BODY_LIMIT = 8 * 1024 * 1024;

/** Environment variable behind each field, for error messages. */
const ENV_NAMES: Partial<Record<keyof RelayConfig, string>> = {
  port: 'PORT',
  publicUrl: 'PUBLIC_URL',
  requestTimeoutMs: 'REQUEST_TIMEOUT_MS',
  maxBodyBytes: 'MAX_BODY_BYTES',
  maxInflightPerDevice: 'MAX_INFLIGHT_PER_DEVICE',
  helloTimeoutMs: 'HELLO_TIMEOUT_MS',
  logLevel: 'LOG_LEVEL',
  rateLimitRequestsPerMin: 'RATE_LIMIT_REQUESTS_PER_MIN',
  rateLimitAuthFailuresPerMin: 'RATE_LIMIT_AUTH_FAILURES_PER_MIN',
};

function label(field: keyof RelayConfig): string {
  const env = ENV_NAMES[field];
  return env === undefined ? field : `${field} (${env})`;
}

/** Thrown for invalid configuration values; the message names the offending variable. */
export class ConfigError extends Error {
  override name = 'ConfigError';
}

/** Builds a complete, validated config from defaults plus [overrides] (used by tests and embedders). */
export function resolveConfig(overrides: Partial<RelayConfig> = {}): RelayConfig {
  const merged: RelayConfig = { ...DEFAULT_CONFIG, ...stripUndefined(overrides) };
  return Object.freeze({
    ...merged,
    publicUrl: merged.publicUrl === null ? null : normalizePublicUrl(merged.publicUrl, label('publicUrl')),
    accessKey: merged.accessKey === '' ? null : merged.accessKey,
    allowedOrigins: Object.freeze(merged.allowedOrigins.map(normalizeOrigin).filter((o) => o !== '')),
    port: checkInt(merged.port, label('port'), 0, 65_535),
    requestTimeoutMs: checkInt(merged.requestTimeoutMs, label('requestTimeoutMs'), 1, MAX_TIMER_MS),
    maxBodyBytes: checkInt(merged.maxBodyBytes, label('maxBodyBytes'), 1, MAX_BODY_LIMIT),
    maxInflightPerDevice: checkInt(merged.maxInflightPerDevice, label('maxInflightPerDevice'), 1),
    helloTimeoutMs: checkInt(merged.helloTimeoutMs, label('helloTimeoutMs'), 1, MAX_TIMER_MS),
    heartbeatIntervalMs: checkInt(merged.heartbeatIntervalMs, label('heartbeatIntervalMs'), 1, MAX_TIMER_MS),
    rateLimitRequestsPerMin: checkInt(merged.rateLimitRequestsPerMin, label('rateLimitRequestsPerMin'), 1),
    rateLimitAuthFailuresPerMin: checkInt(merged.rateLimitAuthFailuresPerMin, label('rateLimitAuthFailuresPerMin'), 1),
    logLevel: checkLogLevel(merged.logLevel, label('logLevel')),
  });
}

/** Reads the configuration from environment variables (see README.md). Throws [ConfigError]. */
export function loadConfigFromEnv(env: NodeJS.ProcessEnv = process.env): RelayConfig {
  const str = (name: string): string | undefined => {
    const value = env[name]?.trim();
    return value === undefined || value === '' ? undefined : value;
  };
  const int = (name: string): number | undefined => {
    const value = str(name);
    if (value === undefined) return undefined;
    if (!/^\d+$/.test(value)) throw new ConfigError(`${name} must be a non-negative integer, got "${value}"`);
    return Number(value);
  };
  const bool = (name: string): boolean | undefined => {
    const value = str(name)?.toLowerCase();
    if (value === undefined) return undefined;
    if (['1', 'true', 'yes', 'on'].includes(value)) return true;
    if (['0', 'false', 'no', 'off'].includes(value)) return false;
    throw new ConfigError(`${name} must be true or false, got "${value}"`);
  };

  const publicUrl = str('PUBLIC_URL');
  const logLevel = str('LOG_LEVEL');
  return resolveConfig({
    port: int('PORT'),
    host: str('HOST'),
    publicUrl: publicUrl === undefined ? undefined : normalizePublicUrl(publicUrl, 'PUBLIC_URL'),
    trustProxy: bool('TRUST_PROXY'),
    // Not trimmed: an access key is compared verbatim.
    accessKey: env.RELAY_ACCESS_KEY ? env.RELAY_ACCESS_KEY : undefined,
    allowTokenInUrl: bool('ALLOW_TOKEN_IN_URL'),
    allowedOrigins: str('ALLOWED_ORIGINS')?.split(','),
    requestTimeoutMs: int('REQUEST_TIMEOUT_MS'),
    maxBodyBytes: int('MAX_BODY_BYTES'),
    maxInflightPerDevice: int('MAX_INFLIGHT_PER_DEVICE'),
    helloTimeoutMs: int('HELLO_TIMEOUT_MS'),
    logLevel: logLevel === undefined ? undefined : checkLogLevel(logLevel.toLowerCase(), 'LOG_LEVEL'),
    rateLimitRequestsPerMin: int('RATE_LIMIT_REQUESTS_PER_MIN'),
    rateLimitAuthFailuresPerMin: int('RATE_LIMIT_AUTH_FAILURES_PER_MIN'),
  });
}

/**
 * Lowercases and strips a trailing slash so `https://Example.com/` matches the browser-sent
 * `https://example.com`. `*` is kept as the wildcard.
 */
export function normalizeOrigin(origin: string): string {
  return origin.trim().replace(/\/+$/, '').toLowerCase();
}

/** True when an `Origin` header value is acceptable under [allowedOrigins]. */
export function isOriginAllowed(origin: string, allowedOrigins: readonly string[]): boolean {
  if (allowedOrigins.includes('*')) return true;
  return allowedOrigins.includes(normalizeOrigin(origin));
}

function normalizePublicUrl(value: string, name: string): string {
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    throw new ConfigError(`${name} must be an absolute http(s) URL, got "${value}"`);
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    throw new ConfigError(`${name} must use http or https, got "${url.protocol}"`);
  }
  if (url.search !== '' || url.hash !== '' || url.username !== '' || url.password !== '') {
    throw new ConfigError(`${name} must not contain credentials, a query or a fragment`);
  }
  return `${url.origin}${url.pathname}`.replace(/\/+$/, '');
}

function checkInt(value: number, name: string, min: number, max = Number.MAX_SAFE_INTEGER): number {
  if (!Number.isSafeInteger(value) || value < min || value > max) {
    throw new ConfigError(`${name} must be an integer in [${min}, ${max}], got ${value}`);
  }
  return value;
}

function checkLogLevel(value: string, name: string): LogLevel {
  if (!(LOG_LEVELS as readonly string[]).includes(value)) {
    throw new ConfigError(`${name} must be one of ${LOG_LEVELS.join(', ')}, got "${value}"`);
  }
  return value as LogLevel;
}

function stripUndefined<T extends object>(value: T): Partial<T> {
  return Object.fromEntries(Object.entries(value).filter(([, v]) => v !== undefined)) as Partial<T>;
}
