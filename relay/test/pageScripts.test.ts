/**
 * Runs the inline scripts of the relay's pages in a `vm` sandbox with a tiny fake DOM, against a
 * real relay, to check the client-side behavior (polling, navigation, setup links).
 */
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { runInNewContext } from 'node:vm';
import { connectReady, httpRequest, startRelay } from './helpers.js';
import { approveNext, denyNext, newPkce, openAuthorize, registerClient } from './oauthHelpers.js';

interface FakeElement {
  attrs: Record<string, string>;
  children: FakeElement[];
  textContent: string;
  className: string;
  href: string;
  getAttribute(name: string): string | null;
  setAttribute(name: string, value: string): void;
  appendChild(child: FakeElement): void;
}

function fakeElement(attrs: Record<string, string> = {}): FakeElement {
  return {
    attrs,
    children: [],
    textContent: '',
    className: '',
    href: '',
    getAttribute(name) {
      return this.attrs[name] ?? null;
    },
    setAttribute(name, value) {
      this.attrs[name] = value;
    },
    appendChild(child) {
      this.children.push(child);
    },
  };
}

interface Browser {
  readonly elements: Map<string, FakeElement>;
  readonly navigations: string[];
  readonly timers: Array<{ fn: () => void; ms: number }>;
  /** Runs the most recent pending timer whose function has [name]. */
  fire(name: string): void;
}

/** Executes the page's inline script with the page's `data-*` attributes on [rootId]. */
function runPageScript(body: string, rootId: string, base: string, hash = ''): Browser {
  const script = /<script nonce="[^"]+">([\s\S]*?)<\/script>/.exec(body)?.[1];
  assert.ok(script !== undefined, 'page has an inline script');
  const rootTag = new RegExp(`<div id="${rootId}"([^>]*)>`).exec(body)?.[1] ?? '';
  const attrs: Record<string, string> = {};
  for (const m of rootTag.matchAll(/([a-z-]+)="([^"]*)"/g)) attrs[m[1] as string] = (m[2] as string).replace(/&amp;/g, '&');

  const elements = new Map<string, FakeElement>([[rootId, fakeElement(attrs)]]);
  const navigations: string[] = [];
  const timers: Browser['timers'] = [];
  const sandbox = {
    document: {
      hidden: false,
      getElementById: (id: string) => {
        if (!elements.has(id)) elements.set(id, fakeElement());
        return elements.get(id);
      },
      createElementNS: () => fakeElement(),
      addEventListener: () => undefined,
    },
    history: { replaceState: () => undefined },
    location: { hash, replace: (url: string) => navigations.push(url) },
    window: { fetch: true },
    fetch: (url: string, init: RequestInit) => fetch(new URL(url, base), init),
    setTimeout: (fn: () => void, ms: number) => timers.push({ fn, ms }),
    clearTimeout: () => undefined,
    URLSearchParams,
    JSON,
    Date,
    Number,
    Math,
    String,
    Error,
  };
  runInNewContext(script, sandbox);
  return {
    elements,
    navigations,
    timers,
    fire(name) {
      const index = timers.map((t) => t.fn.name).lastIndexOf(name);
      assert.ok(index >= 0, `a pending ${name} timer`);
      timers.splice(index, 1)[0]?.fn();
    },
  };
}

async function eventually(condition: () => boolean, what: string): Promise<void> {
  const deadline = Date.now() + 3_000;
  while (!condition()) {
    if (Date.now() > deadline) throw new Error(`timed out waiting for ${what}`);
    await new Promise((r) => setTimeout(r, 5));
  }
}

test('waiting page script: keeps polling while pending, then navigates to the redirect', async (t) => {
  const relay = await startRelay(t);
  const device = await connectReady(relay);
  const page = await openAuthorize(relay, { clientId: await registerClient(relay), pkce: newPkce(), deviceId: device.identity.deviceId });
  const browser = runPageScript(page.res.body, 'pairing', relay.url);
  assert.match(browser.elements.get('expiry')?.textContent ?? '', /Expires in 3:00/);

  browser.fire('poll');
  await eventually(() => browser.timers.some((t) => t.fn.name === 'poll'), 'the next poll');
  assert.equal(browser.navigations.length, 0, 'still pending');

  await approveNext(device);
  browser.fire('poll');
  await eventually(() => browser.navigations.length === 1, 'navigation');
  assert.match(browser.navigations[0] as string, /^http:\/\/localhost:33418\/callback\?code=[A-Za-z0-9_-]{43}&state=state-123&iss=/);
  assert.equal(browser.elements.get('next')?.href, browser.navigations[0]);
  assert.match(browser.elements.get('status')?.textContent ?? '', /Allowed/);
});

test('waiting page script: a denial navigates to the access_denied redirect', async (t) => {
  const relay = await startRelay(t);
  const device = await connectReady(relay);
  const page = await openAuthorize(relay, { clientId: await registerClient(relay), pkce: newPkce(), deviceId: device.identity.deviceId });
  const browser = runPageScript(page.res.body, 'pairing', relay.url);
  await denyNext(device);
  browser.fire('poll');
  await eventually(() => browser.navigations.length === 1, 'navigation');
  assert.match(browser.navigations[0] as string, /[?&]error=access_denied/);
  assert.match(browser.elements.get('status')?.textContent ?? '', /Denied on your phone/);
});

test('setup page script: builds the app links with the key from the fragment and fetches the QR', async (t) => {
  const accessKey = 'k+y/= with space';
  const relay = await startRelay(t, { accessKey });
  const page = await httpRequest(`${relay.url}/setup`);
  const browser = runPageScript(page.body, 'setup', relay.url, `#key=${encodeURIComponent(accessKey)}`);
  const query = `relay=${encodeURIComponent(relay.url)}&key=${encodeURIComponent(accessKey)}`;
  assert.equal(
    browser.elements.get('open')?.href,
    `intent://configure?${query}#Intent;scheme=droidhands;package=com.droidhands.android;end`,
  );
  assert.equal(browser.elements.get('open-direct')?.href, `droidhands://configure?${query}`);
  assert.equal(browser.elements.get('manual-key')?.textContent, accessKey);
  assert.notEqual(browser.elements.get('key-missing')?.className, 'warn', 'no missing-key warning');

  const qr = (): FakeElement | undefined => browser.elements.get('qr')?.children[0];
  await eventually(() => qr() !== undefined, 'the QR code');
  assert.match(qr()?.attrs.viewBox ?? '', /^0 0 \d+ \d+$/);
  assert.match(qr()?.children[1]?.attrs.d ?? '', /^(M\d+ \d+h\d+v1h-\d+z)+$/);
  assert.equal(browser.elements.get('qr-card')?.className, 'card desktop-only');
});

test('setup page script: warns when the relay needs a key and the link has none', async (t) => {
  const relay = await startRelay(t, { accessKey: 'secret' });
  const page = await httpRequest(`${relay.url}/setup`);
  const browser = runPageScript(page.body, 'setup', relay.url);
  assert.equal(browser.elements.get('key-missing')?.className, 'warn');
  assert.equal(browser.elements.get('open-direct')?.href, `droidhands://configure?relay=${encodeURIComponent(relay.url)}`);
});
