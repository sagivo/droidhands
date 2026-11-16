import type { IncomingMessage, ServerResponse } from 'node:http';
import { isOriginAllowed } from './config.js';
import { clientInfo, publicPathPrefix, relayBase, type HttpDeps } from './httpDeps.js';
import { allowMethods, requestUrl, sendBody, sendText } from './httpUtil.js';
import { errorMessage } from './logger.js';
import { handleMcp, sendRpcError, type McpRoute } from './mcpHandler.js';
import { headerValue } from './net.js';
import { PAIR_STATUS_PREFIX, handleAuthorize, handlePairStatus } from './oauth/authorizeRoutes.js';
import { handleRegister, handleResourceMetadata, handleServerMetadata } from './oauth/discoveryRoutes.js';
import { CONNECTOR_PATH_REGEX, deviceIdFromMetadataPath, isAuthorizationServerMetadataPath } from './oauth/metadata.js';
import { handleToken } from './oauth/tokenRoute.js';
import { DEVICE_WS_PATH } from './protocol.js';
import { APK_PATH, SETUP_QR_PATH, handleApkDownload, handleSetupPage, handleSetupQr } from './setup/setupRoutes.js';
import { SETUP_PATH } from './setup/setupUrl.js';

export type { HttpDeps } from './httpDeps.js';
export { requestPath } from './httpUtil.js';

/**
 * How a route treats the `Origin` allowlist (ALLOWED_ORIGINS):
 * * `checked`: requests with a non-allowlisted Origin get 403 (MCP endpoints, misc).
 * * `open`: called cross-origin by browser-based MCP clients; open CORS, no Origin check (OAuth
 *   discovery, registration, token).
 * * `page`: the relay's own browser pages and their same-origin calls (authorize, pairing status,
 *   setup); no Origin check.
 */
type OriginPolicy = 'checked' | 'open' | 'page';

interface Route {
  readonly origin: OriginPolicy;
  /** Errors are JSON-RPC shaped (MCP endpoints) instead of plain text. */
  readonly rpc?: boolean;
  readonly handle: (deps: HttpDeps, req: IncomingMessage, res: ServerResponse, url: URL) => void | Promise<void>;
}

/**
 * The relay's HTTP surface: the MCP endpoints tunneled to devices (§2-4, §5.1), OAuth discovery,
 * registration, authorization and token endpoints (§5.2-5.4), the phone setup page (§5.5), plus
 * `/healthz` and `/`.
 */
export function createHttpHandler(deps: HttpDeps): (req: IncomingMessage, res: ServerResponse) => void {
  const prefix = publicPathPrefix(deps.config);
  return (req, res) => {
    handle(deps, prefix, req, res).catch((err: unknown) => {
      deps.logger.error('request handler failed', { error: errorMessage(err) });
      if (!res.headersSent) sendText(res, 500, 'Internal Server Error\n');
      else res.destroy();
    });
  };
}

async function handle(deps: HttpDeps, prefix: string, req: IncomingMessage, res: ServerResponse): Promise<void> {
  res.setHeader('Cache-Control', 'no-store');
  res.setHeader('X-Content-Type-Options', 'nosniff');

  const url = requestUrl(req.url);
  if (url === null) return sendText(res, 400, 'Bad Request\n');
  const route = matchRoute(deps, url.pathname, prefix);

  const origin = headerValue(req, 'origin');
  if (route.origin === 'checked' && origin !== undefined && !isOriginAllowed(origin, deps.config.allowedOrigins)) {
    deps.logger.info('origin rejected', { ip: clientInfo(deps, req).ip });
    return route.rpc === true ? sendRpcError(req, res, 403, 'Origin not allowed') : sendText(res, 403, 'Forbidden\n');
  }
  return route.handle(deps, req, res, url);
}

function matchRoute(deps: HttpDeps, pathname: string, prefix: string): Route {
  const mcp = (route: McpRoute): Route => ({ origin: 'checked', rpc: true, handle: (d, q, r) => handleMcp(d, q, r, route) });

  if (pathname === '/mcp' || pathname === '/mcp/') return mcp({ kind: 'token', pathToken: null });
  if (pathname.startsWith('/mcp/')) {
    const segment = pathname.slice('/mcp/'.length);
    if (!deps.config.allowTokenInUrl || segment.includes('/')) return { origin: 'checked', rpc: true, handle: notFound };
    return mcp({ kind: 'token', pathToken: segment });
  }
  const connector = CONNECTOR_PATH_REGEX.exec(pathname)?.[1];
  if (connector !== undefined) return mcp({ kind: 'connector', deviceId: connector });

  if (pathname.startsWith('/.well-known/')) {
    const deviceId = deviceIdFromMetadataPath(pathname, prefix);
    if (deviceId !== null) return { origin: 'open', handle: (d, q, r) => handleResourceMetadata(d, q, r, deviceId) };
    if (isAuthorizationServerMetadataPath(pathname, prefix)) return { origin: 'open', handle: handleServerMetadata };
    return { origin: 'open', handle: notFound };
  }
  if (pathname.startsWith(PAIR_STATUS_PREFIX)) {
    const id = pathname.slice(PAIR_STATUS_PREFIX.length);
    return { origin: 'page', handle: (d, q, r, u) => handlePairStatus(d, q, r, id, u) };
  }

  switch (pathname) {
    case '/oauth/register':
      return { origin: 'open', handle: handleRegister };
    case '/oauth/token':
      return { origin: 'open', handle: handleToken };
    case '/oauth/authorize':
      return { origin: 'page', handle: handleAuthorize };
    case SETUP_PATH:
      return { origin: 'page', handle: handleSetupPage };
    case SETUP_QR_PATH:
      return { origin: 'page', handle: handleSetupQr };
    case APK_PATH:
      return { origin: 'page', handle: handleApkDownload };
    case '/healthz':
      return { origin: 'checked', handle: healthz };
    case '/':
      return { origin: 'checked', handle: banner };
    case DEVICE_WS_PATH:
      return { origin: 'checked', handle: upgradeRequired };
    default:
      return { origin: 'checked', handle: notFound };
  }
}

function healthz(_deps: HttpDeps, req: IncomingMessage, res: ServerResponse): void {
  if (!allowMethods(req, res, 'GET, HEAD')) return;
  sendBody(req, res, 200, 'application/json', '{"ok":true}');
}

function banner(deps: HttpDeps, req: IncomingMessage, res: ServerResponse): void {
  if (!allowMethods(req, res, 'GET, HEAD')) return;
  const base = relayBase(deps, req);
  sendBody(
    req,
    res,
    200,
    'text/plain; charset=utf-8',
    `DroidHands relay.\nSet up a phone: ${base}${SETUP_PATH}\nAI apps connect to the connection URL shown in the DroidHands app (${base}/d/<deviceId>/mcp).\n`,
  );
}

function upgradeRequired(_deps: HttpDeps, _req: IncomingMessage, res: ServerResponse): void {
  res.setHeader('Upgrade', 'websocket');
  sendText(res, 426, 'Upgrade Required\n');
}

function notFound(_deps: HttpDeps, _req: IncomingMessage, res: ServerResponse): void {
  sendText(res, 404, 'Not Found\n');
}
