import { constantTimeEqual, verifyDeviceIdentity } from './encoding.js';
import { connectorUrl } from './oauth/metadata.js';
import {
  CloseCode,
  PROTOCOL_VERSION,
  parseFrame,
  type HandshakeErrorCode,
  type WelcomeFrame,
} from './protocol.js';
import { sanitizeDisplayText } from './text.js';

/**
 * The device hello check (docs/PROTOCOL.md §3.1), shared by the Node relay and the Cloudflare
 * Worker relay so both accept and refuse exactly the same handshakes. Pure.
 */

/** Optional descriptive hello fields; if present they must be strings. */
const OPTIONAL_STRING_FIELDS = ['platform', 'appVersion', 'deviceName', 'accessKey'] as const;

/** Longest device name kept for the pairing page. */
const MAX_DEVICE_NAME_LENGTH = 60;

/** Query parameter of the device URL naming the device (`/v1/device?deviceId=<id>`, §3.1). */
export const DEVICE_ID_PARAM = 'deviceId';

/** Descriptive, non-secret data from an accepted hello. */
export interface HelloInfo {
  /** For logs. */
  readonly platform: string | undefined;
  /** For logs. */
  readonly appVersion: string | undefined;
  /** Shown on the pairing page ("Open DroidHands on <deviceName>"); never logged. */
  readonly deviceName: string | null;
}

export interface HelloOptions {
  /** RELAY_ACCESS_KEY, or null when the relay is open. */
  readonly accessKey: string | null;
  /**
   * The `deviceId` query parameter of the connection URL, or null when absent. A hello for another
   * device is refused (`bad_request`), even with a valid secret.
   */
  readonly urlDeviceId: string | null;
}

export type HelloResult =
  | { readonly ok: true; readonly deviceId: string; readonly info: HelloInfo }
  | {
      readonly ok: false;
      readonly code: HandshakeErrorCode;
      readonly closeCode: number;
      readonly message: string;
      /** Counts toward the client's authentication-failure limit. */
      readonly authFailure: boolean;
    };

/** Validates the first frame of a device socket ([text] null for a binary frame). */
export function checkHello(text: string | null, options: HelloOptions): HelloResult {
  const refuse = (code: HandshakeErrorCode, closeCode: number, message: string, authFailure = false): HelloResult => ({
    ok: false,
    code,
    closeCode,
    message,
    authFailure,
  });
  const hello = text === null ? null : parseFrame(text);
  if (hello === null || hello.type !== 'hello') {
    return refuse('bad_request', CloseCode.BAD_REQUEST, 'The first frame must be a JSON "hello" object.');
  }
  if (hello.protocolVersion !== PROTOCOL_VERSION) {
    return refuse(
      'protocol_version',
      CloseCode.PROTOCOL_VERSION,
      `Unsupported protocolVersion; this relay speaks version ${PROTOCOL_VERSION}.`,
    );
  }
  const { deviceId, deviceSecret } = hello;
  if (typeof deviceId !== 'string' || typeof deviceSecret !== 'string') {
    return refuse('bad_request', CloseCode.BAD_REQUEST, 'hello.deviceId and hello.deviceSecret must be strings.');
  }
  if (OPTIONAL_STRING_FIELDS.some((f) => hello[f] !== undefined && typeof hello[f] !== 'string')) {
    return refuse('bad_request', CloseCode.BAD_REQUEST, `hello.${OPTIONAL_STRING_FIELDS.join('/')} must be strings.`);
  }
  if (!verifyDeviceIdentity(deviceId, deviceSecret)) {
    return refuse('unauthorized', CloseCode.UNAUTHORIZED, 'deviceId does not match deviceSecret.', true);
  }
  if (options.urlDeviceId !== null && options.urlDeviceId !== deviceId) {
    return refuse(
      'bad_request',
      CloseCode.BAD_REQUEST,
      `hello.deviceId does not match the ${DEVICE_ID_PARAM} parameter of the connection URL.`,
    );
  }
  if (options.accessKey !== null) {
    const key = hello.accessKey;
    if (typeof key !== 'string' || !constantTimeEqual(key, options.accessKey)) {
      return refuse('access_key', CloseCode.UNAUTHORIZED, 'This relay requires a valid access key.', true);
    }
  }
  return {
    ok: true,
    deviceId,
    info: {
      platform: shortString(hello.platform),
      appVersion: shortString(hello.appVersion),
      deviceName: displayName(hello.deviceName),
    },
  };
}

/** The `welcome` frame for an accepted device, with URLs under the public base [base]. */
export function welcomeFrame(base: string, deviceId: string, now: number): WelcomeFrame {
  return {
    type: 'welcome',
    deviceId,
    mcpUrl: `${base}/mcp`,
    connectUrl: connectorUrl(base, deviceId),
    serverTime: now,
  };
}

/**
 * The hello's device name for display: control, bidi and line-separator characters removed,
 * whitespace collapsed, at most [MAX_DEVICE_NAME_LENGTH] code points. null when empty.
 */
function displayName(value: unknown): string | null {
  if (typeof value !== 'string') return null;
  const name = sanitizeDisplayText(value, MAX_DEVICE_NAME_LENGTH);
  return name === '' ? null : name;
}

/** Bounded, printable form of an informational hello field for logs. */
function shortString(value: unknown): string | undefined {
  return typeof value === 'string' ? value.replace(/[^\x20-\x7e]/g, '?').slice(0, 40) : undefined;
}
