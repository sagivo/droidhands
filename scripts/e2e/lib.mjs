// Minimal MCP (Streamable HTTP, stateless) client + adb helpers for PhoneBridge E2E tests.
// No dependencies: Node >= 20 (global fetch).
import { execFileSync } from 'node:child_process';

export class McpClient {
  constructor(url, token, { timeoutMs = 150_000 } = {}) {
    this.url = url;
    this.token = token;
    this.timeoutMs = timeoutMs;
    this.nextId = 1;
  }

  async post(body, { auth = true } = {}) {
    const headers = {
      'content-type': 'application/json',
      accept: 'application/json, text/event-stream',
      'mcp-protocol-version': '2025-06-18',
    };
    if (auth && this.token) headers.authorization = `Bearer ${this.token}`;
    const res = await fetch(this.url, {
      method: 'POST',
      headers,
      body: typeof body === 'string' ? body : JSON.stringify(body),
      signal: AbortSignal.timeout(this.timeoutMs),
    });
    const text = await res.text();
    let json = null;
    try { json = text ? JSON.parse(text) : null; } catch { /* non-JSON */ }
    return { status: res.status, headers: Object.fromEntries(res.headers), text, json };
  }

  async rpc(method, params) {
    const id = this.nextId++;
    const r = await this.post({ jsonrpc: '2.0', id, method, ...(params ? { params } : {}) });
    if (r.status !== 200) throw new Error(`${method}: HTTP ${r.status} ${r.text.slice(0, 300)}`);
    if (r.json?.error) throw new Error(`${method}: JSON-RPC ${r.json.error.code} ${r.json.error.message}`);
    return r.json.result;
  }

  async initialize() {
    const result = await this.rpc('initialize', {
      protocolVersion: '2025-06-18',
      capabilities: {},
      clientInfo: { name: 'phonebridge-e2e', version: '1.0' },
    });
    await this.post({ jsonrpc: '2.0', method: 'notifications/initialized' });
    return result;
  }

  async listTools() {
    return (await this.rpc('tools/list', {})).tools;
  }

  /** Returns {isError, text, json (parsed text when JSON), images, raw}. */
  async call(name, args = {}) {
    const raw = await this.rpc('tools/call', { name, arguments: args });
    const text = raw.content.filter((c) => c.type === 'text').map((c) => c.text).join('\n');
    const images = raw.content.filter((c) => c.type === 'image');
    return { isError: !!raw.isError, text, json: leadingJson(text), images, raw };
  }
}

/** Parses the JSON value a result starts with; tools may append a plain-text footer after it. */
function leadingJson(text) {
  try { return JSON.parse(text); } catch { /* maybe a footer follows */ }
  const open = text.trimStart()[0];
  const close = open === '[' ? ']' : open === '{' ? '}' : null;
  if (!close) return null;
  for (let end = text.lastIndexOf(close); end > 0; end = text.lastIndexOf(close, end - 1)) {
    try { return JSON.parse(text.slice(0, end + 1)); } catch { /* keep shrinking */ }
  }
  return null;
}

const ADB = process.env.ADB || '/opt/homebrew/share/android-commandlinetools/platform-tools/adb';

export function adb(...args) {
  // Host-level `timeout` as well: a wedged adb client can ignore SIGTERM from execFileSync.
  return execFileSync('timeout', ['25', ADB, ...args], { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024, timeout: 30_000 }).trim();
}

export function shell(cmd) {
  return adb('shell', cmd);
}

export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** Polls fn until it returns a truthy value or the timeout expires. */
export async function waitFor(fn, { timeoutMs = 15_000, intervalMs = 500, what = 'condition' } = {}) {
  const deadline = Date.now() + timeoutMs;
  let last;
  while (Date.now() < deadline) {
    try {
      last = await fn();
      if (last) return last;
    } catch (e) {
      last = e;
    }
    await sleep(intervalMs);
  }
  throw new Error(`Timed out waiting for ${what}${last instanceof Error ? `: ${last.message}` : ''}`);
}

/** Fixture events recorded as JSON lines in the fixture app's private files dir. */
export function fixtureEvents(pkg) {
  let out = '';
  try {
    out = shell(`run-as ${pkg} cat files/events.jsonl 2>/dev/null`);
  } catch {
    return [];
  }
  return out.split('\n').filter(Boolean).flatMap((l) => {
    try { return [JSON.parse(l)]; } catch { return []; }
  });
}

/** Thrown (via skip()) by a step whose prerequisites are missing: reported as SKIP, not as a failure. */
export class Skip extends Error {}

export function skip(reason) {
  throw new Skip(reason);
}

/** Very small test runner with readable output. */
export class Runner {
  constructor() { this.results = []; }

  async step(name, fn) {
    const t0 = Date.now();
    process.stdout.write(`• ${name} ... `);
    try {
      const detail = await fn();
      const ms = Date.now() - t0;
      console.log(`PASS (${ms} ms)${detail ? `\n    ${String(detail).slice(0, 600).replace(/\n/g, '\n    ')}` : ''}`);
      this.results.push({ name, ok: true, ms, detail });
    } catch (e) {
      const ms = Date.now() - t0;
      if (e instanceof Skip) {
        console.log(`SKIP (${e.message})`);
        this.results.push({ name, ok: true, skipped: true, ms, detail: e.message });
        return;
      }
      console.log(`FAIL (${ms} ms)\n    ${String(e?.stack || e).slice(0, 1500).replace(/\n/g, '\n    ')}`);
      this.results.push({ name, ok: false, ms, error: String(e?.message || e) });
    }
  }

  summary() {
    const failed = this.results.filter((r) => !r.ok);
    const skipped = this.results.filter((r) => r.skipped);
    const ran = this.results.length - skipped.length;
    console.log(`\n${ran - failed.length}/${ran} passed${skipped.length ? `, ${skipped.length} skipped` : ''}`);
    for (const s of skipped) console.log(`  - ${s.name}: skipped (${s.detail})`);
    for (const f of failed) console.log(`  ✗ ${f.name}: ${f.error.slice(0, 300)}`);
    return failed.length === 0;
  }
}

export function assert(cond, msg) {
  if (!cond) throw new Error(`Assertion failed: ${msg}`);
}
