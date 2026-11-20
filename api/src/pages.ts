/**
 * The few HTML pages of the API Worker: home, the web account-deletion flow (Google Play
 * requires one outside the app) and errors. No scripts, no external assets.
 */

const ESCAPES: Record<string, string> = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };

export function escapeHtml(text: string): string {
  return text.replace(/[&<>"']/g, (c) => ESCAPES[c] as string);
}

const STYLES = `
:root { color-scheme: light dark; --fg: #111827; --muted: #4b5563; --bg: #f9fafb; --card: #fff; --accent: #4f46e5; --danger: #dc2626; }
@media (prefers-color-scheme: dark) { :root { --fg: #f3f4f6; --muted: #9ca3af; --bg: #0b0d12; --card: #161a22; } }
* { box-sizing: border-box; }
body { margin: 0; font: 16px/1.5 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; color: var(--fg); background: var(--bg); }
main { max-width: 28rem; margin: 10vh auto; padding: 2rem; background: var(--card); border-radius: 1rem; }
h1 { font-size: 1.4rem; margin: 0 0 1rem; }
p { color: var(--muted); }
.button { display: inline-block; border: 0; border-radius: 0.75rem; padding: 0.75rem 1.25rem; font: inherit; font-weight: 600; color: #fff; background: var(--accent); text-decoration: none; cursor: pointer; }
.danger { background: var(--danger); }
form { margin: 1.5rem 0 0; }
`;

const SECURITY_HEADERS: Record<string, string> = {
  'content-type': 'text/html; charset=utf-8',
  // Inline style only; forms may only post back here.
  'content-security-policy': "default-src 'none'; style-src 'unsafe-inline'; img-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'",
  'referrer-policy': 'no-referrer',
  'x-frame-options': 'DENY',
  'x-content-type-options': 'nosniff',
  'cache-control': 'no-store',
};

/** A page; [bodyHtml] must already be escaped. */
export function page(title: string, bodyHtml: string, status = 200, extraHeaders: HeadersInit = {}): Response {
  const document = `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex, nofollow"><title>${escapeHtml(title)}</title><style>${STYLES}</style></head>
<body><main><h1>${escapeHtml(title)}</h1>${bodyHtml}</main></body></html>`;
  const headers = new Headers(SECURITY_HEADERS);
  new Headers(extraHeaders).forEach((value, name) => headers.append(name, value));
  return new Response(document, { status, headers });
}

export function homePage(): Response {
  return page(
    'DroidHands',
    `<p>Your DroidHands account lets your phone use the DroidHands relay, so AI agents can reach it from
anywhere. Sign in from the DroidHands app: Connect › DroidHands relay › Continue with Google.</p>
<p><a href="/delete-account">Delete your account</a></p>`,
  );
}

export function deleteStartPage(): Response {
  return page(
    'Delete your DroidHands account',
    `<p>This deletes your account: your name, email address and the link to your Google account. Phones
signed in with it stop using the DroidHands relay. Pairings and logs on your phone are not affected.</p>
<p>In the app: Settings › Account › Delete account. Or sign in here first:</p>
<form method="post" action="/delete-account/sign-in"><button class="button" type="submit">Continue with Google</button></form>`,
  );
}

export function deleteConfirmPage(email: string): Response {
  return page(
    'Delete your DroidHands account?',
    `<p>Signed in as <strong>${escapeHtml(email)}</strong>. This can't be undone.</p>
<form method="post" action="/delete-account/confirm"><button class="button danger" type="submit">Delete account</button></form>`,
  );
}

export function deletedPage(extraHeaders: HeadersInit): Response {
  return page('Account deleted', '<p>Your DroidHands account and its data are gone.</p>', 200, extraHeaders);
}

export function errorPage(status: number, message: string): Response {
  return page('Something went wrong', `<p>${escapeHtml(message)}</p>`, status);
}
