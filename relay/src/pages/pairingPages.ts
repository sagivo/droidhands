import type { IncomingMessage, ServerResponse } from 'node:http';
import type { PairingView } from '../oauth/pairingStore.js';
import { html, messagePage, sendPage, type PageOptions } from './html.js';

/**
 * Pages of the one-tap pairing flow (docs/PROTOCOL.md §5.3): the "approve on your phone" waiting
 * page, the offline page and error pages. All dynamic text is escaped by [html].
 */

export interface WaitingPageInput {
  readonly pairing: PairingView;
  /** Name the phone reported in its hello, or null. */
  readonly deviceName: string | null;
  /** JSON status endpoint, `/oauth/pair/<id>?t=<pageToken>`. */
  readonly pollUrl: string;
  /** Reloadable HTML view of the same pairing. */
  readonly pageUrl: string;
  readonly now: number;
}

/** "Approve on your phone": shows the device name and display code, polls, then navigates on. */
export function sendWaitingPage(req: IncomingMessage, res: ServerResponse, input: WaitingPageInput): void {
  sendPage(req, res, 200, waitingPage(input));
}

/** The [sendWaitingPage] page (status 200), for adapters other than node:http. */
export function waitingPage(input: WaitingPageInput): PageOptions {
  const { pairing, deviceName, pollUrl, pageUrl, now } = input;
  const remaining = Math.max(0, Math.ceil((pairing.expiresAt - now) / 1000));
  return {
    title: 'Approve on your phone · DroidHands',
    head: html`<noscript><meta http-equiv="refresh" content="5;url=${pageUrl}"></noscript>`,
    body: html`<div id="pairing" class="center" data-poll="${pollUrl}" data-page="${pageUrl}" data-remaining="${remaining}">
<p class="eyebrow">DroidHands</p>
<h1>Tap Allow on your phone</h1>
<p class="muted"><strong>${pairing.clientName}</strong> wants to use <strong>${deviceName ?? 'your phone'}</strong>. Allow it if your phone shows this code:</p>
<div class="card">
<div class="code" id="code">${pairing.displayCode}</div>
<p id="status" role="status" aria-live="polite"><span class="spinner" aria-hidden="true"></span>Waiting for your phone…</p>
</div>
<a id="next" class="button hidden" href="${pageUrl}">Continue</a>
<p class="muted">Different code, or you didn't start this? Tap <strong>Deny</strong> on your phone.
Then you return to <span class="mono">${pairing.redirectHost}</span>. <span id="expiry"></span></p>
<noscript><p><a class="button" href="${pageUrl}">I answered on my phone. Continue</a></p></noscript>
</div>`,
    script: WAITING_SCRIPT,
  };
}

/** §5.3 step 3. */
export function sendOfflinePage(req: IncomingMessage, res: ServerResponse): void {
  sendPage(req, res, OFFLINE_PAGE_STATUS, offlinePage());
}

/** Status of the [offlinePage]. */
export const OFFLINE_PAGE_STATUS = 503;

/** The [sendOfflinePage] page, for adapters other than node:http. */
export function offlinePage(): PageOptions {
  return messagePage(
    'Your phone is offline',
    ['Your phone is offline. Open DroidHands, make sure it shows Connected, then reload this page.'],
    html`<a class="button" href="">Reload</a>`,
  );
}

/** An error the user can't fix by retrying here; [hint] says what to do instead. */
export function sendPairingError(
  req: IncomingMessage,
  res: ServerResponse,
  status: number,
  problem: string,
  hint: string,
): void {
  sendPage(req, res, status, pairingErrorPage(problem, hint));
}

/** The [sendPairingError] page, for adapters other than node:http. */
export function pairingErrorPage(problem: string, hint: string): PageOptions {
  return messagePage("Can't connect to your phone", [problem, html`<span class="muted">${hint}</span>`]);
}

/**
 * Polls the status endpoint every 1.5 s (immediately when the tab becomes visible again, e.g. after
 * switching back from the DroidHands app) and navigates to the decision's redirect. Static: all
 * data comes from `data-*` attributes; DOM updates use textContent only.
 */
const WAITING_SCRIPT = `(function () {
  'use strict';
  var root = document.getElementById('pairing');
  var pollUrl = root.getAttribute('data-poll');
  var pageUrl = root.getAttribute('data-page');
  var deadline = Date.now() + Number(root.getAttribute('data-remaining')) * 1000;
  var status = document.getElementById('status');
  var next = document.getElementById('next');
  var expiry = document.getElementById('expiry');
  var timer = null;
  var done = false;
  try { history.replaceState(null, '', pageUrl); } catch (e) {}
  var MESSAGES = {
    approved: '✓ Allowed. Returning to your app…',
    denied: 'Denied on your phone. Returning to your app…',
    expired: 'This request expired. Returning to your app…'
  };
  function show(text, cls) { status.textContent = text; status.className = cls || ''; }
  function finish(d) {
    done = true;
    if (/^\\s*(javascript|data|vbscript):/i.test(d.redirect)) return;
    show(MESSAGES[d.status] || 'Returning to your app…', d.status === 'approved' ? 'ok' : 'warn');
    expiry.textContent = '';
    next.href = d.redirect;
    next.className = 'button';
    location.replace(d.redirect);
  }
  function schedule(ms) { clearTimeout(timer); if (!done) timer = setTimeout(poll, ms); }
  function poll() {
    if (done) return;
    fetch(pollUrl, { headers: { accept: 'application/json' }, cache: 'no-store', credentials: 'omit' })
      .then(function (r) {
        if (r.status === 404) return { status: 'gone' };
        if (!r.ok) throw new Error('HTTP ' + r.status);
        return r.json();
      })
      .then(function (d) {
        if (done) return;
        if (typeof d.redirect === 'string') return finish(d);
        if (d.status === 'gone') {
          done = true;
          show('This request is no longer valid. Start again from your AI app.', 'warn');
          return;
        }
        schedule(1500);
      })
      .catch(function () { schedule(3000); });
  }
  function tick() {
    if (done) return;
    var left = Math.max(0, Math.round((deadline - Date.now()) / 1000));
    expiry.textContent = 'Expires in ' + Math.floor(left / 60) + ':' + ('0' + (left % 60)).slice(-2) + '.';
    setTimeout(tick, 1000);
  }
  document.addEventListener('visibilitychange', function () { if (!document.hidden) schedule(0); });
  tick();
  schedule(1500);
})();`;
