/**
 * DroidHands API Worker (api/README.md): Google sign-in with Better Auth, users in D1.
 *
 *   /api/auth/*        Better Auth (Google OAuth callback, JWKS for relays, sessions)
 *   /app/*             the phone's account API (docs/PROTOCOL.md §7)
 *   /delete-account    delete an account from the web (Google Play requires this)
 */
import { cookieValue, deleteUser, expiredCookie, handleApp, jsonError, redirectToGoogle } from './app.js';
import { createAuth, missingSettings, type Auth, type Env } from './auth.js';
import { deleteConfirmPage, deleteStartPage, deletedPage, errorPage, homePage } from './pages.js';

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const missing = missingSettings(env);
    if (missing.length > 0) {
      console.error(`api: missing settings: ${missing.join(', ')}`);
      return jsonError(500, 'server_error', `The API server is not configured (missing ${missing.join(', ')}).`);
    }
    const url = new URL(request.url);
    const auth = createAuth(env);
    try {
      // Better Auth initializes in the background and keeps parts of it module-wide. Let it finish
      // inside this request: work a request leaves running is cancelled when the request ends, and
      // the next request would wait for it forever.
      await auth.$context;
      return await route(request, url, auth);
    } catch (err) {
      console.error('api: unhandled error', err instanceof Error ? err.message : String(err));
      return jsonError(500, 'server_error', 'Something went wrong. Try again.');
    }
  },
} satisfies ExportedHandler<Env>;

async function route(request: Request, url: URL, auth: Auth): Promise<Response> {
  const { pathname } = url;
  if (pathname.startsWith('/api/auth/')) return auth.handler(request);
  if (pathname.startsWith('/app/')) return handleApp(request, url, auth);
  const key = `${request.method} ${pathname}`;
  switch (key) {
    case 'GET /':
      return homePage();
    case 'GET /healthz':
      return new Response('{"ok":true}', { headers: { 'content-type': 'application/json' } });
    case 'GET /delete-account':
      return deleteStartPage();
    case 'POST /delete-account/sign-in':
      if (!sameOrigin(request, url)) return errorPage(403, 'This form must be sent from this site.');
      return redirectToGoogle(request, auth, '/delete-account/confirm', '/delete-account');
    case 'GET /delete-account/confirm': {
      const session = await auth.api.getSession({ headers: request.headers });
      if (session === null) return deleteStartPage();
      return deleteConfirmPage(session.user.email);
    }
    case 'POST /delete-account/confirm': {
      if (!sameOrigin(request, url)) return errorPage(403, 'This form must be sent from this site.');
      const session = await auth.api.getSession({ headers: request.headers });
      if (session === null) return deleteStartPage();
      await deleteUser(auth, session.user.id);
      const ctx = await auth.$context;
      const cookie = ctx.authCookies.sessionToken;
      const headers: Record<string, string> =
        cookieValue(request, cookie.name) === null ? {} : { 'set-cookie': expiredCookie(cookie.name, cookie.attributes.secure === true) };
      return deletedPage(headers);
    }
    default:
      return errorPage(404, 'There is nothing here.');
  }
}

/** A form post from one of our own pages (the session cookie is SameSite=Lax as well). */
function sameOrigin(request: Request, url: URL): boolean {
  const origin = request.headers.get('origin');
  return origin === null ? request.headers.get('sec-fetch-site') === 'same-origin' : origin === url.origin;
}
