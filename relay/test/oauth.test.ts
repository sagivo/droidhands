import assert from 'node:assert/strict';
import { test } from 'node:test';
import { connectReady, httpRequest, postMcp, startRelay, tokenFor, waitFor, type Frame } from './helpers.js';
import {
  REDIRECT_URI,
  approveNext,
  approvedCode,
  authorizeUrl,
  denyNext,
  exchange,
  newPkce,
  openAuthorize,
  parseWaitingPage,
  pollStatus,
  pollUntilDecided,
  postForm,
  postJson,
  redirectParams,
  registerClient,
  type PairRequestFrame,
} from './oauthHelpers.js';

test('one-tap pairing: discovery from a 401, registration, approval on the phone, token, MCP call', async (t) => {
  const relay = await startRelay(t);
  const device = await connectReady(relay, { hello: { deviceName: 'Pixel 8' } });
  const { deviceId } = device.identity;
  const connectUrl = `${relay.url}/d/${deviceId}/mcp`;

  // 1. The MCP client calls the connector URL without a token and learns where to authorize.
  const challenge = await postMcp(relay, { path: `/d/${deviceId}/mcp` });
  assert.equal(challenge.status, 401);
  const metadataUrl = /resource_metadata="([^"]+)"/.exec(challenge.headers['www-authenticate'] ?? '')?.[1];
  assert.equal(metadataUrl, `${relay.url}/.well-known/oauth-protected-resource/d/${deviceId}/mcp`);

  // 2. Protected resource metadata → authorization server metadata.
  const prm = JSON.parse((await httpRequest(metadataUrl as string)).body) as { resource: string; authorization_servers: string[] };
  assert.equal(prm.resource, connectUrl);
  const issuer = prm.authorization_servers[0] as string;
  assert.equal(issuer, relay.url);
  const as = JSON.parse((await httpRequest(`${issuer}/.well-known/oauth-authorization-server`)).body) as Record<string, string>;
  assert.equal(as.issuer, relay.url);

  // 3. Dynamic registration.
  const registered = await postJson(as.registration_endpoint as string, {
    client_name: 'Claude',
    redirect_uris: [REDIRECT_URI],
    grant_types: ['authorization_code', 'refresh_token'],
    token_endpoint_auth_method: 'none',
  });
  assert.equal(registered.status, 201);
  const clientId = (JSON.parse(registered.body) as { client_id: string }).client_id;

  // 4. The browser opens the authorization endpoint and gets the waiting page.
  const pkce = newPkce();
  const authorize = new URL(as.authorization_endpoint as string);
  for (const [name, value] of Object.entries({
    response_type: 'code',
    client_id: clientId,
    redirect_uri: REDIRECT_URI,
    code_challenge: pkce.challenge,
    code_challenge_method: 'S256',
    state: 'xyz-state',
    resource: connectUrl,
    scope: 'phone',
  })) {
    authorize.searchParams.set(name, value);
  }
  const pageRes = await httpRequest(authorize.toString(), { headers: { 'user-agent': 'TestBrowser/1.0' } });
  assert.equal(pageRes.status, 200);
  assert.match(pageRes.headers['content-type'] ?? '', /^text\/html/);
  const page = parseWaitingPage(pageRes, relay.url);
  assert.match(page.res.body, /Pixel 8/);
  assert.match(page.res.body, /Claude/);

  // 5. The phone gets the pairing request and approves.
  const { request, accessToken, tokenId } = await approveNext(device);
  assert.match(request.id, /^p_[A-Za-z0-9_-]{22}$/);
  assert.equal(request.clientName, 'Claude');
  assert.equal(request.redirectHost, 'localhost:33418');
  assert.equal(request.displayCode, page.displayCode);
  assert.match(request.displayCode, /^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{4}$/);
  assert.equal(request.remoteAddress, '127.0.0.1');
  assert.equal(request.userAgent, 'TestBrowser/1.0');
  assert.ok(request.expiresAt > Date.now() + 170_000 && request.expiresAt <= Date.now() + 180_000);

  // 6. The page's poll returns the redirect with code, state and iss.
  const status = await pollUntilDecided(page.pollUrl);
  assert.equal(status.status, 'approved');
  const params = redirectParams(status.redirect as string);
  assert.equal(params.get('state'), 'xyz-state');
  assert.equal(params.get('iss'), relay.url);
  const code = params.get('code') as string;
  assert.match(code, /^[A-Za-z0-9_-]{43}$/);

  // The no-JS page view forwards the browser to the same redirect.
  const view = await httpRequest(page.pageUrl);
  assert.equal(view.status, 303);
  assert.equal(view.headers.location, status.redirect);

  // 7. Token exchange.
  const token = await postForm(as.token_endpoint as string, {
    grant_type: 'authorization_code',
    code,
    redirect_uri: REDIRECT_URI,
    client_id: clientId,
    code_verifier: pkce.verifier,
    resource: connectUrl,
  });
  assert.equal(token.status, 200, token.body);
  assert.deepEqual(JSON.parse(token.body), { access_token: accessToken, token_type: 'Bearer', scope: 'phone' });
  assert.equal(token.headers['cache-control'], 'no-store');
  assert.equal(token.headers.pragma, 'no-cache');
  assert.equal(token.headers['access-control-allow-origin'], '*');

  // 8. The phone learns the agent got its token.
  assert.deepEqual(await device.nextFrame('pair_complete'), { type: 'pair_complete', id: request.id, tokenId });

  // 9. The token works on the connector URL.
  device.serve((req) => {
    assert.equal(req.headers.authorization, `Bearer ${accessToken}`);
    return { status: 200, body: '{"jsonrpc":"2.0","id":1,"result":{"tools":[]}}', headers: { 'content-type': 'application/json' } };
  });
  const mcp = await postMcp(relay, { path: `/d/${deviceId}/mcp`, token: accessToken });
  assert.equal(mcp.status, 200);
  assert.equal(mcp.body, '{"jsonrpc":"2.0","id":1,"result":{"tools":[]}}');
});

test('denied on the phone: access_denied redirect with state', async (t) => {
  const relay = await startRelay(t);
  const device = await connectReady(relay);
  const page = await openAuthorize(relay, {
    clientId: await registerClient(relay),
    pkce: newPkce(),
    deviceId: device.identity.deviceId,
    state: 's-1',
  });
  await denyNext(device);
  const status = await pollUntilDecided(page.pollUrl);
  assert.equal(status.status, 'denied');
  const params = redirectParams(status.redirect as string);
  assert.equal(params.get('error'), 'access_denied');
  assert.equal(params.get('state'), 's-1');
  assert.equal(params.get('iss'), relay.url);
  assert.equal(params.get('code'), null);
});

test('expiry before the phone answers: pair_cancel, status expired, access_denied', async (t) => {
  const relay = await startRelay(t, { pairingTtlMs: 150 });
  const device = await connectReady(relay);
  const page = await openAuthorize(relay, { clientId: await registerClient(relay), pkce: newPkce(), deviceId: device.identity.deviceId });
  const request = await device.nextFrame<PairRequestFrame>('pair_request');
  assert.deepEqual(await device.nextFrame('pair_cancel'), { type: 'pair_cancel', id: request.id });
  const status = await pollStatus(page.pollUrl);
  assert.equal(status.status, 'expired');
  assert.equal(redirectParams(status.redirect as string).get('error'), 'access_denied');

  // A late approval is answered with pair_abandoned so the phone deletes the token.
  const late = tokenFor(device.identity.deviceId);
  const tokenId = late.split('.')[2] as string;
  device.send({ type: 'pair_response', id: request.id, approved: true, accessToken: late, tokenId });
  assert.deepEqual(await device.nextFrame('pair_abandoned'), { type: 'pair_abandoned', id: request.id, tokenId });
});

test('code never exchanged: pair_abandoned with the tokenId, then the code is dead', async (t) => {
  const relay = await startRelay(t, { authCodeTtlMs: 150 });
  const device = await connectReady(relay);
  const { clientId, pkce, code, approval, pairingId } = await approvedCode(relay, device);
  assert.deepEqual(await device.nextFrame('pair_abandoned'), { type: 'pair_abandoned', id: pairingId, tokenId: approval.tokenId });
  const res = await exchange(relay, { code, client_id: clientId, code_verifier: pkce.verifier });
  assert.equal(res.status, 400);
  assert.equal((JSON.parse(res.body) as { error: string }).error, 'invalid_grant');
});

test('PKCE mismatch: invalid_grant, and the code is burned (pair_abandoned)', async (t) => {
  const relay = await startRelay(t);
  const device = await connectReady(relay);
  const { clientId, pkce, code, approval } = await approvedCode(relay, device);
  const wrong = await exchange(relay, { code, client_id: clientId, code_verifier: newPkce().verifier });
  assert.equal(wrong.status, 400);
  assert.equal((JSON.parse(wrong.body) as { error: string }).error, 'invalid_grant');
  assert.equal((await device.nextFrame('pair_abandoned')).tokenId, approval.tokenId);
  const retry = await exchange(relay, { code, client_id: clientId, code_verifier: pkce.verifier });
  assert.equal((JSON.parse(retry.body) as { error: string }).error, 'invalid_grant');
});

test('code reuse: the second exchange fails', async (t) => {
  const relay = await startRelay(t);
  const device = await connectReady(relay);
  const { clientId, pkce, code } = await approvedCode(relay, device);
  const first = await exchange(relay, { code, client_id: clientId, code_verifier: pkce.verifier });
  assert.equal(first.status, 200);
  const second = await exchange(relay, { code, client_id: clientId, code_verifier: pkce.verifier });
  assert.equal(second.status, 400);
  assert.equal((JSON.parse(second.body) as { error: string }).error, 'invalid_grant');
});

test('token request binding: other client, other redirect_uri, other resource', async (t) => {
  const relay = await startRelay(t);
  const device = await connectReady(relay);
  const other = await registerClient(relay, { client_name: 'Other', redirect_uris: [REDIRECT_URI] });

  const a = await approvedCode(relay, device);
  const otherClient = await exchange(relay, { code: a.code, client_id: other, code_verifier: a.pkce.verifier });
  assert.equal((JSON.parse(otherClient.body) as { error: string }).error, 'invalid_grant');

  const b = await approvedCode(relay, device);
  const otherRedirect = await exchange(relay, {
    code: b.code,
    client_id: b.clientId,
    code_verifier: b.pkce.verifier,
    redirect_uri: 'http://localhost:9999/callback',
  });
  assert.equal((JSON.parse(otherRedirect.body) as { error: string }).error, 'invalid_grant');

  const c = await approvedCode(relay, device);
  const otherResource = await exchange(relay, {
    code: c.code,
    client_id: c.clientId,
    code_verifier: c.pkce.verifier,
    resource: `${relay.url}/d/${'a'.repeat(26)}/mcp`,
  });
  assert.equal((JSON.parse(otherResource.body) as { error: string }).error, 'invalid_target');

  // `{relay}/d/<id>` is an accepted resource spelling.
  const d = await approvedCode(relay, device);
  const ok = await exchange(relay, {
    code: d.code,
    client_id: d.clientId,
    code_verifier: d.pkce.verifier,
    resource: `${relay.url}/d/${device.identity.deviceId}`,
  });
  assert.equal(ok.status, 200);
});

test('token endpoint errors follow RFC 6749', async (t) => {
  const relay = await startRelay(t);
  const clientId = await registerClient(relay);
  const url = `${relay.url}/oauth/token`;
  const error = async (res: Promise<{ status: number; body: string }>): Promise<string> => {
    const r = await res;
    assert.equal(r.status, 400, r.body);
    const parsed = JSON.parse(r.body) as { error: string; error_description: string };
    assert.equal(typeof parsed.error_description, 'string');
    return parsed.error;
  };
  const verifier = newPkce().verifier;
  assert.equal(await error(postForm(url, { grant_type: 'refresh_token', refresh_token: 'x' })), 'unsupported_grant_type');
  assert.equal(await error(postForm(url, { code: 'x' })), 'invalid_request');
  assert.equal(await error(postForm(url, { grant_type: 'authorization_code', client_id: clientId })), 'invalid_request');
  assert.equal(
    await error(postForm(url, { grant_type: 'authorization_code', client_id: `${clientId}x`, code: 'c', code_verifier: verifier })),
    'invalid_client',
  );
  assert.equal(
    await error(postForm(url, { grant_type: 'authorization_code', client_id: clientId, code: 'nope', code_verifier: verifier })),
    'invalid_grant',
  );
  assert.equal(
    await error(postJson(url, { grant_type: 'authorization_code', client_id: clientId, code: 'c', code_verifier: verifier })),
    'invalid_request',
  );
  const twice = `grant_type=authorization_code&code=a&code=b&client_id=${encodeURIComponent(clientId)}&code_verifier=${verifier}`;
  assert.equal(
    await error(httpRequest(url, { method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded' }, body: twice })),
    'invalid_request',
  );
});

test('client_id via HTTP Basic is accepted at the token endpoint', async (t) => {
  const relay = await startRelay(t);
  const device = await connectReady(relay);
  const { clientId, pkce, code } = await approvedCode(relay, device);
  const basic = Buffer.from(`${encodeURIComponent(clientId)}:`).toString('base64');
  const res = await postForm(
    `${relay.url}/oauth/token`,
    { grant_type: 'authorization_code', code, redirect_uri: REDIRECT_URI, code_verifier: pkce.verifier },
    { authorization: `Basic ${basic}` },
  );
  assert.equal(res.status, 200, res.body);
});

test('authorize: invalid client_id, unregistered redirect_uri and missing PKCE show an error page, never redirect', async (t) => {
  const relay = await startRelay(t);
  const device = await connectReady(relay);
  const clientId = await registerClient(relay);
  const pkce = newPkce();
  const deviceId = device.identity.deviceId;
  const [payload, mac] = clientId.split('.') as [string, string];
  const forged = Buffer.from(
    JSON.stringify({ ...JSON.parse(Buffer.from(payload, 'base64url').toString()), r: ['https://evil.example/cb'] }),
  ).toString('base64url');

  const cases: Array<[string, string]> = [
    ['tampered client_id', authorizeUrl(relay, { clientId: `${forged}.${mac}`, pkce, deviceId, redirectUri: 'https://evil.example/cb' })],
    ['garbage client_id', authorizeUrl(relay, { clientId: 'nope', pkce, deviceId })],
    ['unregistered redirect', authorizeUrl(relay, { clientId, pkce, deviceId, redirectUri: 'http://localhost:1/other' })],
    ['plain PKCE', authorizeUrl(relay, { clientId, pkce, deviceId, extra: { code_challenge_method: 'plain' } })],
    ['bad challenge', authorizeUrl(relay, { clientId, pkce, deviceId, extra: { code_challenge: 'short' } })],
  ];
  for (const [name, url] of cases) {
    const res = await httpRequest(url);
    assert.equal(res.status, 400, name);
    assert.equal(res.headers.location, undefined, `${name}: no redirect`);
    assert.match(res.headers['content-type'] ?? '', /^text\/html/, name);
    assert.match(res.body, /Can&#39;t connect to your phone/, name);
  }
  const missingPkce = new URL(authorizeUrl(relay, { clientId, pkce, deviceId }));
  missingPkce.searchParams.delete('code_challenge');
  assert.equal((await httpRequest(missingPkce.toString())).status, 400);
  await device.sync();
  assert.equal(device.bufferedFrames('pair_request'), 0, 'the phone was never prompted');
});

test('authorize: an unsupported response_type shows an error page and never redirects (no open redirect)', async (t) => {
  const relay = await startRelay(t);
  const device = await connectReady(relay);
  const ownClient = authorizeUrl(relay, {
    clientId: await registerClient(relay),
    pkce: newPkce(),
    deviceId: device.identity.deviceId,
    extra: { response_type: 'token' },
  });
  // Anyone can register any https redirect URI, then send the victim a link on the relay's domain.
  const landing = 'https://evil.example/landing';
  const evilClient = await registerClient(relay, { client_name: 'DroidHands setup', redirect_uris: [landing] });
  const bare = `${relay.url}/oauth/authorize?client_id=${encodeURIComponent(evilClient)}&response_type=x`;

  for (const url of [ownClient, bare]) {
    const res = await httpRequest(url);
    assert.equal(res.status, 400, url);
    assert.equal(res.headers.location, undefined, 'no redirect');
    assert.match(res.headers['content-type'] ?? '', /^text\/html/);
    assert.match(res.body, /only response_type=code is supported/);
    assert.doesNotMatch(res.body, /evil\.example/);
  }
  await device.sync();
  assert.equal(device.bufferedFrames('pair_request'), 0, 'the phone was never prompted');
});

test('authorize: redirect_uri may be omitted when exactly one is registered', async (t) => {
  const relay = await startRelay(t);
  const device = await connectReady(relay);
  const clientId = await registerClient(relay);
  const pkce = newPkce();
  const page = await openAuthorize(relay, { clientId, pkce, deviceId: device.identity.deviceId, redirectUri: null });
  await approveNext(device);
  const code = redirectParams((await pollUntilDecided(page.pollUrl)).redirect as string).get('code') as string;
  const res = await postForm(`${relay.url}/oauth/token`, {
    grant_type: 'authorization_code',
    code,
    client_id: clientId,
    code_verifier: pkce.verifier,
  });
  assert.equal(res.status, 200, res.body);
});

test('device selection: without resource the request is refused, whether or not a phone is online', async (t) => {
  const relay = await startRelay(t);
  const clientId = await registerClient(relay);
  const assertRefused = async (what: string): Promise<void> => {
    const res = await httpRequest(authorizeUrl(relay, { clientId, pkce: newPkce(), deviceId: null }));
    assert.equal(res.status, 400, what);
    assert.equal(res.headers.location, undefined, what);
    assert.match(res.body, /didn&#39;t say which phone to connect to/, what);
    assert.match(res.body, /update your AI app/, what);
    assert.doesNotMatch(res.body, /Pixel 8|offline/, `${what}: says nothing about the phone`);
  };

  await assertRefused('no phone online');
  const only = await connectReady(relay, { hello: { deviceName: 'Pixel 8' } });
  await assertRefused('the only phone online');
  await only.sync();
  assert.equal(only.bufferedFrames('pair_request'), 0, 'the phone was never prompted');

  const foreign = await httpRequest(
    authorizeUrl(relay, { clientId, pkce: newPkce(), deviceId: null, extra: { resource: `https://other.example/d/${only.identity.deviceId}/mcp` } }),
  );
  assert.equal(foreign.status, 400);
  assert.match(foreign.body, /does not belong to this relay/);
});

test('device selection with ALLOW_IMPLICIT_DEVICE: the only online phone, unnamed on the page; zero or two devices', async (t) => {
  const relay = await startRelay(t, { allowImplicitDevice: true });
  const clientId = await registerClient(relay);
  const pkce = newPkce();

  const none = await httpRequest(authorizeUrl(relay, { clientId, pkce, deviceId: null }));
  assert.equal(none.status, 503);
  assert.match(none.body, /Your phone is offline/);

  const first = await connectReady(relay, { hello: { deviceName: 'Pixel 8' } });
  const single = await openAuthorize(relay, { clientId, pkce, deviceId: null });
  assert.equal((await first.nextFrame<PairRequestFrame>('pair_request')).displayCode, single.displayCode);
  assert.doesNotMatch(single.res.body, /Pixel 8/, 'the phone is not named to someone who did not name it');
  assert.match(single.res.body, /wants to use <strong>your phone<\/strong>/);
  assert.doesNotMatch((await httpRequest(single.pageUrl)).body, /Pixel 8/, 'nor on the reloadable page');

  const named = await openAuthorize(relay, { clientId, pkce: newPkce(), deviceId: first.identity.deviceId });
  assert.match(named.res.body, /Pixel 8/, 'named by its connection URL');

  await connectReady(relay);
  const several = await httpRequest(authorizeUrl(relay, { clientId, pkce: newPkce(), deviceId: null }));
  assert.equal(several.status, 400);
  assert.match(several.body, /Several phones/);
});

test('loopback redirect URIs: any port at authorization time, the exact one at the token endpoint (RFC 8252 §7.3)', async (t) => {
  const relay = await startRelay(t);
  const device = await connectReady(relay);
  const { deviceId } = device.identity;
  // A port-less registration (VS Code) and a cached one whose port is now busy (mcp-remote).
  const clientId = await registerClient(relay, {
    client_name: 'Native',
    redirect_uris: ['http://127.0.0.1/', 'http://localhost:3334/oauth/callback'],
  });

  for (const redirectUri of ['http://127.0.0.1:51234/', 'http://localhost:3335/oauth/callback']) {
    const pkce = newPkce();
    const page = await openAuthorize(relay, { clientId, pkce, deviceId, redirectUri });
    const { request } = await approveNext(device);
    assert.equal(request.redirectHost, new URL(redirectUri).host, 'the phone shows the port actually used');
    const redirect = new URL((await pollUntilDecided(page.pollUrl)).redirect as string);
    assert.equal(`${redirect.origin}${redirect.pathname}`, redirectUri, 'the code goes to the requested port');
    const code = redirect.searchParams.get('code') as string;

    const registeredPort = await exchange(relay, {
      code,
      client_id: clientId,
      code_verifier: pkce.verifier,
      redirect_uri: redirectUri.replace(/:\d+\//, '/'),
    });
    assert.equal((JSON.parse(registeredPort.body) as { error: string }).error, 'invalid_grant', 'token: exact redirect_uri');
  }

  const pkce = newPkce();
  const page = await openAuthorize(relay, { clientId, pkce, deviceId, redirectUri: 'http://127.0.0.1:40000/' });
  await approveNext(device);
  const code = new URL((await pollUntilDecided(page.pollUrl)).redirect as string).searchParams.get('code') as string;
  const ok = await exchange(relay, { code, client_id: clientId, code_verifier: pkce.verifier, redirect_uri: 'http://127.0.0.1:40000/' });
  assert.equal(ok.status, 200, ok.body);

  for (const redirectUri of ['http://127.0.0.1:51234/other', 'http://localhost:51234/', 'http://[::1]:3334/oauth/callback']) {
    const res = await httpRequest(authorizeUrl(relay, { clientId, pkce: newPkce(), deviceId, redirectUri }));
    assert.equal(res.status, 400, redirectUri);
    assert.match(res.body, /did not register/, redirectUri);
  }
});

test('device offline: the authorize page says so and nothing is created', async (t) => {
  const relay = await startRelay(t);
  const device = await connectReady(relay);
  const { deviceId } = device.identity;
  device.ws.close();
  await waitFor(() => relay.deviceCount() === 0, 'device removal');
  const res = await httpRequest(authorizeUrl(relay, { clientId: await registerClient(relay), pkce: newPkce(), deviceId }));
  assert.equal(res.status, 503);
  assert.match(res.body, /Your phone is offline\. Open DroidHands, make sure it shows Connected, then reload this page\./);
});

test('a device that disconnects while a pairing is pending ends it as expired', async (t) => {
  const relay = await startRelay(t);
  const device = await connectReady(relay);
  const page = await openAuthorize(relay, { clientId: await registerClient(relay), pkce: newPkce(), deviceId: device.identity.deviceId });
  await device.nextFrame('pair_request');
  device.ws.terminate();
  await waitFor(() => relay.deviceCount() === 0, 'device removal');
  const status = await pollStatus(page.pollUrl);
  assert.equal(status.status, 'expired');
  assert.equal(redirectParams(status.redirect as string).get('error'), 'access_denied');
});

test('a pending pairing is re-sent when the phone reconnects before its old socket closed', async (t) => {
  const relay = await startRelay(t);
  const stale = await connectReady(relay);
  const page = await openAuthorize(relay, { clientId: await registerClient(relay), pkce: newPkce(), deviceId: stale.identity.deviceId });
  // The relay wrote the prompt into a socket the phone no longer reads.
  const lost = await stale.nextFrame<PairRequestFrame>('pair_request');

  const fresh = await connectReady(relay, { identity: stale.identity });
  assert.deepEqual(await fresh.nextFrame<PairRequestFrame>('pair_request'), lost, 'same id, code and expiry');
  assert.equal((await stale.closed).code, 4009);
  await fresh.sync();
  assert.equal((await pollStatus(page.pollUrl)).status, 'pending', "the old socket's close did not expire it");

  const token = tokenFor(stale.identity.deviceId);
  fresh.send({ type: 'pair_response', id: lost.id, approved: true, accessToken: token, tokenId: token.split('.')[2] });
  assert.equal((await pollUntilDecided(page.pollUrl)).status, 'approved');
});

test('pair_complete is delivered when the phone reconnects after an exchange while offline', async (t) => {
  const relay = await startRelay(t);
  const device = await connectReady(relay);
  const { clientId, pkce, code, approval, pairingId } = await approvedCode(relay, device);
  device.ws.terminate();
  await waitFor(() => relay.deviceCount() === 0, 'device removal');
  assert.equal((await exchange(relay, { code, client_id: clientId, code_verifier: pkce.verifier })).status, 200);
  const again = await connectReady(relay, { identity: device.identity });
  assert.deepEqual(await again.nextFrame('pair_complete'), { type: 'pair_complete', id: pairingId, tokenId: approval.tokenId });
});

test('limits: 3 pending pairings per device, then an error page', async (t) => {
  const relay = await startRelay(t);
  const device = await connectReady(relay);
  const clientId = await registerClient(relay);
  for (let i = 0; i < 3; i++) await openAuthorize(relay, { clientId, pkce: newPkce(), deviceId: device.identity.deviceId });
  const fourth = await httpRequest(authorizeUrl(relay, { clientId, pkce: newPkce(), deviceId: device.identity.deviceId }));
  assert.equal(fourth.status, 429);
  assert.match(fourth.body, /several connection requests waiting/);
});

test('limits: 20 pairings per device per hour', async (t) => {
  const relay = await startRelay(t);
  const device = await connectReady(relay);
  const clientId = await registerClient(relay);
  for (let i = 0; i < 20; i++) {
    const page = await openAuthorize(relay, { clientId, pkce: newPkce(), deviceId: device.identity.deviceId });
    await denyNext(device);
    assert.equal((await pollUntilDecided(page.pollUrl)).status, 'denied');
  }
  const res = await httpRequest(authorizeUrl(relay, { clientId, pkce: newPkce(), deviceId: device.identity.deviceId }));
  assert.equal(res.status, 429);
  assert.match(res.body, /too many connection requests in the last hour/);
});

test('reloading the same authorization request reuses the pending pairing', async (t) => {
  const relay = await startRelay(t);
  const device = await connectReady(relay);
  const params = { clientId: await registerClient(relay), pkce: newPkce(), deviceId: device.identity.deviceId };
  const first = await openAuthorize(relay, params);
  const second = await openAuthorize(relay, params);
  assert.equal(second.pollUrl, first.pollUrl);
  await device.sync();
  assert.equal(device.bufferedFrames('pair_request'), 1, 'the phone is prompted once');
});

test('pairing status: unknown id or wrong page token -> 404; the HTML view renders while pending', async (t) => {
  const relay = await startRelay(t);
  const device = await connectReady(relay);
  const page = await openAuthorize(relay, { clientId: await registerClient(relay), pkce: newPkce(), deviceId: device.identity.deviceId });
  const wrongToken = page.pollUrl.replace(/t=[^&]+/, 't=AAAAAAAAAAAAAAAAAAAAAA');
  assert.equal((await httpRequest(wrongToken)).status, 404);
  assert.equal((await httpRequest(`${relay.url}/oauth/pair/p_nope?t=x`)).status, 404);
  const gone = await httpRequest(`${relay.url}/oauth/pair/p_nope?t=x&view=html`);
  assert.equal(gone.status, 404);
  assert.match(gone.body, /no longer valid/);

  const view = await httpRequest(page.pageUrl);
  assert.equal(view.status, 200);
  assert.equal(parseWaitingPage(view, relay.url).displayCode, page.displayCode);
  assert.equal((await pollStatus(page.pollUrl)).status, 'pending');
});

test('an approval with a token for another device is rejected and the token abandoned', async (t) => {
  const relay = await startRelay(t);
  const device = await connectReady(relay);
  const other = await connectReady(relay);
  const page = await openAuthorize(relay, { clientId: await registerClient(relay), pkce: newPkce(), deviceId: device.identity.deviceId });
  const request = await device.nextFrame<PairRequestFrame>('pair_request');
  const foreign = tokenFor(other.identity.deviceId);
  const tokenId = foreign.split('.')[2] as string;
  device.send({ type: 'pair_response', id: request.id, approved: true, accessToken: foreign, tokenId });
  assert.equal((await pollUntilDecided(page.pollUrl)).status, 'denied');
  assert.deepEqual(await device.nextFrame<Frame>('pair_abandoned'), { type: 'pair_abandoned', id: request.id, tokenId });
});

test('relay shutdown cancels pending prompts and abandons unexchanged tokens', async (t) => {
  const relay = await startRelay(t);
  const device = await connectReady(relay);
  const { approval, pairingId } = await approvedCode(relay, device);
  await openAuthorize(relay, { clientId: await registerClient(relay), pkce: newPkce(), deviceId: device.identity.deviceId });
  const pending = await device.nextFrame<PairRequestFrame>('pair_request');
  await relay.close();
  assert.deepEqual(await device.nextFrame('pair_cancel'), { type: 'pair_cancel', id: pending.id });
  assert.deepEqual(await device.nextFrame('pair_abandoned'), { type: 'pair_abandoned', id: pairingId, tokenId: approval.tokenId });
});
