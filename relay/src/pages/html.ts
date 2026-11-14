import { randomBytes } from 'node:crypto';
import type { IncomingMessage, ServerResponse } from 'node:http';
import { sendBody } from '../httpUtil.js';

/**
 * Minimal, injection-safe HTML templating for the relay's few pages, plus their security headers.
 *
 * Every interpolated value in an [html] template is HTML-escaped unless it is itself [SafeHtml]
 * (the result of another template). Attribute values must be double-quoted in templates. Pages use
 * no external assets; inline `<style>`/`<script>` blocks carry a per-response CSP nonce.
 */

/** Markup that is already safe to embed. Only [html] creates it. */
export class SafeHtml {
  /** @internal Use the [html] tag instead. */
  constructor(readonly value: string) {}

  toString(): string {
    return this.value;
  }
}

type Interpolation = SafeHtml | string | number | boolean | null | undefined | readonly Interpolation[];

/** Tagged template that escapes every interpolation (see file comment). */
export function html(strings: TemplateStringsArray, ...values: Interpolation[]): SafeHtml {
  let out = strings[0] ?? '';
  values.forEach((value, i) => {
    out += render(value) + (strings[i + 1] ?? '');
  });
  return new SafeHtml(out);
}

function render(value: Interpolation): string {
  if (value instanceof SafeHtml) return value.value;
  if (Array.isArray(value)) return value.map(render).join('');
  if (value === null || value === undefined || value === false) return '';
  return escapeHtml(String(value));
}

const ESCAPES: Record<string, string> = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };

/** Escapes the five HTML-significant characters (safe in text and in quoted attributes). */
export function escapeHtml(text: string): string {
  return text.replace(/[&<>"']/g, (c) => ESCAPES[c] as string);
}

export interface PageOptions {
  readonly title: string;
  readonly body: SafeHtml;
  /** Extra markup for `<head>` (e.g. a noscript refresh). */
  readonly head?: SafeHtml;
  /** Inline script source (trusted, static; pass data through `data-*` attributes). */
  readonly script?: string;
}

/** A rendered page: the document and the security headers it must be sent with. */
export interface RenderedPage {
  readonly html: string;
  /** `Content-Security-Policy` (with this page's nonce), `Referrer-Policy`, `X-Frame-Options`. */
  readonly headers: Readonly<Record<string, string>>;
}

/**
 * Renders a complete HTML page with a strict CSP (`default-src 'none'`, nonce-only inline style and
 * script, same-origin fetch, no framing, no forms), `Referrer-Policy: no-referrer` and
 * `X-Frame-Options: DENY`. Shared by the Node and Cloudflare Worker relays.
 */
export function renderPage(page: PageOptions): RenderedPage {
  const nonce = randomBytes(16).toString('base64');
  const document = html`<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="color-scheme" content="light dark">
<meta name="robots" content="noindex, nofollow">
<title>${page.title}</title>
<style nonce="${nonce}">${new SafeHtml(STYLES)}</style>
${page.head}
</head>
<body>
<main>
${page.body}
</main>
${page.script === undefined ? '' : html`<script nonce="${nonce}">${new SafeHtml(page.script)}</script>`}
</body>
</html>
`;
  return {
    html: document.value,
    headers: {
      'Content-Security-Policy': [
        "default-src 'none'",
        `style-src 'nonce-${nonce}'`,
        `script-src 'nonce-${nonce}'`,
        "img-src 'self'",
        "connect-src 'self'",
        "base-uri 'none'",
        "form-action 'none'",
        "frame-ancestors 'none'",
      ].join('; '),
      'Referrer-Policy': 'no-referrer',
      'X-Frame-Options': 'DENY',
    },
  };
}

/** Sends [page] (see [renderPage]) with [status]. */
export function sendPage(req: IncomingMessage, res: ServerResponse, status: number, page: PageOptions): void {
  const rendered = renderPage(page);
  for (const [name, value] of Object.entries(rendered.headers)) res.setHeader(name, value);
  sendBody(req, res, status, 'text/html; charset=utf-8', rendered.html);
}

/** A simple page with a heading, paragraphs and an optional hint. */
export function messagePage(title: string, paragraphs: readonly (string | SafeHtml)[], extra?: SafeHtml): PageOptions {
  return {
    title: `${title} · PhoneBridge`,
    body: html`<h1>${title}</h1>${paragraphs.map((p) => html`<p>${p}</p>`)}${extra}`,
  };
}

/** Sends a [messagePage]. */
export function sendMessagePage(
  req: IncomingMessage,
  res: ServerResponse,
  status: number,
  title: string,
  paragraphs: readonly (string | SafeHtml)[],
  extra?: SafeHtml,
): void {
  sendPage(req, res, status, messagePage(title, paragraphs, extra));
}

/** Shared stylesheet: system fonts, mobile-first, dark-mode aware. */
const STYLES = `
:root{--bg:#f6f7f9;--card:#fff;--fg:#1b1d21;--muted:#5d636e;--accent:#1a73e8;--accent-fg:#fff;--border:#dfe2e7;--warn:#a15c00}
@media (prefers-color-scheme:dark){:root{--bg:#121417;--card:#1d2025;--fg:#eceef1;--muted:#a3a9b3;--accent:#8ab4f8;--accent-fg:#0b1a33;--border:#30343b;--warn:#f0b35a}}
*{box-sizing:border-box}
html{-webkit-text-size-adjust:100%}
body{margin:0;background:var(--bg);color:var(--fg);font:16px/1.5 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
main{max-width:34rem;margin:0 auto;padding:2rem 1.25rem 3rem}
h1{font-size:1.5rem;line-height:1.25;margin:0 0 1rem}
h2{font-size:1.1rem;margin:0 0 .5rem}
p{margin:.75rem 0}
a{color:var(--accent)}
code,.mono{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;word-break:break-all}
.card{background:var(--card);border:1px solid var(--border);border-radius:14px;padding:1.25rem;margin:1rem 0}
.muted{color:var(--muted);font-size:.925rem}
.warn{color:var(--warn)}
.code{font:700 2.5rem/1.1 ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;letter-spacing:.35em;text-align:center;margin:.5rem 0 .25rem;padding-left:.35em}
.button{display:block;text-align:center;background:var(--accent);color:var(--accent-fg);text-decoration:none;font-weight:600;padding:.85rem 1rem;border-radius:10px;margin:.75rem 0}
.button.secondary{background:transparent;color:var(--accent);border:1px solid var(--border)}
.spinner{width:1.1rem;height:1.1rem;border:3px solid var(--border);border-top-color:var(--accent);border-radius:50%;display:inline-block;vertical-align:-3px;margin-right:.5rem;animation:spin 1s linear infinite}
@keyframes spin{to{transform:rotate(360deg)}}
@media (prefers-reduced-motion:reduce){.spinner{animation:none}}
ol{padding-left:1.25rem}
li{margin:.4rem 0}
.qr{display:block;width:100%;max-width:240px;height:auto;margin:.5rem auto;background:#fff;border-radius:8px}
.hidden{display:none}
@media (max-width:600px){.desktop-only{display:none}}
`;
