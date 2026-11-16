#!/usr/bin/env node
/**
 * End-to-end smoke test against a live MCP endpoint (relay or local mode):
 * initialize → notifications/initialized → tools/list, then prints the tool names.
 *
 *   npm run smoke -- <mcpUrl> <token>
 *   DROIDHANDS_TOKEN=pb1.… npm run smoke -- https://relay.example.com/mcp
 *
 * Exits 0 on success, 1 on any failure, 2 on bad usage. The token is never printed.
 */

const PROTOCOL_VERSION = '2025-06-18';
const TIMEOUT_MS = Number(process.env.SMOKE_TIMEOUT_MS ?? 30_000);

const [mcpUrl, tokenArg] = process.argv.slice(2);
const token = tokenArg ?? process.env.DROIDHANDS_TOKEN;

if (!mcpUrl || !token) {
  console.error('usage: npm run smoke -- <mcpUrl> <token>   (or set DROIDHANDS_TOKEN)');
  process.exit(2);
}
try {
  new URL(mcpUrl);
} catch {
  console.error(`smoke: not a valid URL: ${mcpUrl}`);
  process.exit(2);
}

class SmokeError extends Error {}

let nextId = 1;
let negotiatedVersion = null;

/** POSTs one JSON-RPC message; returns the parsed response message, or null for 202. */
async function post(message) {
  const headers = {
    authorization: `Bearer ${token}`,
    'content-type': 'application/json',
    accept: 'application/json, text/event-stream',
    'user-agent': 'droidhands-smoke/0.1',
  };
  if (negotiatedVersion) headers['mcp-protocol-version'] = negotiatedVersion;

  let res;
  try {
    res = await fetch(mcpUrl, {
      method: 'POST',
      headers,
      body: JSON.stringify(message),
      signal: AbortSignal.timeout(TIMEOUT_MS),
    });
  } catch (err) {
    throw new SmokeError(`${message.method}: request failed: ${err?.cause?.message ?? err?.message ?? err}`);
  }
  const text = await res.text();
  if (res.status === 202) return null;
  if (!res.ok) {
    const hint = {
      401: 'the token is wrong, revoked or expired (check the Tokens screen on the phone)',
      404: 'wrong URL (expected …/mcp)',
      429: 'rate limited; wait a minute',
      503: 'the phone is not connected to the relay',
      504: 'the phone did not answer in time',
    }[res.status];
    throw new SmokeError(`${message.method}: HTTP ${res.status}${hint ? ` (${hint})` : ''}: ${text.slice(0, 500)}`);
  }
  const contentType = res.headers.get('content-type') ?? '';
  const reply = contentType.includes('text/event-stream') ? fromEventStream(text, message.id) : parseJson(text, message.method);
  if (reply?.error) {
    throw new SmokeError(`${message.method}: JSON-RPC error ${reply.error.code}: ${reply.error.message}`);
  }
  return reply;
}

function parseJson(text, method) {
  try {
    return JSON.parse(text);
  } catch {
    throw new SmokeError(`${method}: response is not JSON: ${text.slice(0, 200)}`);
  }
}

/** Picks the JSON-RPC response with [id] out of an SSE body. */
function fromEventStream(text, id) {
  for (const event of text.split(/\r?\n\r?\n/)) {
    const data = event
      .split(/\r?\n/)
      .filter((line) => line.startsWith('data:'))
      .map((line) => line.slice(5).trimStart())
      .join('\n');
    if (!data) continue;
    try {
      const message = JSON.parse(data);
      if (message.id === id) return message;
    } catch {
      // Not JSON; keep looking.
    }
  }
  throw new SmokeError(`no response with id ${id} in event stream`);
}

function request(method, params) {
  return post({ jsonrpc: '2.0', id: nextId++, method, ...(params ? { params } : {}) });
}

async function main() {
  const init = await request('initialize', {
    protocolVersion: PROTOCOL_VERSION,
    capabilities: {},
    clientInfo: { name: 'droidhands-smoke', version: '0.1.0' },
  });
  const result = init?.result;
  if (!result?.protocolVersion) throw new SmokeError('initialize: missing result.protocolVersion');
  negotiatedVersion = result.protocolVersion;
  const server = result.serverInfo ? `${result.serverInfo.name} ${result.serverInfo.version ?? ''}`.trim() : 'unknown';
  console.log(`initialize: ok (server: ${server}, protocol ${negotiatedVersion})`);

  await post({ jsonrpc: '2.0', method: 'notifications/initialized' });

  const tools = [];
  let cursor;
  do {
    const page = await request('tools/list', cursor ? { cursor } : undefined);
    if (!Array.isArray(page?.result?.tools)) throw new SmokeError('tools/list: missing result.tools');
    tools.push(...page.result.tools);
    cursor = page.result.nextCursor;
  } while (cursor);

  console.log(`tools/list: ${tools.length} tool(s)`);
  for (const tool of tools) console.log(`  - ${tool.name}`);
}

main().then(
  () => process.exit(0),
  (err) => {
    console.error(`smoke: FAILED: ${err instanceof SmokeError ? err.message : err?.stack ?? err}`);
    process.exit(1);
  },
);
