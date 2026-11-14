import { fileURLToPath } from 'node:url';
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
  /**
   * Allowed `Origin` values (normalized, lowercase). `*` allows any. Empty = reject any Origin.
   * MCP responses to an allowed Origin carry CORS headers for it.
   */
  readonly allowedOrigins: readonly string[];
  /**
   * Legacy: when `/oauth/authorize` has no `resource`, pair with the only online device instead of
   * showing an error. Off by default, because then anyone who knows the relay URL can send pairing
   * prompts to a personal relay's phone.
   */
  readonly allowImplicitDevice: boolean;
  readonly requestTimeoutMs: number;
  readonly maxBodyBytes: number;
  readonly maxInflightPerDevice: number;
  readonly helloTimeoutMs: number;
  /** Device sockets that may wait for their hello at the same time, relay-wide (§3.1). */
  readonly maxPendingHandshakes: number;
  /** Device sockets that may wait for their hello at the same time, per client IP. */
  readonly maxPendingHandshakesPerIp: number;
  readonly logLevel: LogLevel;
  /** WebSocket-level ping interval; a device missing 2 consecutive pongs is dropped. */
  readonly heartbeatIntervalMs: number;
  /** Per-IP request budget per minute on the MCP endpoint. */
  readonly rateLimitRequestsPerMin: number;
  /** Per-IP authentication failures per minute before the IP is blocked for a minute. */
  readonly rateLimitAuthFailuresPerMin: number;
  /**
   * HMAC key for stateless OAuth client ids (docs/PROTOCOL.md §5.2). When null, the relay loads or
   * creates `{dataDir}/relay-secret`. Never log it.
   */
  readonly relaySecret: string | null;
  /** Directory for the persisted relay secret. null = do not persist (temporary secret). */
  readonly dataDir: string | null;
  /** Directory the operator may put `phonebridge.apk` in, served at `/download/phonebridge.apk`. */
  readonly publicDir: string;
  /** Print the setup URL and a QR code on startup (CLI only). */
  readonly setupBanner: boolean;
  /** How long a pairing waits for the phone's answer (§5.3: 180 s). */
  readonly pairingTtlMs: number;
  /** Lifetime of an authorization code (§5.3: 120 s). */
  readonly authCodeTtlMs: number;
}

export const DEFAULT_CONFIG: RelayConfig = Object.freeze({
  port: 8787,
  host: '0.0.0.0',
  publicUrl: null,
  trustProxy: false,
  accessKey: null,
  allowTokenInUrl: false,
  allowedOrigins: [],
  allowImplicitDevice: false,
  requestTimeoutMs: 150_000,
  maxBodyBytes: 1_048_576,
  maxInflightPerDevice: 32,
  helloTimeoutMs: 10_000,
  maxPendingHandshakes: 512,
  maxPendingHandshakesPerIp: 16,
  logLevel: 'info',
  heartbeatIntervalMs: 25_000,
  rateLimitRequestsPerMin: 600,
  rateLimitAuthFailuresPerMin: 30,
  relaySecret: null,
  dataDir: './data',
  publicDir: fileURLToPath(new URL('../public', import.meta.url)),
  setupBanner: true,
  pairingTtlMs: 180_000,
  authCodeTtlMs: 120_000,
});

/** Shortest accepted RELAY_SECRET; it keys the HMAC that protects OAuth client ids. */
export const MIN_RELAY_SECRET_LENGTH = 32;

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
  maxPendingHandshakes: 'MAX_PENDING_HANDSHAKES',
  maxPendingHandshakesPerIp: 'MAX_PENDING_HANDSHAKES_PER_IP',
  logLevel: 'LOG_LEVEL',
  rateLimitRequestsPerMin: 'RATE_LIMIT_REQUESTS_PER_MIN',
  rateLimitAuthFailuresPerMin: 'RATE_LIMIT_AUTH_FAILURES_PER_MIN',
  relaySecret: 'RELAY_SECRET',
  dataDir: 'DATA_DIR',
  publicDir: 'PUBLIC_DIR',
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
    maxPendingHandshakes: checkInt(merged.maxPendingHandshakes, label('maxPendingHandshakes'), 1),
    maxPendingHandshakesPerIp: checkInt(merged.maxPendingHandshakesPerIp, label('maxPendingHandshakesPerIp'), 1),
    heartbeatIntervalMs: checkInt(merged.heartbeatIntervalMs, label('heartbeatIntervalMs'), 1, MAX_TIMER_MS),
    rateLimitRequestsPerMin: checkInt(merged.rateLimitRequestsPerMin, label('rateLimitRequestsPerMin'), 1),
    rateLimitAuthFailuresPerMin: checkInt(merged.rateLimitAuthFailuresPerMin, label('rateLimitAuthFailuresPerMin'), 1),
    logLevel: checkLogLevel(merged.logLevel, label('logLevel')),
    relaySecret: checkRelaySecret(merged.relaySecret, label('relaySecret')),
    dataDir: merged.dataDir === '' ? null : merged.dataDir,
    publicDir: checkNonEmpty(merged.publicDir, label('publicDir')),
    pairingTtlMs: checkInt(merged.pairingTtlMs, label('pairingTtlMs'), 1, MAX_TIMER_MS),
    authCodeTtlMs: checkInt(merged.authCodeTtlMs, label('authCodeTtlMs'), 1, MAX_TIMER_MS),
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

  const platform = platformPublicUrl(env);
  const publicUrl = str('PUBLIC_URL') ?? platform?.url;
  const publicUrlName = str('PUBLIC_URL') === undefined && platform !== undefined ? platform.variable : 'PUBLIC_URL';
  const logLevel = str('LOG_LEVEL');
  return resolveConfig({
    port: int('PORT'),
    host: str('HOST'),
    publicUrl: publicUrl === undefined ? undefined : normalizePublicUrl(publicUrl, publicUrlName),
    trustProxy: bool('TRUST_PROXY'),
    // Not trimmed: an access key is compared verbatim.
    accessKey: env.RELAY_ACCESS_KEY ? env.RELAY_ACCESS_KEY : undefined,
    allowTokenInUrl: bool('ALLOW_TOKEN_IN_URL'),
    allowedOrigins: str('ALLOWED_ORIGINS')?.split(','),
    allowImplicitDevice: bool('ALLOW_IMPLICIT_DEVICE'),
    requestTimeoutMs: int('REQUEST_TIMEOUT_MS'),
    maxBodyBytes: int('MAX_BODY_BYTES'),
    maxInflightPerDevice: int('MAX_INFLIGHT_PER_DEVICE'),
    helloTimeoutMs: int('HELLO_TIMEOUT_MS'),
    maxPendingHandshakes: int('MAX_PENDING_HANDSHAKES'),
    maxPendingHandshakesPerIp: int('MAX_PENDING_HANDSHAKES_PER_IP'),
    logLevel: logLevel === undefined ? undefined : checkLogLevel(logLevel.toLowerCase(), 'LOG_LEVEL'),
    rateLimitRequestsPerMin: int('RATE_LIMIT_REQUESTS_PER_MIN'),
    rateLimitAuthFailuresPerMin: int('RATE_LIMIT_AUTH_FAILURES_PER_MIN'),
    // Not trimmed: a secret is used verbatim.
    relaySecret: env.RELAY_SECRET ? env.RELAY_SECRET : undefined,
    dataDir: str('DATA_DIR'),
    publicDir: str('PUBLIC_DIR'),
    setupBanner: bool('SETUP_BANNER'),
  });
}

/**
 * The public URL announced by a hosting platform, used when PUBLIC_URL is unset so one-click deploys
 * print a correct setup link: Render's `RENDER_EXTERNAL_URL`, or `https://<FLY_APP_NAME>.fly.dev`.
 */
function platformPublicUrl(env: NodeJS.ProcessEnv): { url: string; variable: string } | undefined {
  const render = env.RENDER_EXTERNAL_URL?.trim();
  if (render) return { url: render, variable: 'RENDER_EXTERNAL_URL' };
  const fly = env.FLY_APP_NAME?.trim();
  if (fly && /^[a-z0-9-]+$/.test(fly)) return { url: `https://${fly}.fly.dev`, variable: 'FLY_APP_NAME' };
  return undefined;
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

function checkRelaySecret(value: string | null, name: string): string | null {
  if (value === null || value === '') return null;
  if (value.length < MIN_RELAY_SECRET_LENGTH) {
    throw new ConfigError(
      `${name} must be at least ${MIN_RELAY_SECRET_LENGTH} characters (try: openssl rand -base64 32)`,
    );
  }
  return value;
}

function checkNonEmpty(value: string, name: string): string {
  if (value.trim() === '') throw new ConfigError(`${name} must not be empty`);
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
