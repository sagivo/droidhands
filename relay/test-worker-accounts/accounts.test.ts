import { env as testEnv } from 'cloudflare:test';
import { SignJWT, importJWK, type JWK } from 'jose';
import { describe, expect, it } from 'vitest';
import { RELAY_TOKEN_AUDIENCE } from '../src/account.js';
import { openSocket } from '../test-worker/helpers.js';

const ISSUER = 'https://api.test';
const jwk = JSON.parse((testEnv as unknown as { TEST_SIGNING_JWK: string }).TEST_SIGNING_JWK) as JWK;

async function relayToken(claims: { iss?: string; exp?: number | string } = {}): Promise<string> {
  const key = await importJWK(jwk, 'EdDSA');
  return new SignJWT({})
    .setProtectedHeader({ alg: 'EdDSA', kid: jwk.kid })
    .setSubject('person-1')
    .setIssuer(claims.iss ?? ISSUER)
    .setAudience(RELAY_TOKEN_AUDIENCE)
    .setIssuedAt()
    .setExpirationTime(claims.exp ?? '10m')
    .sign(key);
}

describe('Worker relay with ACCOUNTS_URL', () => {
  it('welcomes a phone with a relay token from the API Worker', async () => {
    const socket = await openSocket();
    socket.hello({ accountToken: await relayToken() });
    const welcome = await socket.next<{ deviceId: string }>('welcome');
    expect(welcome.deviceId).toBe(socket.identity.deviceId);
  });

  it('refuses a phone without one: account_required, 4001', async () => {
    const socket = await openSocket();
    socket.hello();
    const error = await socket.next<{ code: string; message: string }>('error');
    expect(error.code).toBe('account_required');
    expect(error.message).toMatch(/Sign in/);
    expect((await socket.closed).code).toBe(4001);
  });

  it('refuses tokens of another issuer, or expired ones', async () => {
    for (const token of [await relayToken({ iss: 'https://evil.test' }), await relayToken({ exp: Math.floor(Date.now() / 1000) - 300 })]) {
      const socket = await openSocket();
      socket.hello({ accountToken: token });
      expect((await socket.next<{ code: string }>('error')).code).toBe('account_required');
    }
  });

  it('ignores frames sent while the token is checked', async () => {
    const socket = await openSocket();
    socket.hello({ accountToken: await relayToken() });
    socket.send({ type: 'hello', protocolVersion: 1, deviceId: 'x', deviceSecret: 'y' });
    await socket.next('welcome');
    socket.send('{"type":"ping","t":7}');
    expect(await socket.next<{ t: number }>('pong')).toMatchObject({ t: 7 });
  });
});
