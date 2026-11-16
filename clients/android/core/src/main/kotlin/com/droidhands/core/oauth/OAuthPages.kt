package com.droidhands.core.oauth

import com.droidhands.core.util.Encoding
import java.security.SecureRandom

/**
 * The pages of local one-tap pairing (docs/PROTOCOL.md §4.1), the same as the relay's
 * (relay/src/pages/pairingPages.ts): the "approve on your phone" waiting page and error pages.
 *
 * Every dynamic value is HTML-escaped. Pages use no external assets; inline style and script carry a
 * per-response CSP nonce, and the headers forbid framing, forms and referrers.
 */
internal object OAuthPages {
    class Page(val html: String, val headers: Map<String, String>)

    /** "Approve on your phone": shows the display code, polls [pollUrl], then navigates on. */
    fun waiting(
        clientName: String,
        displayCode: String,
        redirectHost: String,
        deviceName: String?,
        pollUrl: String,
        pageUrl: String,
        remainingSeconds: Long,
        random: SecureRandom,
    ): Page = render(
        title = "Approve on your phone · DroidHands",
        head = """<noscript><meta http-equiv="refresh" content="5;url=${esc(pageUrl)}"></noscript>""",
        body = """<div id="pairing" data-poll="${esc(pollUrl)}" data-page="${esc(pageUrl)}" data-remaining="$remainingSeconds">
<h1>Approve on your phone</h1>
<p><strong>${esc(clientName)}</strong> wants to connect to your phone.</p>
<div class="card">
<p>Open DroidHands on <strong>${esc(deviceName ?: "your phone")}</strong> and tap <strong>Allow</strong>.</p>
<p class="muted">Check that the code shown there is</p>
<div class="code" id="code">${esc(displayCode)}</div>
</div>
<p id="status" role="status" aria-live="polite"><span class="spinner" aria-hidden="true"></span>Waiting for your phone…</p>
<a id="next" class="button hidden" href="${esc(pageUrl)}">Continue</a>
<p class="muted">If the code on your phone is different, or you did not start this, tap <strong>Deny</strong>.
After you approve, you will return to <span class="mono">${esc(redirectHost)}</span>.
<span id="expiry"></span></p>
<noscript><p><a class="button" href="${esc(pageUrl)}">I answered on my phone. Continue</a></p></noscript>
</div>""",
        script = WAITING_SCRIPT,
        random = random,
    )

    /** An error the user can't fix by retrying here; [hint] says what to do instead. */
    fun error(problem: String, hint: String, random: SecureRandom): Page = render(
        title = "Can't connect to your phone · DroidHands",
        head = "",
        body = "<h1>Can&#39;t connect to your phone</h1><p>${esc(problem)}</p><p><span class=\"muted\">${esc(hint)}</span></p>",
        script = null,
        random = random,
    )

    /** Escapes the five HTML-significant characters (safe in text and in quoted attributes). */
    fun esc(text: String): String = buildString(text.length) {
        for (c in text) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }

    private fun render(title: String, head: String, body: String, script: String?, random: SecureRandom): Page {
        val nonce = Encoding.base64Url(ByteArray(16).also(random::nextBytes))
        val html = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="color-scheme" content="light dark">
<meta name="robots" content="noindex, nofollow">
<title>${esc(title)}</title>
<style nonce="$nonce">$STYLES</style>
$head
</head>
<body>
<main>
$body
</main>
${if (script == null) "" else "<script nonce=\"$nonce\">$script</script>"}
</body>
</html>
"""
        val csp = listOf(
            "default-src 'none'",
            "style-src 'nonce-$nonce'",
            "script-src 'nonce-$nonce'",
            "img-src 'self'",
            "connect-src 'self'",
            "base-uri 'none'",
            "form-action 'none'",
            "frame-ancestors 'none'",
        ).joinToString("; ")
        return Page(
            html,
            mapOf(
                "content-type" to "text/html; charset=utf-8",
                "content-security-policy" to csp,
                "referrer-policy" to "no-referrer",
                "x-frame-options" to "DENY",
                "x-content-type-options" to "nosniff",
            ),
        )
    }

    /** Shared stylesheet (the relay's): system fonts, mobile-first, dark-mode aware. */
    private const val STYLES = """
:root{--bg:#f6f7f9;--card:#fff;--fg:#1b1d21;--muted:#5d636e;--accent:#1a73e8;--accent-fg:#fff;--border:#dfe2e7;--warn:#a15c00}
@media (prefers-color-scheme:dark){:root{--bg:#121417;--card:#1d2025;--fg:#eceef1;--muted:#a3a9b3;--accent:#8ab4f8;--accent-fg:#0b1a33;--border:#30343b;--warn:#f0b35a}}
*{box-sizing:border-box}
html{-webkit-text-size-adjust:100%}
body{margin:0;background:var(--bg);color:var(--fg);font:16px/1.5 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
main{max-width:34rem;margin:0 auto;padding:2rem 1.25rem 3rem}
h1{font-size:1.5rem;line-height:1.25;margin:0 0 1rem}
p{margin:.75rem 0}
a{color:var(--accent)}
code,.mono{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;word-break:break-all}
.card{background:var(--card);border:1px solid var(--border);border-radius:14px;padding:1.25rem;margin:1rem 0}
.muted{color:var(--muted);font-size:.925rem}
.warn{color:var(--warn)}
.code{font:700 2.5rem/1.1 ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;letter-spacing:.35em;text-align:center;margin:.5rem 0 .25rem;padding-left:.35em}
.button{display:block;text-align:center;background:var(--accent);color:var(--accent-fg);text-decoration:none;font-weight:600;padding:.85rem 1rem;border-radius:10px;margin:.75rem 0}
.spinner{width:1.1rem;height:1.1rem;border:3px solid var(--border);border-top-color:var(--accent);border-radius:50%;display:inline-block;vertical-align:-3px;margin-right:.5rem;animation:spin 1s linear infinite}
@keyframes spin{to{transform:rotate(360deg)}}
@media (prefers-reduced-motion:reduce){.spinner{animation:none}}
.hidden{display:none}
"""

    /**
     * Polls the status endpoint every 1.5 s (immediately when the tab becomes visible again, e.g. after
     * switching back from the DroidHands app) and navigates to the decision's redirect. Static: all
     * data comes from `data-*` attributes; DOM updates use textContent only. (The relay's script.)
     */
    private const val WAITING_SCRIPT = """(function () {
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
    approved: 'Approved. Returning to your app…',
    denied: 'Denied on your phone. Returning to your app…',
    expired: 'This request expired. Returning to your app…'
  };
  function show(text, cls) { status.textContent = text; status.className = cls || ''; }
  function finish(d) {
    done = true;
    if (/^\s*(javascript|data|vbscript):/i.test(d.redirect)) return;
    show(MESSAGES[d.status] || 'Returning to your app…', d.status === 'approved' ? '' : 'warn');
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
        if (!r.ok) throw new Error('status ' + r.status);
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
    expiry.textContent = 'The request expires in ' + Math.floor(left / 60) + ':' + ('0' + (left % 60)).slice(-2) + '.';
    setTimeout(tick, 1000);
  }
  document.addEventListener('visibilitychange', function () { if (!document.hidden) schedule(0); });
  tick();
  schedule(1500);
})();"""
}
