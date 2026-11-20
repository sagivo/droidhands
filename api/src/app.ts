/**
 * The phone's account API (docs/PROTOCOL.md §7): browser sign-in that hands the session to the app
 * (RFC 8252 style, a custom-scheme redirect protected by PKCE), then bearer-authenticated calls.
 */
import type { Auth } from './auth.js';
import { RELAY_TOKEN_TTL_SECONDS } from './auth.js';
import { constantTimeEqual, isPkceValue, randomToken, sha256base64url } from './crypto.js';

/** Where the browser sends the phone back (an intent filter of the Android app). */
export const APP_REDIRECT = 'droidhands://signed-in';

/** A sign-in code is redeemable once, within this time. */
export const SIGN_IN_CODE_TTL_MS = 2 * 60 * 1000;

/** Prefix of the sign-in codes' rows in Better Auth's `verification` table (the code itself is hashed). */
const CODE_IDENTIFIER = 'droidhands-app-sign-in:';

/** What a phone learns about its account. */
interface PublicUser {
  readonly id: string;
  readonly name: string;
  readonly email: string;
  readonly image: string | null;
}

export async function handleApp(request: Request, url: URL, auth: Auth): Promise<Response> {
  switch (`${request.method} ${url.pathname}`) {
    case 'GET /app/sign-in':
      return signIn(request, url, auth);
    case 'GET /app/done':
      return done(request, url, auth);
    case 'GET /app/failed':
      return backToApp({ error: url.searchParams.get('error') === 'access_denied' ? 'access_denied' : 'server_error' });
    case 'POST /app/token':
      return token(request, auth);
    case 'GET /app/me':
      return me(request, auth);
    case 'POST /app/relay-token':
      return relayToken(request, auth);
    case 'POST /app/sign-out':
      return signOut(request, auth);
    case 'POST /app/delete-account':
      return deleteAccount(request, auth);
    default:
      return jsonError(404, 'not_found', 'No such endpoint.');
  }
}

/**
 * Step 1, in the phone's browser: `?code_challenge=<S256>&code_challenge_method=S256` → Google.
 * The challenge rides along in Better Auth's callback URL, which it keeps in its signed OAuth state.
 */
async function signIn(request: Request, url: URL, auth: Auth): Promise<Response> {
  const challenge = url.searchParams.get('code_challenge');
  if (!isPkceValue(challenge) || url.searchParams.get('code_challenge_method') !== 'S256') {
    return backToApp({ error: 'invalid_request', error_description: 'code_challenge (S256) is required.' });
  }
  return redirectToGoogle(request, auth, `/app/done?challenge=${challenge}`, '/app/failed');
}

/** Starts Google sign-in for the browser: a 302 to Google, with Better Auth's state cookie. */
export async function redirectToGoogle(request: Request, auth: Auth, callbackURL: string, errorCallbackURL: string): Promise<Response> {
  const started = await auth.api.signInSocial({
    body: { provider: 'google', callbackURL, errorCallbackURL, disableRedirect: true },
    headers: request.headers,
    asResponse: true,
  });
  const body = (await started.json().catch(() => null)) as { url?: unknown } | null;
  if (!started.ok || typeof body?.url !== 'string') {
    return jsonError(502, 'server_error', 'Could not start Google sign-in.');
  }
  const headers = new Headers({ location: body.url, 'cache-control': 'no-store' });
  for (const cookie of started.headers.getSetCookie()) headers.append('set-cookie', cookie);
  return new Response(null, { status: 302, headers });
}

/**
 * Step 2, after Google: the browser now has a session. Hand it to the app as a one-time code bound
 * to the PKCE challenge, and take it away from the browser (the session belongs to the phone).
 */
async function done(request: Request, url: URL, auth: Auth): Promise<Response> {
  const challenge = url.searchParams.get('challenge');
  const ctx = await auth.$context;
  const cookieName = ctx.authCookies.sessionToken.name;
  const sessionToken = cookieValue(request, cookieName);
  if (!isPkceValue(challenge) || sessionToken === null) return backToApp({ error: 'server_error' });
  const session = await auth.api.getSession({ headers: request.headers });
  if (session === null) return backToApp({ error: 'server_error' });

  const code = randomToken();
  await ctx.internalAdapter.createVerificationValue({
    identifier: CODE_IDENTIFIER + (await sha256base64url(code)),
    value: JSON.stringify({ challenge, sessionToken }),
    expiresAt: new Date(Date.now() + SIGN_IN_CODE_TTL_MS),
  });
  return backToApp({ code }, [expiredCookie(cookieName, ctx.authCookies.sessionToken.attributes.secure === true)]);
}

/** Step 3, from the app: `{code, code_verifier}` → the session token and who signed in. */
async function token(request: Request, auth: Auth): Promise<Response> {
  const body = (await request.json().catch(() => null)) as { code?: unknown; code_verifier?: unknown } | null;
  const code = body?.code;
  const verifier = body?.code_verifier;
  if (typeof code !== 'string' || code.length === 0 || code.length > 200 || !isPkceValue(verifier)) {
    return jsonError(400, 'invalid_request', 'code and code_verifier are required.');
  }
  const ctx = await auth.$context;
  // Consumed before the verifier is checked: a code gets exactly one attempt.
  const row = await ctx.internalAdapter.consumeVerificationValue(CODE_IDENTIFIER + (await sha256base64url(code)));
  const stored = row === null || row.expiresAt.getTime() < Date.now() ? null : parseStored(row.value);
  if (stored === null || !constantTimeEqual(await sha256base64url(verifier), stored.challenge)) {
    return jsonError(400, 'invalid_grant', 'This sign-in code is invalid, used or expired. Sign in again.');
  }
  const session = await auth.api.getSession({ headers: bearer(stored.sessionToken) });
  if (session === null) return jsonError(400, 'invalid_grant', 'The sign-in has expired. Sign in again.');
  return json({ sessionToken: stored.sessionToken, user: publicUser(session.user) });
}

async function me(request: Request, auth: Auth): Promise<Response> {
  const session = await sessionOf(request, auth);
  if (session === null) return signedOut();
  return json({ user: publicUser(session.user) });
}

/** A short-lived JWT the phone shows the relay in its hello (`accountToken`, docs/PROTOCOL.md §3.1). */
async function relayToken(request: Request, auth: Auth): Promise<Response> {
  const session = await sessionOf(request, auth);
  if (session === null) return signedOut();
  const expiresAt = Date.now() + RELAY_TOKEN_TTL_SECONDS * 1000;
  const { token } = await auth.api.getToken({ headers: request.headers });
  return json({ token, expiresAt });
}

async function signOut(request: Request, auth: Auth): Promise<Response> {
  const token = bearerToken(request);
  if (token !== null && (await sessionOf(request, auth)) !== null) {
    await auth.api.signOut({ headers: bearer(token) });
  }
  return new Response(null, { status: 204 });
}

async function deleteAccount(request: Request, auth: Auth): Promise<Response> {
  const session = await sessionOf(request, auth);
  if (session === null) return signedOut();
  await deleteUser(auth, session.user.id);
  return new Response(null, { status: 204 });
}

/** Deletes the user with their sessions and linked Google identity. */
export async function deleteUser(auth: Auth, userId: string): Promise<void> {
  const ctx = await auth.$context;
  await ctx.internalAdapter.deleteUser(userId);
}

async function sessionOf(request: Request, auth: Auth) {
  const token = bearerToken(request);
  if (token === null) return null;
  // Also extends the session (at most once a day) while the phone keeps using it.
  return auth.api.getSession({ headers: bearer(token) });
}

function bearerToken(request: Request): string | null {
  const header = request.headers.get('authorization') ?? '';
  const match = /^Bearer\s+(\S+)$/i.exec(header.trim());
  return match?.[1] ?? null;
}

function bearer(token: string): Headers {
  return new Headers({ authorization: `Bearer ${token}` });
}

function parseStored(value: string): { challenge: string; sessionToken: string } | null {
  try {
    const parsed = JSON.parse(value) as { challenge?: unknown; sessionToken?: unknown };
    if (typeof parsed.challenge === 'string' && typeof parsed.sessionToken === 'string') {
      return { challenge: parsed.challenge, sessionToken: parsed.sessionToken };
    }
  } catch {
    // Not one of ours.
  }
  return null;
}

function publicUser(user: { id: string; name: string; email: string; image?: string | null }): PublicUser {
  return { id: user.id, name: user.name, email: user.email, image: user.image ?? null };
}

/** The raw value of cookie [name] in [request], or null. */
export function cookieValue(request: Request, name: string): string | null {
  for (const part of (request.headers.get('cookie') ?? '').split(';')) {
    const eq = part.indexOf('=');
    if (eq > 0 && part.slice(0, eq).trim() === name) return decodeURIComponent(part.slice(eq + 1).trim());
  }
  return null;
}

export function expiredCookie(name: string, secure: boolean): string {
  return `${name}=; Max-Age=0; Path=/; HttpOnly; SameSite=Lax${secure ? '; Secure' : ''}`;
}

/** A 302 to the app's redirect with [params]. */
function backToApp(params: Record<string, string>, cookies: readonly string[] = []): Response {
  const target = `${APP_REDIRECT}?${new URLSearchParams(params).toString()}`;
  const headers = new Headers({ location: target, 'cache-control': 'no-store', 'referrer-policy': 'no-referrer' });
  for (const cookie of cookies) headers.append('set-cookie', cookie);
  return new Response(null, { status: 302, headers });
}

function signedOut(): Response {
  return jsonError(401, 'signed_out', 'Not signed in, or the session has ended. Sign in again.');
}

export function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json', 'cache-control': 'no-store' },
  });
}

export function jsonError(status: number, error: string, description: string): Response {
  return json({ error, error_description: description }, status);
}
