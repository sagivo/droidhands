import assert from 'node:assert/strict';
import { test } from 'node:test';
import { bodyParams, checkDeviceAuthorization, parseDeviceCode } from '../src/oauth/deviceFlow.js';
import { wantsAgentGuide } from '../src/agentGuide.js';
import { connectReady, httpRequest, newIdentity, postMcp, startRelay, type FakeDevice } from './helpers.js';
import { approveNext, denyNext, postForm, postJson, registerClient, type PairRequestFrame } from './oauthHelpers.js';

const DEVICE_GRANT = 'urn:ietf:params:oauth:grant-type:device_code';

interface DeviceAuthorization {
  device_code: string;
  user_code: string;
  verification_uri: string;
  expires_in: number;
  interval: number;
  token_endpoint: string;
  message: string;
}

async function startSignIn(base: string, device: FakeDevice, params: Record<string, string> = {}): Promise<DeviceAuthorization> {
  const res = await postForm(`${base}/oauth/device_authorization`, {
    resource: `${base}/d/${device.identity.deviceId}/mcp`,
    client_name: 'Instinct',
    ...params,
  });
  assert.equal(res.status, 200, res.body);
  return JSON.parse(res.body) as DeviceAuthorization;
}

function poll(base: string, deviceCode: string): Promise<{ status: number; body: string }> {
  return postForm(`${base}/oauth/token`, { grant_type: DEVICE_GRANT, device_code: deviceCode });
}

const errorOf = (res: { body: string }): string => (JSON.parse(res.body) as { error: string }).error;

test('agent sign-in: code, pending, approve, token, pair_complete; the device code is single-use', async (t) => {
  const relay = await startRelay(t);
  const device = await connectReady(relay);
  const auth = await startSignIn(relay.url, device);
  assert.match(auth.user_code, /^[A-HJ-NP-Z2-9]{4}$/);
  assert.equal(auth.verification_uri, `${relay.url}/d/${device.identity.deviceId}/mcp`);
  assert.equal(auth.interval, 5);
  assert.ok(auth.expires_in > 0 && auth.expires_in <= 180);
  assert.equal(auth.token_endpoint, `${relay.url}/oauth/token`);
  assert.match(auth.message, new RegExp(auth.user_code));

  const pending = await poll(relay.url, auth.device_code);
  assert.equal(pending.status, 400);
  assert.equal(errorOf(pending), 'authorization_pending');

  const approval = await approveNext(device);
  assert.equal(approval.request.clientName, 'Instinct');
  assert.equal(approval.request.redirectHost, 'agent sign-in');
  assert.equal((approval.request as PairRequestFrame & { flow?: string }).flow, 'device');
  assert.equal(approval.request.displayCode, auth.user_code);

  // The phone's answer arrives asynchronously.
  let res = await poll(relay.url, auth.device_code);
  for (let i = 0; i < 50 && errorOf(res) === 'authorization_pending'; i++) {
    await new Promise((r) => setTimeout(r, 20));
    res = await poll(relay.url, auth.device_code);
  }
  assert.equal(res.status, 200, res.body);
  assert.deepEqual(JSON.parse(res.body), { access_token: approval.accessToken, token_type: 'Bearer', scope: 'phone' });
  const complete = await device.nextFrame('pair_complete');
  assert.equal(complete.tokenId, approval.tokenId);

  const again = await poll(relay.url, auth.device_code);
  assert.equal(errorOf(again), 'invalid_grant');
});

test('agent sign-in: denied on the phone → access_denied; JSON bodies work', async (t) => {
  const relay = await startRelay(t);
  const device = await connectReady(relay);
  const res = await postJson(`${relay.url}/oauth/device_authorization`, {
    resource: `${relay.url}/d/${device.identity.deviceId}/mcp`,
    client_id: 'Muse',
  });
  assert.equal(res.status, 200, res.body);
  const auth = JSON.parse(res.body) as DeviceAuthorization;
  const request = await denyNext(device);
  assert.equal(request.clientName, 'Muse');
  let polled = await postJson(`${relay.url}/oauth/token`, { grant_type: DEVICE_GRANT, device_code: auth.device_code });
  for (let i = 0; i < 50 && errorOf(polled) === 'authorization_pending'; i++) {
    await new Promise((r) => setTimeout(r, 20));
    polled = await postJson(`${relay.url}/oauth/token`, { grant_type: DEVICE_GRANT, device_code: auth.device_code });
  }
  assert.equal(errorOf(polled), 'access_denied');
});

test('agent sign-in: request errors', async (t) => {
  const relay = await startRelay(t);
  const url = `${relay.url}/oauth/device_authorization`;
  const offlineId = newIdentity().deviceId;
  const missing = await postForm(url, { client_name: 'x' });
  assert.equal(missing.status, 400);
  assert.equal(errorOf(missing), 'invalid_request');
  const foreign = await postForm(url, { resource: `https://elsewhere.example/d/${offlineId}/mcp` });
  assert.equal(errorOf(foreign), 'invalid_target');
  const offline = await postForm(url, { resource: `${relay.url}/d/${offlineId}/mcp` });
  assert.equal(offline.status, 503);
  assert.equal(errorOf(offline), 'temporarily_unavailable');
  const text = await httpRequest(url, { method: 'POST', headers: { 'content-type': 'text/plain' }, body: 'hi' });
  assert.equal(errorOf(text), 'invalid_request');
  const get = await httpRequest(url);
  assert.equal(get.status, 405);
  const garbage = await poll(relay.url, 'nope');
  assert.equal(errorOf(garbage), 'invalid_grant');
  // An authorization-code request in JSON is still refused (only the device grant takes JSON).
  const json = await postJson(`${relay.url}/oauth/token`, { grant_type: 'authorization_code', code: 'c' });
  assert.equal(errorOf(json), 'invalid_request');
});

test('agent sign-in: a registered client is named by its registration', async (t) => {
  const relay = await startRelay(t);
  const device = await connectReady(relay);
  const clientId = await registerClient(relay, { client_name: 'Grok', redirect_uris: ['grokbot://oauth/callback'] });
  await startSignIn(relay.url, device, { client_id: clientId, client_name: 'Spoofed' });
  const approval = await approveNext(device);
  assert.equal(approval.request.clientName, 'Grok');
});

test('agent guide: GET on the connection URL and /llms.txt; MCP clients are unaffected', async (t) => {
  const relay = await startRelay(t);
  const deviceId = newIdentity().deviceId;
  const connector = `${relay.url}/d/${deviceId}/mcp`;
  const guide = await httpRequest(connector, { headers: { accept: '*/*' } });
  assert.equal(guide.status, 200);
  assert.match(String(guide.headers['content-type']), /^text\/plain/);
  assert.ok(guide.body.includes(`MCP server URL: ${connector}`));
  assert.ok(guide.body.includes(`${relay.url}/oauth/device_authorization`));
  // A fetcher that asks for HTML like a browser gets the setup page, with the same guide inside.
  const html = await httpRequest(connector, { headers: { accept: 'text/html,*/*' } });
  assert.match(String(html.headers['content-type']), /^text\/html/);
  assert.ok(html.body.includes(`MCP server URL: ${connector}`));
  assert.ok(html.body.includes(`${relay.url}/oauth/device_authorization`));
  const markdown = await httpRequest(connector, { headers: { accept: 'text/markdown' } });
  assert.match(String(markdown.headers['content-type']), /^text\/markdown/);
  const sse = await httpRequest(connector, { headers: { accept: 'text/event-stream' } });
  assert.equal(sse.status, 405);
  const unauthorized = await postMcp(relay, { path: `/d/${deviceId}/mcp` });
  assert.equal(unauthorized.status, 401);
  const llms = await httpRequest(`${relay.url}/llms.txt`);
  assert.equal(llms.status, 200);
  assert.ok(llms.body.includes(`${relay.url}/d/<id>/mcp`));
  const banner = await httpRequest(`${relay.url}/`);
  assert.ok(banner.body.includes(`${relay.url}/llms.txt`));
});

test('deviceFlow units: body parsing, device codes, names, guide detection', () => {
  assert.equal(bodyParams('application/json', '{"a":"b","n":3,"o":{}}')?.toString(), 'a=b&n=3');
  assert.equal(bodyParams('application/json', '[1]'), null);
  assert.equal(bodyParams('application/json', '{'), null);
  assert.equal(bodyParams('text/plain', 'a=b'), null);
  assert.equal(bodyParams('application/x-www-form-urlencoded; charset=utf-8', 'a=b')?.get('a'), 'b');

  const id = `p_${'A'.repeat(22)}`;
  assert.deepEqual(parseDeviceCode(`${id}.${'b'.repeat(22)}`), { id, pageToken: 'b'.repeat(22) });
  assert.equal(parseDeviceCode('p_short.token'), null);
  assert.equal(parseDeviceCode(`${id}`), null);
  assert.equal(parseDeviceCode(`${id}.has space here!!`), null);

  const base = 'https://relay.example';
  const secret = Buffer.alloc(32, 1);
  const deviceId = newIdentity().deviceId;
  const params = (p: Record<string, string>) => new URLSearchParams({ resource: `${base}/d/${deviceId}/mcp`, ...p });
  const name = (p: Record<string, string>): string => {
    const r = checkDeviceAuthorization(params(p), base, secret);
    assert.ok(r.ok);
    return r.clientName;
  };
  assert.equal(name({}), 'AI agent');
  assert.equal(name({ client_name: '  Meta‮ Muse ' }), 'Meta Muse');
  assert.equal(name({ client_id: 'instinct-agent' }), 'instinct-agent');
  assert.equal(name({ client_id: 'not a name: <script>' }), 'AI agent');
  const repeated = checkDeviceAuthorization(new URLSearchParams(`resource=a&resource=b`), base, secret);
  assert.ok(!repeated.ok && repeated.error === 'invalid_request');

  assert.equal(wantsAgentGuide('GET', undefined), true);
  assert.equal(wantsAgentGuide('HEAD', 'text/html'), true);
  assert.equal(wantsAgentGuide('GET', 'application/json, text/event-stream'), false);
  assert.equal(wantsAgentGuide('POST', 'text/html'), false);
});
