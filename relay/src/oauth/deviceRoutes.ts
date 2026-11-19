import type { IncomingMessage, ServerResponse } from 'node:http';
import { agentGuide, agentGuideContentType, llmsTxt } from '../agentGuide.js';
import { readBody } from '../body.js';
import { admit, clientInfo, relayBase, type HttpDeps } from '../httpDeps.js';
import { allowMethods, applyOpenCors, sendBody, sendJson } from '../httpUtil.js';
import { headerValue } from '../net.js';
import { approveCode, approvePage } from '../pages/approvePage.js';
import { connectorSetupPage, wantsSetupPage } from '../pages/connectorPage.js';
import { sendPage } from '../pages/html.js';
import { cleanUserAgent } from './authorizeRoutes.js';
import { connectorUrl } from './metadata.js';
import {
  BODY_ERROR,
  DEVICE_OFFLINE_ERROR,
  MAX_DEVICE_AUTHORIZATION_BODY_BYTES,
  bodyParams,
  checkDeviceAuthorization,
  deviceAuthorizationRequest,
  deviceAuthorizationResponse,
  deviceCreateFailure,
  type DeviceFlowError,
} from './deviceFlow.js';

/**
 * Agent sign-in and the agent guide (docs/PROTOCOL.md §5.6-5.7): `POST /oauth/device_authorization`,
 * `GET` on a connection URL, and `GET /llms.txt`.
 */

/** `POST /oauth/device_authorization` (RFC 8628 §3.1): prompts the phone and returns the device code. */
export async function handleDeviceAuthorization(deps: HttpDeps, req: IncomingMessage, res: ServerResponse): Promise<void> {
  if (applyOpenCors(req, res, 'POST')) return;
  if (req.method !== 'POST') {
    res.setHeader('Allow', 'POST, OPTIONS');
    return sendJson(res, 405, { error: 'invalid_request', error_description: 'use POST' });
  }
  const client = clientInfo(deps, req);
  const decision = admit(deps, client);
  if (!decision.allowed) {
    res.setHeader('Retry-After', String(decision.retryAfterSec));
    return sendJson(res, 429, { error: 'slow_down', error_description: 'too many requests; retry later' });
  }
  const body = await readBody(req, MAX_DEVICE_AUTHORIZATION_BODY_BYTES);
  if (body.kind === 'aborted') return;
  if (body.kind === 'too_large') {
    res.setHeader('Connection', 'close');
    return sendJson(res, 413, { error: 'invalid_request', error_description: 'request too large' });
  }
  const params = bodyParams(headerValue(req, 'content-type'), body.body);
  if (params === null) return sendError(res, BODY_ERROR);

  const base = relayBase(deps, req);
  const checked = checkDeviceAuthorization(params, base, deps.relaySecret);
  if (!checked.ok) return sendError(res, checked);
  const connection = deps.registry.get(checked.deviceId);
  if (connection === undefined || !connection.isOpen) return sendError(res, DEVICE_OFFLINE_ERROR, { 'Retry-After': '30' });

  const result = deps.pairings.create(deviceAuthorizationRequest(checked.deviceId, checked.clientName, base), {
    ipKey: client.ipKey,
    remoteAddress: client.ip,
    userAgent: cleanUserAgent(headerValue(req, 'user-agent')),
  });
  if (!result.ok) return sendError(res, deviceCreateFailure(result.reason));
  sendJson(res, 200, deviceAuthorizationResponse(result.pairing, base, Date.now()));
}

/** `GET /d/<deviceId>/mcp` without `Accept: text/event-stream`: the guide for that phone. */
export function handleAgentGuide(deps: HttpDeps, req: IncomingMessage, res: ServerResponse, deviceId: string): void {
  if (!admit(deps, clientInfo(deps, req)).allowed) return sendJson(res, 429, { error: 'rate_limited' });
  const accept = headerValue(req, 'accept');
  const base = relayBase(deps, req);
  // A person in a browser gets the setup page (with the guide inside); agents get the guide as text.
  if (wantsSetupPage(req.method, accept, headerValue(req, 'authorization'))) {
    return sendPage(req, res, 200, connectorSetupPage(connectorUrl(base, deviceId), agentGuide(base, deviceId)));
  }
  sendBody(req, res, 200, agentGuideContentType(accept), agentGuide(base, deviceId));
}

/** `GET /approve?code=…`: the page an agent sends the person (§5.6). */
export function handleApprovePage(deps: HttpDeps, req: IncomingMessage, res: ServerResponse, url: URL): void {
  if (!allowMethods(req, res, 'GET, HEAD')) return;
  if (!admit(deps, clientInfo(deps, req)).allowed) return sendJson(res, 429, { error: 'rate_limited' });
  sendPage(req, res, 200, approvePage(approveCode(url.searchParams.get('code'))));
}

/** `GET /llms.txt`. */
export function handleLlmsTxt(deps: HttpDeps, req: IncomingMessage, res: ServerResponse): void {
  if (!allowMethods(req, res, 'GET, HEAD')) return;
  if (!admit(deps, clientInfo(deps, req)).allowed) return sendJson(res, 429, { error: 'rate_limited' });
  sendBody(req, res, 200, agentGuideContentType(headerValue(req, 'accept')), llmsTxt(relayBase(deps, req)));
}

function sendError(res: ServerResponse, error: DeviceFlowError, headers: Record<string, string> = {}): void {
  for (const [name, value] of Object.entries(headers)) res.setHeader(name, value);
  sendJson(res, error.status, { error: error.error, error_description: error.description });
}
