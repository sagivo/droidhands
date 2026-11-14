#!/usr/bin/env node
// E2E: MCP traffic through a relay to the demo device (`./gradlew :core:runDemoDevice`), for checking
// a relay deploy (Node or Cloudflare). Run pairing.mjs against the same URL for the OAuth flow.
// usage: PB_TOKEN=<DEMO_TOKEN> [IDLE_MS=100000] node scripts/e2e/relay-interop.mjs <connectUrl>
//   connectUrl: https://relay/d/<deviceId>/mcp (the demo device prints it in RELAY_STATUS)
//   IDLE_MS: also wait this long with only heartbeats, then call again (> 75 s checks the heartbeat)
import { McpClient, sleep } from './lib.mjs';

const url = process.argv[2];
const token = process.env.PB_TOKEN;
if (!url || !token) throw new Error('usage: PB_TOKEN=<token> node relay-interop.mjs <connectUrl>');
const idleMs = Number(process.env.IDLE_MS || 0);
const ok = (cond, msg) => {
  if (!cond) throw new Error(`FAIL: ${msg}`);
  console.log(`ok  ${msg}`);
};
const timed = async (fn) => {
  const t = Date.now();
  const r = await fn();
  return [r, Date.now() - t];
};

const anon = await new McpClient(url, null).post({ jsonrpc: '2.0', id: 1, method: 'initialize', params: {} }, { auth: false });
ok(anon.status === 401 && /resource_metadata/.test(anon.headers['www-authenticate'] || ''), `no token -> 401 with resource_metadata (${anon.status})`);
const bad = await new McpClient(url, `pb1_${'x'.repeat(40)}`).post({ jsonrpc: '2.0', id: 1, method: 'tools/list' });
ok(bad.status === 401, `unknown token -> 401 (${bad.status})`);

const c = new McpClient(url, token);
const [init, initMs] = await timed(() => c.initialize());
ok(init.serverInfo?.name, `initialize -> ${init.serverInfo?.name} ${init.protocolVersion} (${initMs} ms)`);
const tools = (await c.listTools()).map((t) => t.name);
ok(tools.includes('echo') && tools.includes('demo_send'), `tools/list -> ${tools.join(', ')}`);
const text = 'hello through the relay 👋 שלום';
const [echo, echoMs] = await timed(() => c.call('echo', { text }));
ok(echo.text === text, `echo UTF-8 round trip (${echoMs} ms)`);
const sent = await c.call('demo_send', { to: 'Alice', text: 'hi' });
ok(sent.json?.sent === true, `demo_send with phone confirmation -> ${sent.text}`);
const img = await c.call('demo_image');
ok(img.images.length === 1 && img.images[0].mimeType === 'image/png', 'demo_image -> 1 image/png');
for (const kib of [100, 500, 950]) {
  const big = 'a'.repeat(kib * 1024);
  const [r, ms] = await timed(() => c.call('echo', { text: big }));
  ok(r.text.length === big.length, `echo ${kib} KiB request and response (${ms} ms)`);
}
const burst = await Promise.all(Array.from({ length: 10 }, (_, i) => c.call('echo', { text: `p${i}` })));
ok(burst.every((r, i) => r.text === `p${i}`), '10 parallel calls');
if (idleMs) {
  console.log(`idle ${idleMs / 1000} s (heartbeats only)...`);
  await sleep(idleMs);
  const [after, ms] = await timed(() => c.call('echo', { text: 'after idle' }));
  ok(after.text === 'after idle', `call after ${idleMs / 1000} s idle (${ms} ms)`);
}
console.log('\nRELAY INTEROP PASSED');
