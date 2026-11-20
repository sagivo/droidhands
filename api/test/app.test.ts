import { SELF, env as testEnv } from 'cloudflare:test';
import { serializeSignedCookie } from 'better-call';
import { createLocalJWKSet, jwtVerify } from 'jose';
import { describe, expect, it } from 'vitest';
import { createAuth, RELAY_AUDIENCE, type Env } from '../src/auth.js';
import { randomToken, sha256base64url } from '../src/crypto.js';

const env = testEnv as unknown as Env;
const BASE = 'http://api.test';
const auth = createAuth(env);

function get(path: string, headers: HeadersInit = {}): Promise<Response> {
  return SELF.fetch(BASE + path, { headers, redirect: 'manual' });
}

function post(path: string, body?: unknown, headers: HeadersInit = {}): Promise<Response> {
  return SELF.fetch(BASE + path, {
    method: 'POST',
    headers: { 'content-type': 'application/json', ...headers },
    body: body === undefined ? undefined : JSON.stringify(body),
    redirect: 'manual',
  });
}

function bearer(token: string): HeadersInit {
  return { authorization: `Bearer ${token}` };
}

/** A user who signed in with Google, as the OAuth callback leaves them: a session cookie in the browser. */
async function googleUser(email = `${randomToken(6)}@example.com`) {
  const ctx = await auth.$context;
  const { user } = await ctx.internalAdapter.createOAuthUser(
    { name: 'Test Person', email, emailVerified: true, image: null },
    { providerId: 'google', accountId: randomToken(8), accessToken: 'google-access', refreshToken: 'google-refresh', idToken: 'google-id' },
  );
  const session = await ctx.internalAdapter.createSession(user.id);
  const signed = (await serializeSignedCookie('', session.token, env.BETTER_AUTH_SECRET)).slice(1);
  const cookie = `${ctx.authCookies.sessionToken.name}=${signed}`;
  return { user, cookie };
}

/** The app's side of a sign-in: verifier, challenge, and the browser landing on /app/done. */
async function handoff(cookie: string) {
  const verifier = randomToken(48);
  const challenge = await sha256base64url(verifier);
  const done = await get(`/app/done?challenge=${challenge}`, { cookie });
  return { verifier, done, location: new URL(done.headers.get('location') ?? 'invalid:') };
}

async function signedInApp() {
  const person = await googleUser();
  const { verifier, location } = await handoff(person.cookie);
  const res = await post('/app/token', { code: location.searchParams.get('code'), code_verifier: verifier });
  expect(res.status).toBe(200);
  const body = (await res.json()) as { sessionToken: string; user: { id: string; email: string } };
  return { ...person, sessionToken: body.sessionToken, body };
}

describe('sign-in', () => {
  it('sends the browser to Google with the callback on this server', async () => {
    const challenge = await sha256base64url(randomToken(48));
    const res = await get(`/app/sign-in?code_challenge=${challenge}&code_challenge_method=S256`);
    expect(res.status).toBe(302);
    const google = new URL(res.headers.get('location')!);
    expect(google.host).toBe('accounts.google.com');
    expect(google.searchParams.get('client_id')).toBe(env.GOOGLE_CLIENT_ID);
    expect(google.searchParams.get('redirect_uri')).toBe(`${BASE}/api/auth/callback/google`);
    expect(google.searchParams.get('prompt')).toBe('select_account');
    expect(res.headers.getSetCookie().length).toBeGreaterThan(0);
  });

  it('sends the app an error without a usable S256 challenge', async () => {
    for (const query of ['', '?code_challenge=short&code_challenge_method=S256', `?code_challenge=${'a'.repeat(43)}&code_challenge_method=plain`]) {
      const res = await get(`/app/sign-in${query}`);
      expect(res.status).toBe(302);
      const location = new URL(res.headers.get('location')!);
      expect(`${location.protocol}//${location.host}`).toBe('droidhands://signed-in');
      expect(location.searchParams.get('error')).toBe('invalid_request');
    }
  });

  it('maps Google errors to the app redirect', async () => {
    expect((await get('/app/failed?error=access_denied')).headers.get('location')).toBe('droidhands://signed-in?error=access_denied');
    expect((await get('/app/failed?error=please_restart_the_process')).headers.get('location')).toBe('droidhands://signed-in?error=server_error');
  });

  it('hands the browser session to the app and clears it from the browser', async () => {
    const { cookie, user } = await googleUser();
    const { done, location, verifier } = await handoff(cookie);
    expect(done.status).toBe(302);
    expect(`${location.protocol}//${location.host}`).toBe('droidhands://signed-in');
    const code = location.searchParams.get('code')!;
    expect(code).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(done.headers.getSetCookie().join('\n')).toMatch(/session_token=; Max-Age=0/);

    const res = await post('/app/token', { code, code_verifier: verifier });
    expect(res.status).toBe(200);
    const body = (await res.json()) as { sessionToken: string; user: Record<string, unknown> };
    expect(body.user).toEqual({ id: user.id, name: 'Test Person', email: user.email, image: null });
    expect(typeof body.sessionToken).toBe('string');
  });

  it('without a browser session, tells the app it failed', async () => {
    const challenge = await sha256base64url(randomToken(48));
    const res = await get(`/app/done?challenge=${challenge}`);
    expect(res.headers.get('location')).toBe('droidhands://signed-in?error=server_error');
  });

  it('gives a code one attempt, and only with the right verifier', async () => {
    const { cookie } = await googleUser();
    const { location, verifier } = await handoff(cookie);
    const code = location.searchParams.get('code')!;
    const wrong = await post('/app/token', { code, code_verifier: randomToken(48) });
    expect(wrong.status).toBe(400);
    expect(((await wrong.json()) as { error: string }).error).toBe('invalid_grant');
    // The right verifier is too late: the code is spent.
    expect((await post('/app/token', { code, code_verifier: verifier })).status).toBe(400);
    expect((await post('/app/token', { code: 'nope', code_verifier: verifier })).status).toBe(400);
    expect((await post('/app/token', { code })).status).toBe(400);
  });

  it('never stores Google tokens', async () => {
    const { user } = await googleUser();
    const row = await env.DB.prepare('select accessToken, refreshToken, idToken from account where userId = ?').bind(user.id).first();
    expect(row).toEqual({ accessToken: null, refreshToken: null, idToken: null });
  });
});

describe('signed-in app', () => {
  it('reads its account', async () => {
    const { sessionToken, user } = await signedInApp();
    const res = await get('/app/me', bearer(sessionToken));
    expect(res.status).toBe(200);
    expect(((await res.json()) as { user: { email: string } }).user.email).toBe(user.email);
    expect((await get('/app/me')).status).toBe(401);
    expect((await get('/app/me', bearer('not-a-session'))).status).toBe(401);
  });

  it('gets relay tokens that relays can verify with the JWKS', async () => {
    const { sessionToken, user } = await signedInApp();
    const res = await post('/app/relay-token', undefined, bearer(sessionToken));
    expect(res.status).toBe(200);
    const { token, expiresAt } = (await res.json()) as { token: string; expiresAt: number };
    expect(expiresAt).toBeGreaterThan(Date.now());

    const jwks = (await (await get('/api/auth/jwks')).json()) as Parameters<typeof createLocalJWKSet>[0];
    const { payload } = await jwtVerify(token, createLocalJWKSet(jwks), { issuer: BASE, audience: RELAY_AUDIENCE });
    expect(payload.sub).toBe(user.id);
    // Relays learn the account id only.
    expect(Object.keys(payload).sort()).toEqual(['aud', 'exp', 'iat', 'iss', 'sub']);
    expect(Math.abs(payload.exp! * 1000 - expiresAt)).toBeLessThan(5_000);

    expect((await post('/app/relay-token')).status).toBe(401);
  });

  it('signs out', async () => {
    const { sessionToken } = await signedInApp();
    expect((await post('/app/sign-out', undefined, bearer(sessionToken))).status).toBe(204);
    expect((await get('/app/me', bearer(sessionToken))).status).toBe(401);
    expect((await post('/app/relay-token', undefined, bearer(sessionToken))).status).toBe(401);
    // Idempotent.
    expect((await post('/app/sign-out', undefined, bearer(sessionToken))).status).toBe(204);
  });

  it('deletes the account with its sessions and Google identity', async () => {
    const { sessionToken, user } = await signedInApp();
    expect((await post('/app/delete-account', undefined, bearer(sessionToken))).status).toBe(204);
    expect((await get('/app/me', bearer(sessionToken))).status).toBe(401);
    for (const [table, column] of [['user', 'id'], ['session', 'userId'], ['account', 'userId']] as const) {
      const row = await env.DB.prepare(`select count(*) as n from "${table}" where "${column}" = ?`).bind(user.id).first<{ n: number }>();
      expect(row?.n, table).toBe(0);
    }
  });
});

describe('web pages', () => {
  it('deletes an account from the web, only by a same-origin form post', async () => {
    const { cookie, user } = await googleUser();
    const confirm = await get('/delete-account/confirm', { cookie });
    expect(await confirm.text()).toContain(user.email);

    const forged = await post('/delete-account/confirm', undefined, { cookie, origin: 'https://evil.example' });
    expect(forged.status).toBe(403);

    const res = await post('/delete-account/confirm', undefined, { cookie, origin: BASE });
    expect(res.status).toBe(200);
    expect(await res.text()).toContain('Account deleted');
    expect(await env.DB.prepare('select id from "user" where id = ?').bind(user.id).first()).toBeNull();
  });

  it('serves the home and deletion pages with strict headers', async () => {
    for (const path of ['/', '/delete-account']) {
      const res = await get(path);
      expect(res.status).toBe(200);
      expect(res.headers.get('content-security-policy')).toContain("default-src 'none'");
    }
    expect((await get('/nope')).status).toBe(404);
  });
});
