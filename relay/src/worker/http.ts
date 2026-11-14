/**
 * Response helpers of the Worker relay, mirroring relay/src/httpUtil.ts for the fetch API.
 */
import { renderPage, type PageOptions } from '../pages/html.js';
import { jsonRpcError, RpcCode } from '../protocol.js';

/** Headers on every response (the Node relay sets them on every request). */
export const COMMON_HEADERS: Readonly<Record<string, string>> = {
  'Cache-Control': 'no-store',
  'X-Content-Type-Options': 'nosniff',
};

const encoder = new TextEncoder();

/**
 * A response with [COMMON_HEADERS]. String bodies are sent as bytes so that the runtime does not add
 * a `text/plain` content type the Node relay would not send.
 */
export function respond(status: number, body: string | null, headers: Record<string, string> = {}): Response {
  const bodyless = status === 101 || status === 204 || status === 205 || status === 304;
  return new Response(bodyless || body === null ? null : encoder.encode(body), {
    status,
    headers: { ...COMMON_HEADERS, ...headers },
  });
}

export function text(status: number, body: string, headers: Record<string, string> = {}): Response {
  return respond(status, body, { 'Content-Type': 'text/plain; charset=utf-8', ...headers });
}

export function json(status: number, value: unknown, headers: Record<string, string> = {}): Response {
  return respond(status, JSON.stringify(value), { 'Content-Type': 'application/json', ...headers });
}

/** A relay-generated JSON-RPC error (`id: null`), as on the Node relay's MCP endpoints. */
export function rpcError(status: number, message: string, headers: Record<string, string> = {}): Response {
  return respond(status, jsonRpcError(RpcCode.SERVER_ERROR, message), { 'Content-Type': 'application/json', ...headers });
}

export function page(status: number, options: PageOptions, headers: Record<string, string> = {}): Response {
  const rendered = renderPage(options);
  return respond(status, rendered.html, { ...rendered.headers, 'Content-Type': 'text/html; charset=utf-8', ...headers });
}

/** 405 with `Allow` unless [method] is in [allow] (`"GET, HEAD"` style). */
export function methodNotAllowed(method: string, allow: string): Response | null {
  if (allow.split(', ').includes(method)) return null;
  return text(405, 'Method Not Allowed\n', { Allow: allow });
}

export type BodyResult = { readonly kind: 'ok'; readonly body: string } | { readonly kind: 'too_large' };

/** The declared Content-Length, or null when absent or malformed. */
export function declaredLength(request: Request): number | null {
  const header = request.headers.get('content-length');
  return header === null || !/^\d+$/.test(header) ? null : Number(header);
}

/** Reads the body as UTF-8, buffering at most [limit] bytes (the rest is not read). */
export async function readBody(request: Request, limit: number): Promise<BodyResult> {
  const declared = declaredLength(request);
  if (declared !== null && declared > limit) return { kind: 'too_large' };
  if (request.body === null) return { kind: 'ok', body: '' };
  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    size += value.byteLength;
    if (size > limit) {
      await reader.cancel().catch(() => {});
      return { kind: 'too_large' };
    }
    chunks.push(value);
  }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return { kind: 'ok', body: new TextDecoder().decode(bytes) };
}
