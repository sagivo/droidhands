#!/usr/bin/env node
// E2E: one-tap pairing exactly as an MCP client (Claude.ai / Claude Code / ChatGPT) performs it.
// usage: [PB_TOKEN_FILE=path] node scripts/e2e/pairing.mjs <connectUrl> [--approve-with-ui]
//   connectUrl: https://relay/d/<deviceId>/mcp (shown in the app / welcome.connectUrl)
import { createHash, randomBytes } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { writeFileSync } from 'node:fs';
import { McpClient, sleep } from './lib.mjs';

const connectUrl = process.argv[2];
if (!connectUrl) throw new Error('usage: pairing.mjs <connectUrl> [--approve-with-ui]');
const approveWithUi = process.argv.includes('--approve-with-ui');
const log = (...a) => console.log('•', ...a);
const b64url = (buf) => buf.toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');

// 1. Unauthenticated request → 401 with resource_metadata.
const r401 = await fetch(connectUrl, { method: 'POST', headers: { 'content-type': 'application/json' }, body: '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}' });
const wwwAuth = r401.headers.get('www-authenticate') || '';
const rmUrl = /resource_metadata="([^"]+)"/.exec(wwwAuth)?.[1];
log('401 →', r401.status, rmUrl ? 'resource_metadata ok' : `MISSING resource_metadata (${wwwAuth})`);
if (r401.status !== 401 || !rmUrl) process.exit(1);

// 2. Protected resource metadata → authorization server metadata.
const rm = await (await fetch(rmUrl)).json();
const asBase = rm.authorization_servers[0];
const as = await (await fetch(`${asBase}/.well-known/oauth-authorization-server`)).json();
log('discovery → resource', rm.resource, '| AS', as.issuer);

// 3. Dynamic client registration (public client, loopback redirect like Claude Code).
const redirectUri = 'http://localhost:33418/callback';
const reg = await (await fetch(as.registration_endpoint, {
  method: 'POST', headers: { 'content-type': 'application/json' },
  body: JSON.stringify({ client_name: 'Claude Code (E2E)', redirect_uris: [redirectUri], grant_types: ['authorization_code'], response_types: ['code'], token_endpoint_auth_method: 'none' }),
})).json();
log('registered client', reg.client_id?.slice(0, 16) + '…');

// 4. Authorization request with PKCE.
const verifier = b64url(randomBytes(32));
const challenge = b64url(createHash('sha256').update(verifier).digest());
const state = b64url(randomBytes(12));
const authUrl = new URL(as.authorization_endpoint);
for (const [k, v] of Object.entries({ response_type: 'code', client_id: reg.client_id, redirect_uri: redirectUri, code_challenge: challenge, code_challenge_method: 'S256', state, resource: rm.resource, scope: 'phone' })) authUrl.searchParams.set(k, v);
const page = await (await fetch(authUrl)).text();
const pollPath = /\/oauth\/pair\/[^"'\s?]+\?t=[A-Za-z0-9_-]+/.exec(page)?.[0];
const code4 = /\b([A-HJ-NP-Z2-9]{4})\b/.exec(page.replace(/<[^>]+>/g, ' '))?.[1];
log('authorize page → poll', pollPath ? 'ok' : 'MISSING', '| display code', code4);
if (!pollPath) { console.log(page.slice(0, 1500)); process.exit(1); }

// 5. Approve on the phone.
if (approveWithUi) {
  const ui = (...a) => execFileSync('timeout', ['-k', '2', '60', 'python3', '-I', new URL('./ui.py', import.meta.url).pathname, ...a], { encoding: 'utf8' }).trim();
  const adbPath = process.env.ADB || '/opt/homebrew/share/android-commandlinetools/platform-tools/adb';
  // The shade can take a moment to open, so look for the notification a few times.
  let opened = false;
  for (let i = 0; i < 5 && !opened; i++) {
    await sleep(2000);
    execFileSync('timeout', ['10', adbPath, 'shell', 'cmd', 'statusbar', 'expand-notifications']);
    await sleep(1500);
    try { ui('tap', 'wants to connect'); opened = true; } catch { /* not shown yet */ }
  }
  await sleep(2000);
  // Only tap Allow on the approval screen itself; elsewhere "Allow" may match unrelated text.
  try {
    ui('has', 'Connect an AI agent?');
    log(ui('tap', 'Allow'));
  } catch { log('approval screen not found; approve manually on the phone'); }
} else {
  log(`→ approve on the phone now (code ${code4})…`);
}

// 6. Poll like the browser page does.
let decision;
for (let i = 0; i < 120 && !decision; i++) {
  const s = await (await fetch(new URL(pollPath, asBase))).json();
  if (s.status !== 'pending') decision = s;
  else await sleep(1500);
}
log('decision →', decision?.status);
if (decision?.status !== 'approved') process.exit(1);
const redirect = new URL(decision.redirect);
if (redirect.searchParams.get('state') !== state) throw new Error('state mismatch');

// 7. Token exchange.
const tok = await (await fetch(as.token_endpoint, {
  method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded' },
  body: new URLSearchParams({ grant_type: 'authorization_code', code: redirect.searchParams.get('code'), redirect_uri: redirectUri, client_id: reg.client_id, code_verifier: verifier, resource: rm.resource }),
})).json();
log('token exchange →', tok.token_type, tok.access_token ? tok.access_token.slice(0, 12) + '…' : JSON.stringify(tok));
// PB_TOKEN_FILE=path saves the token (mode 600) for chaining, e.g. into scenarios.mjs.
if (process.env.PB_TOKEN_FILE && tok.access_token) writeFileSync(process.env.PB_TOKEN_FILE, tok.access_token, { mode: 0o600 });

// 8. Use it.
const c = new McpClient(connectUrl, tok.access_token);
const init = await c.initialize();
const caps = await c.call('bridge_capabilities');
log('initialize →', init.serverInfo.name, '| token name:', caps.json?.token?.name, '| scopes:', caps.json?.token?.scopes?.length);
console.log('\nPAIRING E2E PASSED');
