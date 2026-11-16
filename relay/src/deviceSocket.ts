import type { IncomingMessage } from 'node:http';
import WebSocket from 'ws';
import type { RelayConfig } from './config.js';
import { DeviceConnection } from './deviceConnection.js';
import type { DeviceRegistry } from './deviceRegistry.js';
import { DEVICE_ID_PARAM, checkHello, welcomeFrame } from './handshake.js';
import { requestUrl } from './httpUtil.js';
import { deviceTag, errorMessage, type Logger } from './logger.js';
import { clientAddress, publicBaseUrl, rateLimitKey } from './net.js';
import type { PairingStore } from './oauth/pairingStore.js';
import { CloseCode, HEARTBEAT_ACK_FRAME, parseFrame, type ErrorFrame, type HandshakeErrorCode } from './protocol.js';
import type { RateLimiter } from './rateLimit.js';

export interface DeviceSocketDeps {
  readonly config: RelayConfig;
  readonly logger: Logger;
  readonly registry: DeviceRegistry;
  readonly limiter: RateLimiter;
  /** Every open device socket, authenticated or not (used for shutdown). */
  readonly sockets: Set<WebSocket>;
  readonly pairings: PairingStore;
}

/**
 * How long a socket refused during the handshake may take to complete the close handshake before
 * it is dropped (ws itself would wait 30 s).
 */
const HANDSHAKE_CLOSE_GRACE_MS = 2_000;

/**
 * Drives one device WebSocket from the hello handshake (§3.1) to close: authenticates the device,
 * registers it (replacing an older connection), then dispatches `response`, `pair_response` and
 * `ping` frames. [onAuthenticated] runs once the hello is accepted.
 */
export function acceptDeviceSocket(
  ws: WebSocket,
  req: IncomingMessage,
  deps: DeviceSocketDeps,
  onAuthenticated: () => void = () => {},
): void {
  const { config, logger, registry, limiter, sockets, pairings } = deps;
  const ip = clientAddress(req, config.trustProxy);
  const base = publicBaseUrl(req, config.publicUrl, config.trustProxy);
  // Optional (§3.1): the Node relay needs no routing hint, but a hello for another device is refused.
  const urlDeviceId = requestUrl(req.url)?.searchParams.get(DEVICE_ID_PARAM) ?? null;
  let state: 'hello' | 'open' | 'closed' = 'hello';
  let connection: DeviceConnection | null = null;
  sockets.add(ws);

  const helloTimer = setTimeout(() => {
    if (state !== 'hello') return;
    state = 'closed';
    logger.info('device hello timeout', { ip });
    closeHandshake(ws, CloseCode.HELLO_TIMEOUT, 'hello timeout');
  }, config.helloTimeoutMs);

  const reject = (code: HandshakeErrorCode, closeCode: number, message: string): void => {
    state = 'closed';
    clearTimeout(helloTimer);
    logger.info('device rejected', { ip, code });
    const frame: ErrorFrame = { type: 'error', code, message };
    ws.send(JSON.stringify(frame));
    closeHandshake(ws, closeCode, code);
  };

  const onHello = (text: string | null): void => {
    const hello = checkHello(text, { accessKey: config.accessKey, urlDeviceId });
    if (!hello.ok) {
      if (hello.authFailure) limiter.recordAuthFailure(rateLimitKey(ip));
      reject(hello.code, hello.closeCode, hello.message);
      return;
    }
    // A phone that is connected already may always reconnect: its new connection replaces the old one.
    if (registry.get(hello.deviceId) === undefined && registry.size >= config.maxDevices) {
      reject(
        'relay_full',
        CloseCode.RELAY_FULL,
        'This relay is full right now. DroidHands keeps retrying; to connect now, use another relay or run your own.',
      );
      logger.warn('relay full: refused a new device', { devices: registry.size, maxDevices: config.maxDevices });
      return;
    }
    if (ws.readyState !== WebSocket.OPEN) {
      state = 'closed';
      return;
    }

    state = 'open';
    clearTimeout(helloTimer);
    onAuthenticated();
    const { deviceId, info } = hello;
    const { platform, appVersion } = info;
    connection = new DeviceConnection(deviceId, ws, config.requestTimeoutMs, info);
    const replaced = registry.register(connection);
    if (replaced !== undefined) {
      replaced.close(CloseCode.REPLACED, 'replaced by a newer connection', 'replaced');
      logger.info('device connection replaced', { device: deviceTag(deviceId) });
    }
    ws.send(JSON.stringify(welcomeFrame(base, deviceId, Date.now())));
    logger.info('device connected', { device: deviceTag(deviceId), ip, platform, appVersion, devices: registry.size });
    pairings.deviceConnected(deviceId, connection);
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
      case 'pair_response':
        pairings.handleResponse(conn.deviceId, frame);
        return;
      case 'ping':
        ws.send(JSON.stringify({ type: 'pong', t: frame.t }));
        return;
      case 'hb':
        ws.send(HEARTBEAT_ACK_FRAME);
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
    pairings.connectionClosed(connection);
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

/**
 * Closes a socket that failed the handshake with [code] (so the device sees why), then drops it if
 * the close handshake has not finished within [HANDSHAKE_CLOSE_GRACE_MS].
 */
function closeHandshake(ws: WebSocket, code: number, reason: string): void {
  ws.close(code, reason);
  setTimeout(() => ws.terminate(), HANDSHAKE_CLOSE_GRACE_MS).unref();
}

function rawToString(data: WebSocket.RawData): string {
  if (Buffer.isBuffer(data)) return data.toString('utf8');
  if (Array.isArray(data)) return Buffer.concat(data).toString('utf8');
  return Buffer.from(data).toString('utf8');
}
