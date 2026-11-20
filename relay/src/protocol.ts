/**
 * Device ⇄ relay tunnel frames and constants (docs/PROTOCOL.md §3).
 */

/** `hello.protocolVersion` understood by this relay. */
export const PROTOCOL_VERSION = 1;

/** Path of the device WebSocket endpoint. */
export const DEVICE_WS_PATH = '/v1/device';

/** Maximum WebSocket frame size, both directions, once the hello is accepted. */
export const MAX_FRAME_BYTES = 16 * 1024 * 1024;

/**
 * Most bytes a device socket may send before its hello is accepted (a hello is well under 1 KiB).
 * Beyond it the socket is dropped, so unauthenticated clients cannot make the relay buffer large
 * frames.
 */
export const MAX_PRE_HELLO_BYTES = 16 * 1024;

export const CloseCode = Object.freeze({
  GOING_AWAY: 1001,
  BAD_REQUEST: 4000,
  UNAUTHORIZED: 4001,
  PROTOCOL_VERSION: 4002,
  HELLO_TIMEOUT: 4008,
  REPLACED: 4009,
  /** MAX_DEVICES phones are connected already; retry later with backoff (like 1013 "Try Again Later"). */
  RELAY_FULL: 4013,
  /** The relay can't check accounts right now (`account_unavailable`); retry with backoff. */
  TRY_AGAIN_LATER: 1013,
});

/**
 * Application-level heartbeat (§3.4): once welcomed, the device sends exactly this text every
 * [HEARTBEAT_INTERVAL_MS] and the relay answers [HEARTBEAT_ACK_FRAME]. The bytes are fixed so a
 * relay can answer without parsing (Cloudflare: `setWebSocketAutoResponse`, which does not wake a
 * hibernated Durable Object).
 */
export const HEARTBEAT_FRAME = '{"type":"hb"}';
export const HEARTBEAT_ACK_FRAME = '{"type":"hb_ack"}';
export const HEARTBEAT_INTERVAL_MS = 30_000;
/** A relay may treat a device as offline after this long without any frame or heartbeat from it. */
export const HEARTBEAT_TIMEOUT_MS = 75_000;

export type HandshakeErrorCode =
  | 'unauthorized'
  | 'bad_request'
  | 'protocol_version'
  | 'access_key'
  | 'relay_full'
  | 'account_required'
  | 'account_unavailable';

/** First frame from the device. Optional descriptive fields are informational only. */
export interface HelloFrame {
  type: 'hello';
  protocolVersion: number;
  deviceId: string;
  deviceSecret: string;
  platform?: string;
  appVersion?: string;
  deviceName?: string;
  accessKey?: string;
  /** Relay token from the API server, when the relay requires accounts (§3.1, §7.3). */
  accountToken?: string;
}

export interface WelcomeFrame {
  type: 'welcome';
  deviceId: string;
  mcpUrl: string;
  /** The device connector URL `{relay}/d/<deviceId>/mcp` (§5.1), shown as "Your connection URL". */
  connectUrl: string;
  serverTime: number;
}

export interface ErrorFrame {
  type: 'error';
  code: HandshakeErrorCode;
  message: string;
}

/** A tunneled HTTP request (relay → device). Header names are lowercase. */
export interface RequestFrame {
  type: 'request';
  id: string;
  method: 'POST';
  path: '/mcp';
  headers: Record<string, string>;
  body: string;
  remoteAddress: string;
}

/** A tunneled HTTP response (device → relay), after validation. */
export interface DeviceResponse {
  status: number;
  /** Lowercase names, string values only. */
  headers: Record<string, string>;
  /** null for an empty body (e.g. 202). */
  body: string | null;
}

/**
 * Headers forwarded from the agent's request to the device, when present (§3.2). Everything else
 * (cookies, proxy headers, Host, …) is dropped.
 */
export const FORWARDED_REQUEST_HEADERS = Object.freeze([
  'authorization',
  'content-type',
  'accept',
  'mcp-protocol-version',
  'mcp-session-id',
  'user-agent',
  'origin',
] as const);

/** Headers copied from the device's response to the agent (§3.3). */
export const SAFE_RESPONSE_HEADERS = Object.freeze([
  'content-type',
  'mcp-session-id',
  'www-authenticate',
  'retry-after',
] as const);

/** Parses a text frame into a JSON object with a string `type`, or null. */
export function parseFrame(text: string): (Record<string, unknown> & { type: string }) | null {
  let value: unknown;
  try {
    value = JSON.parse(text);
  } catch {
    return null;
  }
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return null;
  const type = (value as Record<string, unknown>).type;
  return typeof type === 'string' ? (value as Record<string, unknown> & { type: string }) : null;
}

/**
 * Validates a `response` frame. Returns the request id with either the sanitized response or
 * null when the frame is malformed (the pending request then fails with 502).
 */
export function parseResponseFrame(frame: Record<string, unknown>): { id: string; response: DeviceResponse | null } | null {
  const { id, status, headers, body } = frame;
  if (typeof id !== 'string') return null;
  if (typeof status !== 'number' || !Number.isInteger(status) || status < 200 || status > 599) {
    return { id, response: null };
  }
  if (body !== undefined && body !== null && typeof body !== 'string') return { id, response: null };
  if (headers !== undefined && headers !== null && (typeof headers !== 'object' || Array.isArray(headers))) {
    return { id, response: null };
  }
  const cleanHeaders: Record<string, string> = {};
  for (const [name, value] of Object.entries((headers ?? {}) as Record<string, unknown>)) {
    if (typeof value === 'string') cleanHeaders[name.toLowerCase()] = value;
  }
  return { id, response: { status, headers: cleanHeaders, body: typeof body === 'string' ? body : null } };
}

/** `{"jsonrpc":"2.0","id":null,"error":{code,message}}` as sent by the relay for its own errors. */
export function jsonRpcError(code: number, message: string): string {
  return JSON.stringify({ jsonrpc: '2.0', id: null, error: { code, message } });
}

/** JSON-RPC error codes used by the relay. */
export const RpcCode = Object.freeze({
  /** Relay/transport failure (offline, timeout, overload). */
  SERVER_ERROR: -32000,
  UNAUTHORIZED: -32001,
});
