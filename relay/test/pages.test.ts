import assert from 'node:assert/strict';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { SafeHtml, escapeHtml, html } from '../src/pages/html.js';
import { connectReady, httpRequest, newIdentity, startRelay, type HttpResult } from './helpers.js';
import { REDIRECT_URI, approveNext, newPkce, openAuthorize, postJson, registerClient, type PairRequestFrame } from './oauthHelpers.js';

function assertPageHeaders(res: HttpResult): void {
  assert.match(res.headers['content-type'] ?? '', /^text\/html; charset=utf-8$/);
  const csp = String(res.headers['content-security-policy'] ?? '');
  assert.match(csp, /default-src 'none'/);
  assert.match(csp, /frame-ancestors 'none'/);
  assert.match(csp, /script-src 'nonce-[A-Za-z0-9+/=]+'/);
  assert.doesNotMatch(csp, /unsafe-inline|unsafe-eval/);
  assert.equal(res.headers['referrer-policy'], 'no-referrer');
  assert.equal(res.headers['x-frame-options'], 'DENY');
  assert.equal(res.headers['cache-control'], 'no-store');
  // Every inline script/style carries the response's nonce.
  const nonce = /'nonce-([^']+)'/.exec(csp)?.[1];
  for (const tag of res.body.match(/<(script|style)\b[^>]*>/g) ?? []) assert.ok(tag.includes(`nonce="${nonce}"`), tag);
  assert.doesNotMatch(res.body, /\sstyle="|\son[a-z]+="/, 'no inline style or event-handler attributes');
}

test('html template escapes interpolations, but not nested templates', () => {
  const evil = `<img src=x onerror="alert('1')">&`;
  assert.equal(escapeHtml(evil), '&lt;img src=x onerror=&quot;alert(&#39;1&#39;)&quot;&gt;&amp;');
  const nested = html`<b>${evil}</b>`;
  assert.equal(html`<p>${nested}${[evil, 1]}${null}${undefined}${false}</p>`.value, `<p><b>${escapeHtml(evil)}</b>${escapeHtml(evil)}1</p>`);
  assert.ok(nested instanceof SafeHtml);
});

test('XSS: a hostile client_name and device name are escaped on the waiting page and sanitized for the phone', async (t) => {
  const relay = await startRelay(t);
  const deviceName = '</strong><script>alert("dev")</script>';
  const device = await connectReady(relay, { hello: { deviceName } });
  const clientName = '"><script>alert(1)</script><img src=x onerror=alert(2)>‮evil\n';
  const clientId = await registerClient(relay, { client_name: clientName, redirect_uris: [REDIRECT_URI] });
  const page = await openAuthorize(relay, { clientId, pkce: newPkce(), deviceId: device.identity.deviceId, state: '"><script>x</script>' });
  assertPageHeaders(page.res);
  const body = page.res.body;
  assert.doesNotMatch(body, /<script>alert/);
  assert.doesNotMatch(body, /<img src=x/);
  assert.match(body, /&quot;&gt;&lt;script&gt;alert\(1\)&lt;\/script&gt;/);
  assert.match(body, /&lt;\/strong&gt;&lt;script&gt;alert\(&quot;dev&quot;\)/);
  assert.equal((body.match(/<script\b/g) ?? []).length, 1, 'only the page script');

  const request = await device.nextFrame<PairRequestFrame>('pair_request');
  assert.ok(!/[\u0000-\u001f‮]/.test(request.clientName), 'control and bidi characters stripped');
  assert.ok(Array.from(request.clientName).length <= 60);
});

test('client names are truncated to 60 characters', async (t) => {
  const relay = await startRelay(t);
  const device = await connectReady(relay);
  const clientId = await registerClient(relay, { client_name: '🙂'.repeat(100), redirect_uris: [REDIRECT_URI] });
  await openAuthorize(relay, { clientId, pkce: newPkce(), deviceId: device.identity.deviceId });
  const { request } = await approveNext(device);
  assert.equal(request.clientName, '🙂'.repeat(60));
});

test('error and offline pages carry the security headers', async (t) => {
  const relay = await startRelay(t);
  assertPageHeaders(await httpRequest(`${relay.url}/oauth/authorize?client_id=nope`));
  const clientId = await registerClient(relay);
  const offline = await openAuthorizeRaw(relay.url, clientId);
  assert.equal(offline.status, 503);
  assertPageHeaders(offline);
});

/** An authorization request for a phone that is not connected. */
async function openAuthorizeRaw(base: string, clientId: string): Promise<HttpResult> {
  const url = new URL(`${base}/oauth/authorize`);
  url.searchParams.set('response_type', 'code');
  url.searchParams.set('client_id', clientId);
  url.searchParams.set('code_challenge', newPkce().challenge);
  url.searchParams.set('code_challenge_method', 'S256');
  url.searchParams.set('resource', `${base}/d/${newIdentity().deviceId}/mcp`);
  return httpRequest(url.toString());
}

test('/setup without an APK: no install link, inline QR of the setup URL, intent link', async (t) => {
  const dir = await mkdtemp(join(tmpdir(), 'pb-public-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const relay = await startRelay(t, { publicDir: dir, publicUrl: 'https://relay.example.com' });
  const res = await httpRequest(`${relay.url}/setup`);
  assert.equal(res.status, 200);
  assertPageHeaders(res);
  assert.doesNotMatch(res.body, /download\/phonebridge\.apk/);
  assert.match(res.body, /<svg class="qr"[^>]*viewBox="0 0 \d+ \d+"/);
  assert.match(
    res.body,
    /href="intent:\/\/configure\?relay=https%3A%2F%2Frelay\.example\.com#Intent;scheme=phonebridge;package=dev\.phonebridge\.android;end"/,
  );
  assert.match(res.body, /data-key-required="false"/);
  assert.equal((await httpRequest(`${relay.url}/download/phonebridge.apk`)).status, 404);
});

test('/setup with an APK: install link and download with the Android content type', async (t) => {
  const dir = await mkdtemp(join(tmpdir(), 'pb-public-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const apk = Buffer.from('PK\u0003\u0004 fake apk bytes');
  await writeFile(join(dir, 'phonebridge.apk'), apk);
  const relay = await startRelay(t, { publicDir: dir });
  const res = await httpRequest(`${relay.url}/setup`);
  assert.match(res.body, /href="\/download\/phonebridge\.apk"/);

  const download = await httpRequest(`${relay.url}/download/phonebridge.apk`);
  assert.equal(download.status, 200);
  assert.equal(download.headers['content-type'], 'application/vnd.android.package-archive');
  assert.equal(download.headers['content-length'], String(apk.length));
  assert.equal(download.headers['content-disposition'], 'attachment; filename="phonebridge.apk"');
  assert.equal(download.body, apk.toString('utf8'));
  const head = await httpRequest(`${relay.url}/download/phonebridge.apk`, { method: 'HEAD' });
  assert.equal(head.status, 200);
  assert.equal(head.body, '');
  assert.equal((await httpRequest(`${relay.url}/download/other.apk`)).status, 404);
  assert.equal((await httpRequest(`${relay.url}/download/..%2Fphonebridge.apk`)).status, 404);
});

test('/setup with an access key: no server-side QR; /setup/qr draws one only for the right key', async (t) => {
  const accessKey = 'k+y/=secret-access-key';
  const relay = await startRelay(t, { accessKey, publicUrl: 'https://relay.example.com', rateLimitAuthFailuresPerMin: 100 });
  const page = await httpRequest(`${relay.url}/setup`);
  assert.match(page.body, /data-key-required="true"/);
  assert.doesNotMatch(page.body, /<svg/);
  assert.ok(!page.body.includes(accessKey) && !page.body.includes(encodeURIComponent(accessKey)), 'the key is never rendered');

  const ok = await postJson(`${relay.url}/setup/qr`, { key: accessKey });
  assert.equal(ok.status, 200);
  const qr = JSON.parse(ok.body) as { size: number; path: string };
  assert.ok(qr.size >= 21 + 8);
  assert.match(qr.path, /^(M\d+ \d+h\d+v1h-\d+z)+$/);

  for (const key of ['wrong', undefined, 5]) {
    const res = await postJson(`${relay.url}/setup/qr`, { key });
    assert.equal(res.status, 403, String(key));
  }
  assert.equal((await httpRequest(`${relay.url}/setup/qr`)).status, 405);
});
