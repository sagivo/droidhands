import assert from 'node:assert/strict';
import { test } from 'node:test';
import { SignJWT, exportJWK, generateKeyPair, type JWK } from 'jose';
import { AccountVerifier, RELAY_TOKEN_AUDIENCE } from '../src/account.js';
import { connectDevice, startRelay } from './helpers.js';

const ISSUER = 'https://api.example.com';

/** An API server's signing key, as Better Auth's jwt plugin makes them (EdDSA, Ed25519). */
async function signingKey(kid: string) {
  const { privateKey, publicKey } = await generateKeyPair('EdDSA', { crv: 'Ed25519', extractable: true });
  const jwk: JWK = { ...(await exportJWK(publicKey)), kid, alg: 'EdDSA' };
  const sign = (claims: { sub?: string; iss?: string; aud?: string; exp?: number | string } = {}) => {
    const jwt = new SignJWT({}).setProtectedHeader({ alg: 'EdDSA', kid }).setIssuedAt();
    if (claims.sub !== '') jwt.setSubject(claims.sub ?? 'user-1');
    return jwt
      .setIssuer(claims.iss ?? ISSUER)
      .setAudience(claims.aud ?? RELAY_TOKEN_AUDIENCE)
      .setExpirationTime(claims.exp ?? '10m')
      .sign(privateKey);
  };
  return { jwk, sign };
}

test('AccountVerifier accepts tokens of its API server and learns only the account id', async () => {
  const key = await signingKey('k1');
  const verifier = new AccountVerifier(ISSUER, async () => ({ keys: [key.jwk] }));
  assert.deepEqual(await verifier.check(await key.sign({ sub: 'person-42' })), { ok: true, accountId: 'person-42' });
});

test('AccountVerifier refuses missing, foreign, expired and malformed tokens with account_required', async () => {
  const key = await signingKey('k1');
  const stranger = await signingKey('k1');
  const verifier = new AccountVerifier(ISSUER, async () => ({ keys: [key.jwk] }));
  const now = Math.floor(Date.now() / 1000);
  for (const token of [
    null,
    '',
    'not-a-jwt',
    await stranger.sign(),
    await key.sign({ iss: 'https://evil.example.com' }),
    await key.sign({ aud: 'someone-else' }),
    await key.sign({ exp: now - 120 }),
    await key.sign({ sub: '' }),
    'x'.repeat(5000),
  ]) {
    const result = await verifier.check(token);
    assert.equal(result.ok, false, String(token).slice(0, 40));
    if (!result.ok) {
      assert.equal(result.code, 'account_required');
      assert.equal(result.closeCode, 4001);
      assert.equal(result.authFailure, true);
    }
  }
});

test('AccountVerifier tolerates a minute of clock difference', async () => {
  const key = await signingKey('k1');
  const verifier = new AccountVerifier(ISSUER, async () => ({ keys: [key.jwk] }));
  const justExpired = await key.sign({ exp: Math.floor(Date.now() / 1000) - 30 });
  assert.equal((await verifier.check(justExpired)).ok, true);
});

test('AccountVerifier caches the keys and picks up a rotated key', async () => {
  const old = await signingKey('old');
  const rotated = await signingKey('new');
  let served = [old.jwk];
  let fetches = 0;
  let now = Date.now();
  const verifier = new AccountVerifier(
    ISSUER,
    async () => {
      fetches += 1;
      return { keys: served };
    },
    () => now,
  );
  assert.equal((await verifier.check(await old.sign())).ok, true);
  assert.equal((await verifier.check(await old.sign())).ok, true);
  assert.equal(fetches, 1);

  served = [old.jwk, rotated.jwk];
  // An unknown key fetches the keys again, but not more than every 30 s.
  assert.equal((await verifier.check(await rotated.sign())).ok, false);
  assert.equal(fetches, 1);
  now += 31_000;
  assert.equal((await verifier.check(await rotated.sign())).ok, true);
  assert.equal(fetches, 2);
});

test('AccountVerifier says account_unavailable (retry) when the keys cannot be fetched', async () => {
  const verifier = new AccountVerifier(ISSUER, async () => {
    throw new Error('down');
  });
  const result = await verifier.check((await (await signingKey('k1')).sign()));
  assert.equal(result.ok, false);
  if (!result.ok) {
    assert.equal(result.code, 'account_unavailable');
    assert.equal(result.closeCode, 1013);
    assert.equal(result.authFailure, false);
  }
  const garbage = new AccountVerifier(ISSUER, async () => ({ nope: true }));
  assert.equal(((await garbage.check('a.b.c')) as { code: string }).code, 'account_unavailable');
});

test('a relay with ACCOUNTS_URL welcomes only signed-in phones', async (t) => {
  const key = await signingKey('k1');
  const relay = await startRelay(t, { accountsUrl: `${ISSUER}/` }, { fetchJwks: async () => ({ keys: [key.jwk] }) });

  const signedOut = await connectDevice(relay);
  const error = await signedOut.nextFrame('error');
  assert.equal(error.code, 'account_required');
  assert.match(String(error.message), /Sign in/);
  assert.equal((await signedOut.closed).code, 4001);

  const expired = await connectDevice(relay, { hello: { accountToken: await key.sign({ exp: Math.floor(Date.now() / 1000) - 300 }) } });
  assert.equal((await expired.nextFrame('error')).code, 'account_required');

  const wrongType = await connectDevice(relay, { hello: { accountToken: 42 } });
  assert.equal((await wrongType.nextFrame('error')).code, 'bad_request');

  const signedIn = await connectDevice(relay, { hello: { accountToken: await key.sign() } });
  await signedIn.nextFrame('welcome');
  assert.equal(relay.deviceCount(), 1);
});

test('a relay that cannot reach the API server asks phones to retry (1013)', async (t) => {
  const relay = await startRelay(t, { accountsUrl: ISSUER }, {
    fetchJwks: async () => {
      throw new Error('down');
    },
  });
  const device = await connectDevice(relay, { hello: { accountToken: 'a.b.c' } });
  assert.equal((await device.nextFrame('error')).code, 'account_unavailable');
  assert.equal((await device.closed).code, 1013);
});

test('a relay without ACCOUNTS_URL ignores accountToken', async (t) => {
  const relay = await startRelay(t);
  const device = await connectDevice(relay, { hello: { accountToken: 'whatever' } });
  await device.nextFrame('welcome');
});
