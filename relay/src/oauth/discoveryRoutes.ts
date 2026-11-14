import type { IncomingMessage, ServerResponse } from 'node:http';
import { readBody } from '../body.js';
import { admit, clientInfo, relayBase, type HttpDeps } from '../httpDeps.js';
import { applyOpenCors, sendJson } from '../httpUtil.js';
import { registerClient } from './clientRegistration.js';
import { authorizationServerMetadata, protectedResourceMetadata } from './metadata.js';

/**
 * OAuth discovery and dynamic client registration (docs/PROTOCOL.md §5.2). All of these answer
 * with open CORS (no credentials) so browser-based MCP clients can use them.
 */

/** Registration metadata is small; this bounds memory per request. */
const MAX_REGISTRATION_BODY_BYTES = 16 * 1024;

/** `GET /.well-known/oauth-protected-resource/d/<deviceId>/mcp` (RFC 9728). */
export function handleResourceMetadata(deps: HttpDeps, req: IncomingMessage, res: ServerResponse, deviceId: string): void {
  if (!discoveryPreamble(deps, req, res)) return;
  sendJson(res, 200, protectedResourceMetadata(relayBase(deps, req), deviceId));
}

/** `GET /.well-known/oauth-authorization-server` and `/.well-known/openid-configuration` (RFC 8414). */
export function handleServerMetadata(deps: HttpDeps, req: IncomingMessage, res: ServerResponse): void {
  if (!discoveryPreamble(deps, req, res)) return;
  sendJson(res, 200, authorizationServerMetadata(relayBase(deps, req)));
}

/** `POST /oauth/register` (RFC 7591), stateless: see [registerClient]. */
export async function handleRegister(deps: HttpDeps, req: IncomingMessage, res: ServerResponse): Promise<void> {
  if (applyOpenCors(req, res, 'POST')) return;
  if (req.method !== 'POST') {
    res.setHeader('Allow', 'POST, OPTIONS');
    return sendJson(res, 405, { error: 'invalid_request', error_description: 'use POST' });
  }
  const client = clientInfo(deps, req);
  const decision = admit(deps, client);
  if (!decision.allowed) {
    res.setHeader('Retry-After', String(decision.retryAfterSec));
    return sendJson(res, 429, { error: 'invalid_request', error_description: 'too many requests; retry later' });
  }
  const body = await readBody(req, MAX_REGISTRATION_BODY_BYTES);
  if (body.kind === 'aborted') return;
  if (body.kind === 'too_large') {
    res.setHeader('Connection', 'close');
    return sendJson(res, 413, { error: 'invalid_client_metadata', error_description: 'registration request too large' });
  }
  let metadata: unknown;
  try {
    metadata = JSON.parse(body.body);
  } catch {
    return sendJson(res, 400, { error: 'invalid_client_metadata', error_description: 'the request body must be JSON' });
  }
  const result = registerClient(metadata, deps.relaySecret, Date.now());
  if (!result.ok) {
    deps.logger.info('client registration rejected', { ip: client.ip, error: result.error });
    return sendJson(res, 400, { error: result.error, error_description: result.description });
  }
  deps.logger.info('client registered', { ip: client.ip, name: result.response.client_name });
  sendJson(res, 201, result.response);
}

/** CORS, preflight, method and rate checks shared by the metadata endpoints. */
function discoveryPreamble(deps: HttpDeps, req: IncomingMessage, res: ServerResponse): boolean {
  if (applyOpenCors(req, res, 'GET, HEAD')) return false;
  if (req.method !== 'GET' && req.method !== 'HEAD') {
    res.setHeader('Allow', 'GET, HEAD, OPTIONS');
    sendJson(res, 405, { error: 'invalid_request', error_description: 'use GET' });
    return false;
  }
  const decision = admit(deps, clientInfo(deps, req));
  if (!decision.allowed) {
    res.setHeader('Retry-After', String(decision.retryAfterSec));
    sendJson(res, 429, { error: 'invalid_request', error_description: 'too many requests; retry later' });
    return false;
  }
  return true;
}
