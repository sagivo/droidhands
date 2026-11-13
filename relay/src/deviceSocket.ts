import type { IncomingMessage } from 'node:http';
import WebSocket from 'ws';
import type { RelayConfig } from './config.js';
import { DeviceConnection } from './deviceConnection.js';
import type { DeviceRegistry } from './deviceRegistry.js';
import { constantTimeEqual, verifyDeviceIdentity } from './encoding.js';
import { deviceTag, errorMessage, type Logger } from './logger.js';
import { clientAddress, publicBaseUrl, rateLimitKey } from './net.js';
import {
  CloseCode,
  PROTOCOL_VERSION,
  parseFrame,
  type ErrorFrame,
  type HandshakeErrorCode,
  type WelcomeFrame,
} from './protocol.js';
import type { RateLimiter } from './rateLimit.js';

export interface DeviceSocketDeps {
  readonly config: RelayConfig;
  readonly logger: Logger;
  readonly registry: DeviceRegistry;
  readonly limiter: RateLimiter;
  /** Every open device socket, authenticated or not (used for shutdown). */
  readonly sockets: Set<WebSocket>;
}

/** Optional descriptive hello fields; if present they must be strings. */
const OPTIONAL_STRING_FIELDS = ['platform', 'appVersion', 'deviceName', 'accessKey'] as const;

/**
 * Drives one device WebSocket from the hello handshake (§3.1) to close: authenticates the device,
 * registers it (replacing an older connection), then dispatches `response` and `ping` frames.
 */
export function acceptDeviceSocket(ws: WebSocket, req: IncomingMessage, deps: DeviceSocketDeps): void {
  const { config, logger, registry, limiter, sockets } = deps;
  const ip = clientAddress(req, config.trustProxy);
  const mcpUrl = `${publicBaseUrl(req, config.publicUrl, config.trustProxy)}/mcp`;
  let state: 'hello' | 'open' | 'closed' = 'hello';
  let connection: DeviceConnection | null = null;
  sockets.add(ws);

  const helloTimer = setTimeout(() => {
    if (state !== 'hello') return;
    state = 'closed';
    logger.info('device hello timeout', { ip });
    ws.close(CloseCode.HELLO_TIMEOUT, 'hello timeout');
  }, config.helloTimeoutMs);

  const reject = (code: HandshakeErrorCode, closeCode: number, message: string): void => {
    state = 'closed';
    clearTimeout(helloTimer);
    logger.info('device rejected', { ip, code });
    const frame: ErrorFrame = { type: 'error', code, message };
    ws.send(JSON.stringify(frame));
    ws.close(closeCode, code);
  };

  const onHello = (text: string | null): void => {
    const hello = text === null ? null : parseFrame(text);
    if (hello === null || hello.type !== 'hello') {
      reject('bad_request', CloseCode.BAD_REQUEST, 'The first frame must be a JSON "hello" object.');
      return;
    }
    if (hello.protocolVersion !== PROTOCOL_VERSION) {
      reject(
        'protocol_version',
        CloseCode.PROTOCOL_VERSION,
        `Unsupported protocolVersion; this relay speaks version ${PROTOCOL_VERSION}.`,
      );
      return;
    }
    const { deviceId, deviceSecret } = hello;
    if (typeof deviceId !== 'string' || typeof deviceSecret !== 'string') {
      reject('bad_request', CloseCode.BAD_REQUEST, 'hello.deviceId and hello.deviceSecret must be strings.');
      return;
    }
    if (OPTIONAL_STRING_FIELDS.some((f) => hello[f] !== undefined && typeof hello[f] !== 'string')) {
      reject('bad_request', CloseCode.BAD_REQUEST, `hello.${OPTIONAL_STRING_FIELDS.join('/')} must be strings.`);
      return;
    }
    if (!verifyDeviceIdentity(deviceId, deviceSecret)) {
      limiter.recordAuthFailure(rateLimitKey(ip));
      reject('unauthorized', CloseCode.UNAUTHORIZED, 'deviceId does not match deviceSecret.');
      return;
    }
    if (config.accessKey !== null) {
      const key = hello.accessKey;
      if (typeof key !== 'string' || !constantTimeEqual(key, config.accessKey)) {
        limiter.recordAuthFailure(rateLimitKey(ip));
        reject('access_key', CloseCode.UNAUTHORIZED, 'This relay requires a valid access key.');
        return;
      }
    }
    if (ws.readyState !== WebSocket.OPEN) {
      state = 'closed';
      return;
    }

    state = 'open';
    clearTimeout(helloTimer);
    const info = { platform: shortString(hello.platform), appVersion: shortString(hello.appVersion) };
    connection = new DeviceConnection(deviceId, ws, config.requestTimeoutMs, info);
    const replaced = registry.register(connection);
    if (replaced !== undefined) {
      replaced.close(CloseCode.REPLACED, 'replaced by a newer connection', 'replaced');
      logger.info('device connection replaced', { device: deviceTag(deviceId) });
    }
    const welcome: WelcomeFrame = { type: 'welcome', deviceId, mcpUrl, serverTime: Date.now() };
    ws.send(JSON.stringify(welcome));
    logger.info('device connected', { device: deviceTag(deviceId), ip, ...info, devices: registry.size });
  };

  const onFrame = (conn: DeviceConnection, text: string | null): void => {
    const frame = text === null ? null : parseFrame(text);
    if (frame === null) {
      logger.debug('ignored non-JSON or binary device frame', { device: deviceTag(conn.deviceId) });
      return;
    }
    switch (frame.type) {
      case 'response':
        if (!conn.handleResponse(frame)) {
          logger.debug('ignored response for unknown request', { device: deviceTag(conn.deviceId) });
        }
        return;
      case 'ping':
        ws.send(JSON.stringify({ type: 'pong', t: frame.t }));
        return;
      case 'pong':
        return;
      default:
        logger.debug('ignored device frame', { device: deviceTag(conn.deviceId), type: frame.type.slice(0, 32) });
    }
  };

  ws.on('message', (data, isBinary) => {
    const text = isBinary ? null : rawToString(data);
    if (state === 'hello') onHello(text);
    else if (state === 'open' && connection !== null) onFrame(connection, text);
  });

  ws.on('pong', () => connection?.onPong());

  ws.on('close', (code) => {
    clearTimeout(helloTimer);
    state = 'closed';
    sockets.delete(ws);
    if (connection === null) return;
    registry.unregister(connection);
    connection.onSocketClosed();
    logger.info('device disconnected', {
      device: deviceTag(connection.deviceId),
      code,
      connectedSec: Math.round((Date.now() - connection.connectedAt) / 1000),
      devices: registry.size,
    });
  });

  ws.on('error', (err) => {
    logger.debug('device socket error', {
      device: connection === null ? undefined : deviceTag(connection.deviceId),
      error: errorMessage(err),
    });
  });
}

function rawToString(data: WebSocket.RawData): string {
  if (Buffer.isBuffer(data)) return data.toString('utf8');
  if (Array.isArray(data)) return Buffer.concat(data).toString('utf8');
  return Buffer.from(data).toString('utf8');
}

/** Bounded, printable form of an informational hello field for logs. */
function shortString(value: unknown): string | undefined {
  return typeof value === 'string' ? value.replace(/[^\x20-\x7e]/g, '?').slice(0, 40) : undefined;
}
