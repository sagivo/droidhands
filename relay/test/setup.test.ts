import assert from 'node:assert/strict';
import type { AddressInfo } from 'node:net';
import { test } from 'node:test';
import { setupBanner } from '../src/setup/banner.js';
import { qrMatrix, qrPath, qrSvg, qrTerminal } from '../src/setup/qr.js';
import { guessBaseUrl, setupUrl } from '../src/setup/setupUrl.js';

test('setup URL carries the access key only in the fragment, percent-encoded', () => {
  assert.equal(setupUrl('https://r.example', null), 'https://r.example/setup');
  assert.equal(setupUrl('https://r.example', 'a+b/c='), 'https://r.example/setup#key=a%2Bb%2Fc%3D');
  assert.equal(new URLSearchParams(new URL(setupUrl('https://r.example', 'a+b/c= d')).hash.slice(1)).get('key'), 'a+b/c= d');
});

test('base URL guess: PUBLIC_URL, else the first external IPv4 for wildcard binds', () => {
  const wildcard: AddressInfo = { address: '0.0.0.0', family: 'IPv4', port: 8787 };
  const interfaces = {
    lo0: [{ address: '127.0.0.1', family: 'IPv4', internal: true } as never],
    en0: [{ address: 'fe80::1', family: 'IPv6', internal: false } as never, { address: '192.168.1.20', family: 'IPv4', internal: false } as never],
  };
  assert.equal(guessBaseUrl('https://relay.example.com', wildcard, interfaces), 'https://relay.example.com');
  assert.equal(guessBaseUrl(null, wildcard, interfaces), 'http://192.168.1.20:8787');
  assert.equal(guessBaseUrl(null, wildcard, {}), 'http://127.0.0.1:8787');
  assert.equal(guessBaseUrl(null, { address: '10.0.0.5', family: 'IPv4', port: 1 }, interfaces), 'http://10.0.0.5:1');
  assert.equal(guessBaseUrl(null, { address: '::1', family: 'IPv6', port: 2 }, interfaces), 'http://[::1]:2');
});

test('QR renderers agree with the module matrix', () => {
  const text = 'https://relay.example.com/setup#key=abc';
  const matrix = qrMatrix(text);
  assert.ok(matrix.size >= 21 && (matrix.size - 17) % 4 === 0, 'a valid QR version size');
  // Finder pattern corners are dark; the quiet zone is light.
  assert.ok(matrix.dark(0, 0) && matrix.dark(0, matrix.size - 1) && matrix.dark(matrix.size - 1, 0));
  assert.equal(matrix.dark(-1, 0), false);

  const { size, path } = qrPath(text);
  assert.equal(size, matrix.size + 8);
  let dark = 0;
  for (const m of path.matchAll(/M(\d+) (\d+)h(\d+)v1h-(\d+)z/g)) {
    assert.equal(m[3], m[4]);
    dark += Number(m[3]);
  }
  let expected = 0;
  for (let r = 0; r < matrix.size; r++) for (let c = 0; c < matrix.size; c++) if (matrix.dark(r, c)) expected++;
  assert.equal(dark, expected);

  const svg = qrSvg(text, 'label "x"');
  assert.match(svg, /^<svg class="qr" [^>]*aria-label="label x"/);

  const lines = qrTerminal(text).split('\n');
  assert.equal(lines.length, Math.ceil((matrix.size + 4) / 2));
  for (const line of lines) {
    assert.ok(line.startsWith('\x1b[30;47m') && line.endsWith('\x1b[0m'));
    assert.equal(Array.from(line.replace(/\x1b\[[0-9;]*m/g, '')).length, matrix.size + 4);
  }
});

test('startup banner shows the URL, a QR code and a hint when the address is guessed', () => {
  const banner = setupBanner('http://192.168.1.20:8787/setup#key=k', { guessed: true });
  assert.match(banner, /http:\/\/192\.168\.1\.20:8787\/setup#key=k/);
  assert.match(banner, /▀|▄|█/);
  assert.match(banner, /PUBLIC_URL is not set/);
  assert.doesNotMatch(setupBanner('https://r.example/setup', { guessed: false }), /PUBLIC_URL/);
});
