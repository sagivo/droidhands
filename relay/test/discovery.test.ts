import assert from 'node:assert/strict';
import { test } from 'node:test';
import { httpRequest, newIdentity, startRelay, testRelaySecret } from './helpers.js';
import { REDIRECT_URI, authorizeUrl, newPkce, postJson, registerClient } from './oauthHelpers.js';

test('protected resource metadata (RFC 9728) for any well-formed device id', async (t) => {
  const relay = await startRelay(t, { publicUrl: 'https://relay.example.com' });
  const { deviceId } = newIdentity();
  const res = await httpRequest(`${relay.url}/.well-known/oauth-protected-resource/d/${deviceId}/mcp`);
  assert.equal(res.status, 200);
  assert.equal(res.headers['content-type'], 'application/json');
  assert.equal(res.headers['access-control-allow-origin'], '*');
  assert.deepEqual(JSON.parse(res.body), {
    resource: `https://relay.example.com/d/${deviceId}/mcp`,
    authorization_servers: ['https://relay.example.com'],
    bearer_methods_supported: ['header'],
    resource_name: 'PhoneBridge',
    scopes_supported: ['phone'],
  });
  for (const path of ['/.well-known/oauth-protected-resource', '/.well-known/oauth-protected-resource/d/BAD/mcp']) {
    assert.equal((await httpRequest(`${relay.url}${path}`)).status, 404, path);
  }
});

test('authorization server metadata (RFC 8414) and the openid-configuration alias', async (t) => {
  const relay = await startRelay(t);
  const expected = {
    issuer: relay.url,
    authorization_endpoint: `${relay.url}/oauth/authorize`,
    token_endpoint: `${relay.url}/oauth/token`,
    registration_endpoint: `${relay.url}/oauth/register`,
    response_types_supported: ['code'],
    response_modes_supported: ['query'],
    grant_types_supported: ['authorization_code'],
    code_challenge_methods_supported: ['S256'],
    token_endpoint_auth_methods_supported: ['none'],
    scopes_supported: ['phone'],
    authorization_response_iss_parameter_supported: true,
  };
  for (const path of ['/.well-known/oauth-authorization-server', '/.well-known/openid-configuration']) {
    const res = await httpRequest(`${relay.url}${path}`);
    assert.equal(res.status, 200, path);
    assert.equal(res.headers['access-control-allow-origin'], '*');
    assert.deepEqual(JSON.parse(res.body), expected);
  }
});

test('a PUBLIC_URL path prefix is reflected in the metadata and accepted in well-known paths', async (t) => {
  const relay = await startRelay(t, { publicUrl: 'https://example.com/relay' });
  const { deviceId } = newIdentity();
  for (const path of ['/.well-known/oauth-authorization-server/relay', '/.well-known/oauth-authorization-server']) {
    const res = await httpRequest(`${relay.url}${path}`);
    assert.equal(res.status, 200, path);
    assert.equal((JSON.parse(res.body) as { issuer: string }).issuer, 'https://example.com/relay');
  }
  const prm = await httpRequest(`${relay.url}/.well-known/oauth-protected-resource/relay/d/${deviceId}/mcp`);
  assert.equal((JSON.parse(prm.body) as { resource: string }).resource, `https://example.com/relay/d/${deviceId}/mcp`);
});

test('CORS preflight on discovery, registration and token endpoints', async (t) => {
  const relay = await startRelay(t, { allowedOrigins: [] });
  const paths = [
    `/.well-known/oauth-protected-resource/d/${newIdentity().deviceId}/mcp`,
    '/.well-known/oauth-authorization-server',
    '/oauth/register',
    '/oauth/token',
  ];
  for (const path of paths) {
    const res = await httpRequest(`${relay.url}${path}`, {
      method: 'OPTIONS',
      headers: {
        origin: 'https://inspector.example',
        'access-control-request-method': 'POST',
        'access-control-request-headers': 'content-type',
      },
    });
    assert.equal(res.status, 204, path);
    assert.equal(res.headers['access-control-allow-origin'], '*');
    assert.match(res.headers['access-control-allow-headers'] ?? '', /Content-Type/);
    assert.match(res.headers['access-control-allow-methods'] ?? '', /OPTIONS/);
    assert.equal(res.headers['access-control-allow-credentials'], undefined);
  }
  // A cross-origin registration works although ALLOWED_ORIGINS is empty.
  const res = await postJson(`${relay.url}/oauth/register`, { redirect_uris: [REDIRECT_URI] }, { origin: 'https://inspector.example' });
  assert.equal(res.status, 201);
  assert.equal(res.headers['access-control-allow-origin'], '*');
});

test('registration: response shape and substituted metadata', async (t) => {
  const relay = await startRelay(t);
  const before = Math.floor(Date.now() / 1000);
  const res = await postJson(`${relay.url}/oauth/register`, {
    client_name: '  Claude‮\u0000 Code  ',
    redirect_uris: [REDIRECT_URI, 'https://claude.ai/api/mcp/auth_callback'],
    token_endpoint_auth_method: 'client_secret_basic',
    grant_types: ['authorization_code', 'refresh_token'],
  });
  assert.equal(res.status, 201, res.body);
  const body = JSON.parse(res.body) as Record<string, unknown>;
  assert.match(body.client_id as string, /^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]{43}$/);
  assert.equal(body.client_name, 'Claude Code');
  assert.deepEqual(body.redirect_uris, [REDIRECT_URI, 'https://claude.ai/api/mcp/auth_callback']);
  assert.equal(body.token_endpoint_auth_method, 'none');
  assert.deepEqual(body.grant_types, ['authorization_code']);
  assert.deepEqual(body.response_types, ['code']);
  assert.ok((body.client_id_issued_at as number) >= before);
  assert.equal(body.client_secret, undefined);
});

test('registration: redirect URI rules', async (t) => {
  const relay = await startRelay(t);
  const accepted = [
    'https://claude.ai/api/mcp/auth_callback',
    'https://chatgpt.com/connector_platform_oauth_redirect?x=1',
    'http://localhost:33418/callback',
    'http://127.0.0.1:6274/oauth/callback',
    'http://[::1]:8080/cb',
    'com.example.app:/oauth2redirect',
    'cursor.app://anysphere.cursor-retrieval/oauth/callback',
  ];
  for (const uri of accepted) {
    assert.equal((await postJson(`${relay.url}/oauth/register`, { redirect_uris: [uri] })).status, 201, uri);
  }
  const rejected = [
    'http://example.com/cb',
    'http://localhost/cb',
    'http://192.168.1.2:8080/cb',
    'https://claude.ai/cb#fragment',
    'https://user:pass@claude.ai/cb',
    'javascript:alert(1)',
    'data:text/html,hi',
    'myapp:/cb',
    'intent://x#Intent;end',
    'file:///etc/passwd',
    '/relative',
    `https://claude.ai/${'x'.repeat(600)}`,
  ];
  for (const uri of rejected) {
    const res = await postJson(`${relay.url}/oauth/register`, { redirect_uris: [uri] });
    assert.equal(res.status, 400, uri);
    const body = JSON.parse(res.body) as { error: string; error_description: string };
    assert.equal(body.error, 'invalid_redirect_uri', uri);
    assert.ok(body.error_description.length > 0);
  }
});

test('registration: invalid metadata', async (t) => {
  const relay = await startRelay(t);
  const url = `${relay.url}/oauth/register`;
  const cases: Array<[unknown, string]> = [
    [{}, 'invalid_redirect_uri'],
    [{ redirect_uris: [] }, 'invalid_redirect_uri'],
    [{ redirect_uris: 'https://a.example/cb' }, 'invalid_redirect_uri'],
    [{ redirect_uris: Array.from({ length: 6 }, (_, i) => `https://a.example/${i}`) }, 'invalid_redirect_uri'],
    [{ redirect_uris: [REDIRECT_URI], client_name: 5 }, 'invalid_client_metadata'],
    [{ redirect_uris: [REDIRECT_URI], grant_types: ['client_credentials'] }, 'invalid_client_metadata'],
    [{ redirect_uris: [REDIRECT_URI], response_types: ['token'] }, 'invalid_client_metadata'],
    [[], 'invalid_client_metadata'],
  ];
  for (const [metadata, error] of cases) {
    const res = await postJson(url, metadata);
    assert.equal(res.status, 400, JSON.stringify(metadata));
    assert.equal((JSON.parse(res.body) as { error: string }).error, error, JSON.stringify(metadata));
  }
  const notJson = await httpRequest(url, { method: 'POST', headers: { 'content-type': 'application/json' }, body: '{' });
  assert.equal(notJson.status, 400);
  const big = await postJson(url, { redirect_uris: [REDIRECT_URI], client_name: 'x'.repeat(20_000) });
  assert.equal(big.status, 413);
  assert.equal((await httpRequest(url)).status, 405);
});

test('client ids are stateless: valid across relays sharing RELAY_SECRET, invalid with another secret', async (t) => {
  const secret = testRelaySecret();
  const a = await startRelay(t, { relaySecret: secret });
  const b = await startRelay(t, { relaySecret: secret });
  const c = await startRelay(t);
  const clientId = await registerClient(a);
  const page = (relayUrl: typeof a) => httpRequest(authorizeUrl(relayUrl, { clientId, pkce: newPkce(), deviceId: newIdentity().deviceId }));
  // A valid client reaches device selection (offline page); an unknown one stops at the client check.
  assert.equal((await page(b)).status, 503);
  const rejected = await page(c);
  assert.equal(rejected.status, 400);
  assert.match(rejected.body, /registration is not valid for this relay/);
});
