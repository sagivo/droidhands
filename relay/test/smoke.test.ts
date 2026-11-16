import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { connectReady, newIdentity, startRelay, tokenFor, type RequestFrame } from './helpers.js';

const SMOKE = fileURLToPath(new URL('../../scripts/smoke.mjs', import.meta.url));

interface RunResult {
  readonly code: number;
  readonly stdout: string;
  readonly stderr: string;
}

function runSmoke(...args: string[]): Promise<RunResult> {
  return new Promise((resolve) => {
    execFile(process.execPath, [SMOKE, ...args], { env: { ...process.env, DROIDHANDS_TOKEN: '' } }, (err, stdout, stderr) => {
      const code = err === null ? 0 : typeof err.code === 'number' ? err.code : -1;
      resolve({ code, stdout, stderr });
    });
  });
}

/** A tiny stateless MCP server, as the device would run it (two pages of tools). */
function fakeMcp(expectedAuth: string) {
  return (request: RequestFrame) => {
    if (request.headers.authorization !== expectedAuth) return { status: 401, body: null };
    const message = JSON.parse(request.body) as { id?: number; method: string; params?: { cursor?: string } };
    if (message.id === undefined) return { status: 202, body: null };
    const reply = (result: unknown) => ({
      status: 200,
      body: JSON.stringify({ jsonrpc: '2.0', id: message.id, result }),
      headers: { 'content-type': 'application/json' },
    });
    switch (message.method) {
      case 'initialize':
        return reply({ protocolVersion: '2025-06-18', capabilities: { tools: {} }, serverInfo: { name: 'DroidHands', version: '0.1.0' } });
      case 'tools/list':
        return message.params?.cursor === 'page2'
          ? reply({ tools: [{ name: 'screen_observe' }] })
          : reply({ tools: [{ name: 'contacts_search' }, { name: 'device_status' }], nextCursor: 'page2' });
      default:
        return { status: 200, body: JSON.stringify({ jsonrpc: '2.0', id: message.id, error: { code: -32601, message: 'nope' } }) };
    }
  };
}

test('smoke script: initialize + tools/list through the relay', async (t) => {
  const relay = await startRelay(t);
  const device = await connectReady(relay);
  const token = tokenFor(device.identity.deviceId);
  device.serve(fakeMcp(`Bearer ${token}`));

  const result = await runSmoke(`${relay.url}/mcp`, token);
  assert.equal(result.code, 0, result.stderr);
  assert.match(result.stdout, /initialize: ok \(server: DroidHands 0\.1\.0, protocol 2025-06-18\)/);
  assert.match(result.stdout, /tools\/list: 3 tool\(s\)/);
  for (const name of ['contacts_search', 'device_status', 'screen_observe']) assert.match(result.stdout, new RegExp(`- ${name}`));
  assert.ok(!result.stdout.includes(token) && !result.stderr.includes(token), 'token is never printed');
});

test('smoke script: fails with a hint when the device is offline or the token is rejected', async (t) => {
  const relay = await startRelay(t);
  const offline = await runSmoke(`${relay.url}/mcp`, tokenFor(newIdentity().deviceId));
  assert.equal(offline.code, 1);
  assert.match(offline.stderr, /HTTP 503 \(the phone is not connected to the relay\)/);

  const device = await connectReady(relay);
  device.serve(fakeMcp('Bearer something-else'));
  const rejected = await runSmoke(`${relay.url}/mcp`, tokenFor(device.identity.deviceId));
  assert.equal(rejected.code, 1);
  assert.match(rejected.stderr, /HTTP 401/);
});

test('smoke script: usage error without arguments', async () => {
  const result = await runSmoke();
  assert.equal(result.code, 2);
  assert.match(result.stderr, /usage/);
});
