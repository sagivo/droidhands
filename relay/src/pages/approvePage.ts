import { html, type PageOptions } from './html.js';

/**
 * The page an agent sends the person after starting agent sign-in (docs/PROTOCOL.md §5.6): "switch to
 * DroidHands and tap Allow". Static: it looks nothing up and grants nothing; the phone's own prompt
 * (with the agent's real name and code) decides. Its button opens the app on that prompt.
 */

/** `GET /approve?code=K7Q2`. */
export const APPROVE_PATH = '/approve';

/** The app link the button opens: DroidHands' prompt for [code] (or the newest one). */
export const APP_APPROVE_LINK = 'droidhands://approve';

/** A display code (§5.3 step 4), or null for anything else, so the page never echoes arbitrary text. */
export function approveCode(value: string | null): string | null {
  const code = value?.trim().toUpperCase() ?? '';
  return /^[A-HJ-NP-Z2-9]{4}$/.test(code) ? code : null;
}

/** The link to send the person for a sign-in with [code]. */
export function approveUrl(base: string, code: string): string {
  return `${base}${APPROVE_PATH}?code=${encodeURIComponent(code)}`;
}

export function approvePage(code: string | null): PageOptions {
  const appLink = code === null ? APP_APPROVE_LINK : `${APP_APPROVE_LINK}?code=${encodeURIComponent(code)}`;
  return {
    title: 'Approve in DroidHands',
    body: html`<div class="center">
<p class="eyebrow">DroidHands</p>
<h1>Approve your agent</h1>
<p class="muted">Your AI agent asked to use your phone. Open DroidHands and tap <strong>Allow</strong>${code === null ? '.' : ' if it shows this code:'}</p>
${code === null ? '' : html`<div class="card"><div class="code">${code}</div></div>`}
<a class="button" href="${appLink}">Open DroidHands</a>
<p class="muted">Then go back to your agent: it connects by itself. No password or key to copy.</p>
<p class="muted">On a computer? Open DroidHands on your phone instead. Didn't ask for this, or a different code? Tap <strong>Deny</strong>.</p>
</div>`,
  };
}
