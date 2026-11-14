import { STATUS_CODES, createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import type { Duplex } from 'node:stream';
import { WebSocketServer, type WebSocket } from 'ws';
import { resolveConfig, type RelayConfig } from './config.js';
import { DeviceRegistry } from './deviceRegistry.js';
import { acceptDeviceSocket } from './deviceSocket.js';
import { HandshakeGuard } from './handshakeGuard.js';
import { createHttpHandler, requestPath } from './httpHandler.js';
import { createLogger, type Logger } from './logger.js';
import { clientAddress, rateLimitKey } from './net.js';
import { PairingStore, type Channel } from './oauth/pairingStore.js';
import { CloseCode, DEVICE_WS_PATH, MAX_FRAME_BYTES, MAX_PRE_HELLO_BYTES } from './protocol.js';
import { RateLimiter } from './rateLimit.js';
import { loadRelaySecret, type RelaySecretSource } from './relaySecret.js';

export { ConfigError, DEFAULT_CONFIG, loadConfigFromEnv, resolveConfig, type RelayConfig } from './config.js';
export { createLogger, type Logger, type LogLevel } from './logger.js';
export { deriveDeviceId } from './encoding.js';
export { guessBaseUrl, setupUrl } from './setup/setupUrl.js';
export { qrTerminal } from './setup/qr.js';

/** A running relay. */
export interface Relay {
  readonly server: Server;
  /** The bound address (useful with port 0). */
  readonly address: AddressInfo;
  /** `http://host:port` of the bound address, with wildcard hosts mapped to loopback. */
  readonly url: string;
  readonly config: RelayConfig;
  /** Number of currently connected (authenticated) devices. */
  deviceCount(): number;
  /** Where the OAuth client-id secret came from (`env`, `file`, `generated` or `temporary`). */
  readonly relaySecretSource: RelaySecretSource;
  /**
   * Graceful shutdown: stops accepting connections, cancels pending pairings (`pair_cancel`, and
   * `pair_abandoned` for tokens no agent collected), fails pending tunneled requests with 503,
   * closes device sockets with 1001 and resolves once the server is closed. Idempotent.
   */
  close(): Promise<void>;
}

export interface RelayOptions {
  /** Defaults to a JSON-lines logger on stdout at `config.logLevel`. */
  readonly logger?: Logger;
}

const RATE_WINDOW_MS = 60_000;
const SWEEP_INTERVAL_MS = 60_000;
/** Pairing expirations (pair_cancel, pair_abandoned) are applied at least this often. */
const PAIRING_SWEEP_MAX_MS = 1_000;
/** After this long, shutdown stops waiting for sockets to close politely. */
const SHUTDOWN_GRACE_MS = 3_000;

/**
 * Creates the relay and starts listening on `config.port`/`config.host` (port 0 = ephemeral).
 * Unspecified config fields take their defaults ([DEFAULT_CONFIG]).
 */
export async function createRelay(config: Partial<RelayConfig> = {}, options: RelayOptions = {}): Promise<Relay> {
  const cfg = resolveConfig(config);
  const logger = options.logger ?? createLogger(cfg.logLevel);
  const registry = new DeviceRegistry();
  const limiter = new RateLimiter({
    windowMs: RATE_WINDOW_MS,
    maxRequests: cfg.rateLimitRequestsPerMin,
    maxAuthFailures: cfg.rateLimitAuthFailuresPerMin,
    blockMs: RATE_WINDOW_MS,
  });
  const sockets = new Set<WebSocket>();
  let shuttingDown = false;
  const secret = await loadRelaySecret(cfg, logger);
  const pairings = new PairingStore({
    ttlMs: cfg.pairingTtlMs,
    codeTtlMs: cfg.authCodeTtlMs,
    logger,
    deliver: (deviceId, frame): Channel | null => {
      const connection = registry.get(deviceId);
      return connection !== undefined && connection.sendFrame(frame) ? connection : null;
    },
  });

  const server = createServer(
    createHttpHandler({
      config: cfg,
      logger,
      registry,
      limiter,
      pairings,
      relaySecret: secret.key,
      isShuttingDown: () => shuttingDown,
    }),
  );
  // Behind a reverse proxy, idle upstream connections must outlive the proxy's own keep-alive.
  server.keepAliveTimeout = 65_000;
  server.headersTimeout = 66_000;
  // Bounds slow uploads; must not cut off agents waiting for a device (the response phase).
  server.requestTimeout = Math.max(300_000, cfg.requestTimeoutMs + 30_000);

  // MAX_FRAME_BYTES applies once a socket is authenticated; before that, `handshakes` caps it far lower.
  const wss = new WebSocketServer({
    noServer: true,
    maxPayload: MAX_FRAME_BYTES,
    clientTracking: false,
    perMessageDeflate: false,
  });
  const handshakes = new HandshakeGuard({
    maxPending: cfg.maxPendingHandshakes,
    maxPendingPerKey: cfg.maxPendingHandshakesPerIp,
    maxBytes: MAX_PRE_HELLO_BYTES,
  });
  const socketDeps = { config: cfg, logger, registry, limiter, sockets, pairings };

  server.on('upgrade', (req, socket: Duplex, head: Buffer) => {
    socket.on('error', () => socket.destroy());
    if (requestPath(req.url) !== DEVICE_WS_PATH) return abortUpgrade(socket, 404);
    if (shuttingDown) return abortUpgrade(socket, 503);
    const ip = clientAddress(req, cfg.trustProxy);
    const ipKey = rateLimitKey(ip);
    // Upgrades share the per-IP request budget, which also bounds handshake spam.
    if (!limiter.hit(ipKey).allowed) return abortUpgrade(socket, 429);
    const slot = handshakes.admit(ipKey, socket);
    if (slot === 'global_limit' || slot === 'key_limit') {
      logger.warn('device handshake refused', { ip, reason: slot, pending: handshakes.pending });
      return abortUpgrade(socket, slot === 'key_limit' ? 429 : 503);
    }
    wss.handleUpgrade(req, socket, head, (ws) => {
      slot.limitBytes(() => logger.info('device sent too much before its hello', { ip }));
      acceptDeviceSocket(ws, req, socketDeps, slot.release);
    });
  });

  const heartbeat = setInterval(() => {
    for (const connection of registry.values()) connection.heartbeat();
  }, cfg.heartbeatIntervalMs);
  heartbeat.unref();
  const sweeper = setInterval(() => limiter.sweep(), SWEEP_INTERVAL_MS);
  sweeper.unref();
  const pairingSweeper = setInterval(
    () => pairings.sweep(),
    Math.max(10, Math.min(PAIRING_SWEEP_MAX_MS, Math.floor(Math.min(cfg.pairingTtlMs, cfg.authCodeTtlMs) / 4))),
  );
  pairingSweeper.unref();
  const stopTimers = (): void => {
    clearInterval(heartbeat);
    clearInterval(sweeper);
    clearInterval(pairingSweeper);
  };

  try {
    await listen(server, cfg.port, cfg.host);
  } catch (err) {
    stopTimers();
    throw err;
  }
  const address = server.address() as AddressInfo;

  let closing: Promise<void> | null = null;
  const close = (): Promise<void> => {
    closing ??= (async () => {
      shuttingDown = true;
      stopTimers();
      // Before the sockets close: phones drop prompts and delete tokens no agent received.
      pairings.shutdown();
      const serverClosed = new Promise<void>((resolve) => server.close(() => resolve()));
      for (const connection of [...registry.values()]) {
        connection.close(CloseCode.GOING_AWAY, 'relay shutting down', 'shutdown');
      }
      for (const ws of sockets) ws.close(CloseCode.GOING_AWAY, 'relay shutting down');
      server.closeIdleConnections();
      wss.close();
      const force = setTimeout(() => {
        for (const ws of sockets) ws.terminate();
        server.closeAllConnections();
      }, SHUTDOWN_GRACE_MS);
      await serverClosed;
      clearTimeout(force);
      logger.info('relay stopped');
    })();
    return closing;
  };

  return {
    server,
    address,
    url: `http://${formatHost(address)}:${address.port}`,
    config: cfg,
    deviceCount: () => registry.size,
    relaySecretSource: secret.source,
    close,
  };
}

function listen(server: Server, port: number, host: string): Promise<void> {
  return new Promise((resolve, reject) => {
    const onError = (err: Error): void => reject(err);
    server.once('error', onError);
    server.listen(port, host, () => {
      server.off('error', onError);
      resolve();
    });
  });
}

/** Answers a rejected upgrade with a bare HTTP status and closes the socket. */
function abortUpgrade(socket: Duplex, status: number): void {
  if (!socket.writable) {
    socket.destroy();
    return;
  }
  socket.once('finish', () => socket.destroy());
  socket.end(`HTTP/1.1 ${status} ${STATUS_CODES[status] ?? ''}\r\nConnection: close\r\nContent-Length: 0\r\n\r\n`);
}

function formatHost(address: AddressInfo): string {
  if (address.address === '0.0.0.0') return '127.0.0.1';
  if (address.address === '::') return '[::1]';
  return address.family === 'IPv6' ? `[${address.address}]` : address.address;
}
