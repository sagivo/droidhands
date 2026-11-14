import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { test } from 'node:test';
import {
  DEFAULT_CLIENT_NAME,
  decodeClientId,
  encodeClientId,
  registerClient,
  sanitizeClientName,
} from '../src/oauth/clientRegistration.js';
import { connectorChallenge, deviceIdFromMetadataPath, deviceIdFromResource } from '../src/oauth/metadata.js';
import { parsePairResponse } from '../src/oauth/pairingFrames.js';
import { s256Challenge, verifyPkce } from '../src/oauth/pkce.js';
import { buildRedirect, classifyRedirectUri, redirectHost } from '../src/oauth/redirectUris.js';
import { newIdentity, tokenFor } from './helpers.js';

const KEY = randomBytes(32);

test('PKCE S256 matches the RFC 7636 appendix B vector', () => {
  const verifier = 'dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk';
  assert.equal(s256Challenge(verifier), 'E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM');
  assert.ok(verifyPkce(verifier, 'E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM'));
  assert.equal(verifyPkce(`${verifier}x`, 'E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM'), false);
  assert.equal(verifyPkce('short', s256Challenge('short')), false, 'verifiers under 43 chars are refused');
  assert.equal(verifyPkce(`${verifier}!`, s256Challenge(`${verifier}!`)), false, 'only unreserved characters');
});

test('redirect URI classification and display host', () => {
  assert.equal(classifyRedirectUri('https://claude.ai/api/mcp/auth_callback').kind, 'https');
  assert.equal(classifyRedirectUri('http://localhost:1234/cb').kind, 'loopback');
  assert.equal(classifyRedirectUri('com.example.app:/cb').kind, 'private_use');
  assert.equal(classifyRedirectUri('http://localhost.evil.com:1/cb').kind, null);
  assert.equal(classifyRedirectUri('https://a.example/\ncb').kind, null);
  assert.equal(redirectHost('https://Claude.AI:8443/cb'), 'claude.ai');
  assert.equal(redirectHost('http://localhost:33418/callback'), 'localhost:33418');
  assert.equal(redirectHost('http://[::1]:8080/cb'), '[::1]:8080');
  assert.equal(redirectHost('com.example.app:/cb'), 'com.example.app');
});

test('buildRedirect keeps the existing query and encodes values', () => {
  assert.equal(
    buildRedirect('https://a.example/cb?x=1', { code: 'a b&c', state: undefined, iss: 'https://r.example' }),
    'https://a.example/cb?x=1&code=a+b%26c&iss=https%3A%2F%2Fr.example',
  );
  assert.equal(buildRedirect('com.example.app:/cb', { code: 'c' }), 'com.example.app:/cb?code=c');
});

test('client ids round-trip and any tampering is rejected', () => {
  const result = registerClient({ client_name: 'Claude', redirect_uris: ['https://claude.ai/cb'] }, KEY, 1_700_000_000_000);
  assert.ok(result.ok);
  const clientId = result.response.client_id;
  assert.deepEqual(decodeClientId(clientId, KEY), {
    clientName: 'Claude',
    redirectUris: ['https://claude.ai/cb'],
    issuedAt: 1_700_000_000,
  });
  assert.equal(decodeClientId(clientId, randomBytes(32)), null, 'another secret');
  const [payload, mac] = clientId.split('.') as [string, string];
  const flipped = `${payload.slice(0, -2)}${payload.at(-2) === 'A' ? 'B' : 'A'}${payload.at(-1)}`;
  for (const bad of [
    `${flipped}.${mac}`,
    `${payload}.${mac.slice(0, -1)}`,
    `${payload}.${mac}=`,
    `${payload}.${mac}.x`,
    payload,
    '',
    'a'.repeat(5000),
  ]) {
    assert.equal(decodeClientId(bad, KEY), null, bad.slice(0, 40));
  }
  // A correctly signed payload whose URIs are no longer acceptable is refused too.
  const signedBad = encodeClientId({ clientName: 'x', redirectUris: ['javascript:alert(1)'], issuedAt: 1 }, KEY);
  assert.equal(decodeClientId(signedBad, KEY), null);
});

test('client names: control/bidi characters stripped, whitespace collapsed, default when empty', () => {
  assert.equal(sanitizeClientName('  Claude ‮edoC\u0007\n Desktop '), 'Claude edoC Desktop');
  assert.equal(sanitizeClientName('​\u0000 '), DEFAULT_CLIENT_NAME);
  assert.equal(sanitizeClientName(undefined), DEFAULT_CLIENT_NAME);
  assert.equal(sanitizeClientName('x'.repeat(100)), 'x'.repeat(60));
});

test('resource values map to device ids only for this relay', () => {
  const id = newIdentity().deviceId;
  const base = 'https://relay.example.com/base';
  for (const ok of [`${base}/d/${id}/mcp`, `${base}/d/${id}`, `${base}/d/${id}/`, `HTTPS://RELAY.EXAMPLE.COM:443/base/d/${id}/mcp`]) {
    assert.equal(deviceIdFromResource(ok, base), id, ok);
  }
  for (const bad of [
    `https://other.example/base/d/${id}/mcp`,
    `${base}/d/${id}/mcp?x=1`,
    `${base}/d/${id}/mcp#f`,
    `${base}/d/${id}/other`,
    `${base}/d/${id.toUpperCase()}/mcp`,
    `https://relay.example.com/d/${id}/mcp`,
    'not a url',
  ]) {
    assert.equal(deviceIdFromResource(bad, base), null, bad);
  }
});

test('resource metadata paths and the connector challenge', () => {
  const id = newIdentity().deviceId;
  assert.equal(deviceIdFromMetadataPath(`/.well-known/oauth-protected-resource/d/${id}/mcp`, ''), id);
  assert.equal(deviceIdFromMetadataPath(`/.well-known/oauth-protected-resource/p/d/${id}/mcp`, '/p'), id);
  assert.equal(deviceIdFromMetadataPath(`/.well-known/oauth-protected-resource/p/d/${id}/mcp`, ''), null);
  assert.equal(deviceIdFromMetadataPath('/.well-known/oauth-protected-resource', ''), null);
  assert.equal(
    connectorChallenge('https://r.example', id),
    `Bearer realm="phonebridge", resource_metadata="https://r.example/.well-known/oauth-protected-resource/d/${id}/mcp"`,
  );
});

test('pair_response parsing', () => {
  const { deviceId } = newIdentity();
  const token = tokenFor(deviceId);
  const tokenId = token.split('.')[2] as string;
  assert.deepEqual(parsePairResponse({ id: 'p_1', approved: true, accessToken: token, tokenId }, deviceId), {
    kind: 'approved',
    id: 'p_1',
    accessToken: token,
    tokenId,
  });
  assert.deepEqual(parsePairResponse({ id: 'p_1', approved: false, reason: 'busy' }, deviceId), { kind: 'denied', id: 'p_1', reason: 'busy' });
  assert.deepEqual(parsePairResponse({ id: 'p_1', approved: false, reason: 'weird' }, deviceId), {
    kind: 'denied',
    id: 'p_1',
    reason: 'denied',
  });
  assert.deepEqual(parsePairResponse({ id: 'p_1', approved: 'yes' }, deviceId), { kind: 'denied', id: 'p_1', reason: 'denied' });
  assert.deepEqual(parsePairResponse({ id: 'p_1', approved: true, accessToken: token, tokenId: 'aaaaaaaaaaaa' }, deviceId), {
    kind: 'invalid',
    id: 'p_1',
    tokenId: 'aaaaaaaaaaaa',
  });
  assert.deepEqual(parsePairResponse({ id: 'p_1', approved: true, accessToken: 'nope' }, deviceId), {
    kind: 'invalid',
    id: 'p_1',
    tokenId: null,
  });
  assert.equal(parsePairResponse({ approved: true }, deviceId), null);
  assert.equal(parsePairResponse({ id: 'x'.repeat(65), approved: false }, deviceId), null);
});
