import type { IncomingMessage, ServerResponse } from 'node:http';
import { declaredLength } from './body.js';

/**
 * Small response helpers shared by the relay's HTTP routes.
 */

/**
 * Ends the response. If the request body has not been consumed and is large or of unknown size,
 * the connection is closed afterwards instead of draining the rest of the upload.
 */
export function sendBody(req: IncomingMessage, res: ServerResponse, status: number, contentType: string, body: string): void {
  if (!req.complete) {
    const declared = declaredLength(req);
    const chunked = req.headers['transfer-encoding'] !== undefined;
    if (chunked || (declared !== null && declared > 64 * 1024)) res.setHeader('Connection', 'close');
  }
  res.statusCode = status;
  res.setHeader('Content-Type', contentType);
  res.setHeader('Content-Length', String(Buffer.byteLength(body)));
  res.end(req.method === 'HEAD' ? undefined : body);
}

export function sendText(res: ServerResponse, status: number, text: string): void {
  sendBody(res.req, res, status, 'text/plain; charset=utf-8', text);
}

export function sendJson(res: ServerResponse, status: number, value: unknown): void {
  sendBody(res.req, res, status, 'application/json', JSON.stringify(value));
}

/** Answers 405 with `Allow` unless the request method is in [allow] (`"GET, HEAD"` style). */
export function allowMethods(req: IncomingMessage, res: ServerResponse, allow: string): boolean {
  if (allow.split(', ').includes(req.method ?? '')) return true;
  res.setHeader('Allow', allow);
  sendText(res, 405, 'Method Not Allowed\n');
  return false;
}

/**
 * Open CORS for endpoints browser-based MCP clients call directly (OAuth discovery, registration
 * and token): any origin, no credentials. Answers an `OPTIONS` preflight itself and returns true
 * when it did.
 */
export function applyOpenCors(req: IncomingMessage, res: ServerResponse, methods: string): boolean {
  res.setHeader('Access-Control-Allow-Origin', '*');
  if (req.method !== 'OPTIONS') return false;
  res.setHeader('Access-Control-Allow-Methods', `${methods}, OPTIONS`);
  res.setHeader('Access-Control-Allow-Headers', 'Authorization, Content-Type, MCP-Protocol-Version');
  res.setHeader('Access-Control-Max-Age', '86400');
  res.statusCode = 204;
  res.end();
  return true;
}

/** CORS settings for [applyOriginCors]. */
export interface OriginCors {
  /** Methods allowed in preflights (`OPTIONS` is added). */
  readonly methods: string;
  /** Request headers allowed in preflights. */
  readonly allowHeaders: string;
  /** Response headers scripts may read besides the CORS-safelisted ones. */
  readonly exposeHeaders: string;
}

/**
 * CORS for an `Origin` the operator allowlisted (ALLOWED_ORIGINS), without credentials: echoes
 * [origin] on the response. Answers an `OPTIONS` preflight itself and returns true when it did.
 * The caller must have checked [origin] against the allowlist.
 */
export function applyOriginCors(req: IncomingMessage, res: ServerResponse, origin: string, cors: OriginCors): boolean {
  res.setHeader('Access-Control-Allow-Origin', origin);
  res.setHeader('Vary', 'Origin');
  if (req.method !== 'OPTIONS') {
    res.setHeader('Access-Control-Expose-Headers', cors.exposeHeaders);
    return false;
  }
  res.setHeader('Access-Control-Allow-Methods', `${cors.methods}, OPTIONS`);
  res.setHeader('Access-Control-Allow-Headers', cors.allowHeaders);
  res.setHeader('Access-Control-Max-Age', '86400');
  res.statusCode = 204;
  res.end();
  return true;
}

/** The request's path, or null when the target cannot be parsed. Query strings are ignored. */
export function requestPath(url: string | undefined): string | null {
  return requestUrl(url)?.pathname ?? null;
}

/** The parsed request target (against a dummy origin), or null when it cannot be parsed. */
export function requestUrl(url: string | undefined): URL | null {
  if (url === undefined || url === '') return null;
  try {
    return new URL(url, 'http://relay.invalid');
  } catch {
    return null;
  }
}
