import type { IncomingMessage, ServerResponse } from 'node:http';
import { ANDROID_PACKAGE } from '../setup/setupUrl.js';
import { SafeHtml, html, sendPage } from './html.js';

/**
 * The phone setup page (docs/PROTOCOL.md §5.5): install the app, then open it pre-configured for
 * this relay. The access key is read client-side from the URL fragment (`#key=…`) and never sent to
 * the server, except to `POST /setup/qr` by the page itself to draw a QR code that includes it.
 */

export interface SetupPageInput {
  /** `{relay}`. */
  readonly base: string;
  /** Download link for the APK, or null when the operator provided none. */
  readonly apkUrl: string | null;
  /** Where the page's script asks for a QR code that includes the access key. */
  readonly qrEndpoint: string;
  readonly accessKeyRequired: boolean;
  /** Inline QR `<svg>` of the setup URL (only when no access key is involved), or null. */
  readonly qrSvg: string | null;
}

export function sendSetupPage(req: IncomingMessage, res: ServerResponse, input: SetupPageInput): void {
  const { base, apkUrl, qrEndpoint, accessKeyRequired, qrSvg } = input;
  sendPage(req, res, 200, {
    title: 'Set up PhoneBridge',
    body: html`<div id="setup" data-relay="${base}" data-key-required="${String(accessKeyRequired)}" data-qr="${qrEndpoint}">
<h1>Set up PhoneBridge</h1>
<p>Let your AI apps use this Android phone, with your approval.</p>
<p id="key-missing" class="warn hidden">This link is missing the relay's access key. Open the full setup link
printed by the relay (it ends with <code>#key=…</code>), or enter the key by hand in step 3.</p>

<div class="card">
<h2>1. Install the app</h2>
${
  apkUrl === null
    ? html`<p>Install the PhoneBridge app on your phone. Ask whoever runs this relay for the app, or build it from source.</p>`
    : html`<a class="button" href="${apkUrl}" download>Install PhoneBridge</a>
<p class="muted">Android may ask you to allow installs from your browser. Allow it, install, then come back here.</p>`
}
</div>

<div class="card">
<h2>2. Connect it to this relay</h2>
<a id="open" class="button" href="${`intent://configure?relay=${encodeURIComponent(base)}#Intent;scheme=phonebridge;package=${ANDROID_PACKAGE};end`}">Open in PhoneBridge</a>
<p class="muted">PhoneBridge asks you to confirm, then connects. Link not working?
<a id="open-direct" href="${`phonebridge://configure?relay=${encodeURIComponent(base)}`}">Try this one</a>.</p>
</div>

<div class="card">
<h2>3. Or enter it by hand</h2>
<p>In PhoneBridge, open <strong>Settings</strong> and set:</p>
<p>Relay URL: <code>${base}</code></p>
<p id="manual-key-row" class="${accessKeyRequired ? '' : 'hidden'}">Access key: <code id="manual-key">(from your setup link)</code></p>
</div>

<div class="card">
<h2>Then connect your AI app</h2>
<ol>
<li>PhoneBridge shows <strong>Your connection URL</strong>. Copy it.</li>
<li>Paste it into your AI app as a custom connector (for example Claude: <em>Settings › Connectors › Add custom connector</em>).</li>
<li>Your phone asks whether to allow the app. Check the code and tap <strong>Allow</strong>.</li>
</ol>
</div>

<div id="qr-card" class="card desktop-only${qrSvg === null ? ' hidden' : ''}">
<h2>On a computer?</h2>
<p>Scan this with your phone's camera to open this page there.</p>
<div id="qr">${qrSvg === null ? '' : new SafeHtml(qrSvg)}</div>
</div>
</div>`,
    script: SETUP_SCRIPT,
  });
}

/**
 * Builds the app links from the fragment's `key`, shows it for manual entry and, when the relay
 * needs a key, asks `POST /setup/qr` for a QR code that includes it. DOM updates use textContent
 * and createElementNS only.
 */
const SETUP_SCRIPT = `(function () {
  'use strict';
  var root = document.getElementById('setup');
  var relay = root.getAttribute('data-relay');
  var keyRequired = root.getAttribute('data-key-required') === 'true';
  var key = new URLSearchParams(location.hash.slice(1)).get('key') || '';
  var query = 'relay=' + encodeURIComponent(relay) + (key ? '&key=' + encodeURIComponent(key) : '');
  document.getElementById('open').href =
    'intent://configure?' + query + '#Intent;scheme=phonebridge;package=${ANDROID_PACKAGE};end';
  document.getElementById('open-direct').href = 'phonebridge://configure?' + query;
  if (key) {
    document.getElementById('manual-key').textContent = key;
    document.getElementById('manual-key-row').className = '';
  }
  if (keyRequired && !key) document.getElementById('key-missing').className = 'warn';
  if (!keyRequired || !key || !window.fetch) return;
  fetch(root.getAttribute('data-qr'), {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ key: key }),
    cache: 'no-store',
    credentials: 'omit'
  })
    .then(function (r) { return r.ok ? r.json() : null; })
    .then(function (d) {
      if (!d || typeof d.path !== 'string' || typeof d.size !== 'number') return;
      var NS = 'http://www.w3.org/2000/svg';
      var svg = document.createElementNS(NS, 'svg');
      svg.setAttribute('class', 'qr');
      svg.setAttribute('viewBox', '0 0 ' + d.size + ' ' + d.size);
      svg.setAttribute('shape-rendering', 'crispEdges');
      svg.setAttribute('role', 'img');
      svg.setAttribute('aria-label', 'QR code of this setup page');
      var bg = document.createElementNS(NS, 'rect');
      bg.setAttribute('width', String(d.size));
      bg.setAttribute('height', String(d.size));
      bg.setAttribute('fill', '#fff');
      var path = document.createElementNS(NS, 'path');
      path.setAttribute('d', d.path);
      path.setAttribute('fill', '#000');
      svg.appendChild(bg);
      svg.appendChild(path);
      document.getElementById('qr').appendChild(svg);
      document.getElementById('qr-card').className = 'card desktop-only';
    })
    .catch(function () {});
})();`;
