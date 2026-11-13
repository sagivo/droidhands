import { STATUS_CODES, createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import type { Duplex } from 'node:stream';
import { WebSocketServer, type WebSocket } from 'ws';
import { resolveConfig, type RelayConfig } from './config.js';
import { DeviceRegistry } from './deviceRegistry.js';
import { acceptDeviceSocket } from './deviceSocket.js';
import { createHttpHandler, requestPath } from './httpHandler.js';
import { createLogger, type Logger } from './logger.js';
import { clientAddress, rateLimitKey } from './net.js';
import { CloseCode, DEVICE_WS_PATH, MAX_FRAME_BYTES } from './protocol.js';
import { RateLimiter } from './rateLimit.js';

export { ConfigError, DEFAULT_CONFIG, loadConfigFromEnv, resolveConfig, type RelayConfig } from './config.js';
export { createLogger, type Logger, type LogLevel } from './logger.js';
export { deriveDeviceId } from './encoding.js';

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
  /**
   * Graceful shutdown: stops accepting connections, fails pending tunneled requests with 503,
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

  const server = createServer(
    createHttpHandler({ config: cfg, logger, registry, limiter, isShuttingDown: () => shuttingDown }),
  );
  // Behind a reverse proxy, idle upstream connections must outlive the proxy's own keep-alive.
  server.keepAliveTimeout = 65_000;
  server.headersTimeout = 66_000;
  // Bounds slow uploads; must not cut off agents waiting for a device (the response phase).
  server.requestTimeout = Math.max(300_000, cfg.requestTimeoutMs + 30_000);

  const wss = new WebSocketServer({
    noServer: true,
    maxPayload: MAX_FRAME_BYTES,
    clientTracking: false,
    perMessageDeflate: false,
  });
  const socketDeps = { config: cfg, logger, registry, limiter, sockets };

  server.on('upgrade', (req, socket: Duplex, head: Buffer) => {
    socket.on('error', () => socket.destroy());
    if (requestPath(req.url) !== DEVICE_WS_PATH) return abortUpgrade(socket, 404);
    if (shuttingDown) return abortUpgrade(socket, 503);
    // Upgrades share the per-IP request budget, which also bounds handshake spam.
    if (!limiter.hit(rateLimitKey(clientAddress(req, cfg.trustProxy))).allowed) return abortUpgrade(socket, 429);
    wss.handleUpgrade(req, socket, head, (ws) => acceptDeviceSocket(ws, req, socketDeps));
  });

  const heartbeat = setInterval(() => {
    for (const connection of registry.values()) connection.heartbeat();
  }, cfg.heartbeatIntervalMs);
  heartbeat.unref();
  const sweeper = setInterval(() => limiter.sweep(), SWEEP_INTERVAL_MS);
  sweeper.unref();

  try {
    await listen(server, cfg.port, cfg.host);
  } catch (err) {
    clearInterval(heartbeat);
    clearInterval(sweeper);
    throw err;
  }
  const address = server.address() as AddressInfo;

  let closing: Promise<void> | null = null;
  const close = (): Promise<void> => {
    closing ??= (async () => {
      shuttingDown = true;
      clearInterval(heartbeat);
      clearInterval(sweeper);
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
