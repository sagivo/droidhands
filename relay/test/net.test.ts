import assert from 'node:assert/strict';
import type { IncomingMessage } from 'node:http';
import { test } from 'node:test';
import { clientAddress, expandIpv6, normalizeIp, publicBaseUrl, rateLimitKey } from '../src/net.js';

function fakeReq(headers: Record<string, string>, socket: Record<string, unknown> = {}): IncomingMessage {
  return {
    headers,
    socket: { remoteAddress: '10.0.0.1', localAddress: '127.0.0.1', localPort: 8787, ...socket },
  } as unknown as IncomingMessage;
}

test('normalizeIp strips IPv4-mapped prefixes and zone ids', () => {
  assert.equal(normalizeIp('::ffff:203.0.113.7'), '203.0.113.7');
  assert.equal(normalizeIp('::FFFF:203.0.113.7'), '203.0.113.7');
  assert.equal(normalizeIp('fe80::1%eth0'), 'fe80::1');
  assert.equal(normalizeIp('2001:DB8::1'), '2001:db8::1');
  assert.equal(normalizeIp('198.51.100.1'), '198.51.100.1');
});

test('expandIpv6', () => {
  assert.deepEqual(expandIpv6('::'), ['0', '0', '0', '0', '0', '0', '0', '0']);
  assert.deepEqual(expandIpv6('::1'), ['0', '0', '0', '0', '0', '0', '0', '1']);
  assert.deepEqual(expandIpv6('2001:db8::'), ['2001', 'db8', '0', '0', '0', '0', '0', '0']);
  assert.deepEqual(expandIpv6('2001:0db8:0001:0002:0003:0004:0005:0006'), ['2001', 'db8', '1', '2', '3', '4', '5', '6']);
  assert.deepEqual(expandIpv6('64:ff9b::192.0.2.33'), ['64', 'ff9b', '0', '0', '0', '0', 'c000', '221']);
  assert.equal(expandIpv6('1::2::3'), null);
  assert.equal(expandIpv6('1:2:3'), null);
  assert.equal(expandIpv6('1:2:3:4:5:6:7:8:9'), null);
  assert.equal(expandIpv6('gggg::1'), null);
});

test('rateLimitKey groups IPv6 by /64 and keeps IPv4 as is', () => {
  assert.equal(rateLimitKey('203.0.113.7'), '203.0.113.7');
  assert.equal(rateLimitKey('::ffff:203.0.113.7'), '203.0.113.7');
  assert.equal(rateLimitKey('2001:db8:1:2:aaaa::1'), '2001:db8:1:2::/64');
  assert.equal(rateLimitKey('2001:db8:1:2:bbbb::9'), '2001:db8:1:2::/64');
  assert.notEqual(rateLimitKey('2001:db8:1:3::1'), rateLimitKey('2001:db8:1:2::1'));
  assert.equal(rateLimitKey('unknown'), 'unknown');
});

test('clientAddress ignores X-Forwarded-For unless the proxy is trusted', () => {
  const req = fakeReq({ 'x-forwarded-for': '1.1.1.1, 203.0.113.9' }, { remoteAddress: '::ffff:10.0.0.2' });
  assert.equal(clientAddress(req, false), '10.0.0.2');
  // Right-most entry: the one appended by the trusted proxy.
  assert.equal(clientAddress(req, true), '203.0.113.9');
  assert.equal(clientAddress(fakeReq({ 'x-forwarded-for': 'garbage' }), true), '10.0.0.1');
  assert.equal(clientAddress(fakeReq({}), true), '10.0.0.1');
});

test('publicBaseUrl prefers PUBLIC_URL', () => {
  const req = fakeReq({ host: 'ignored.example' });
  assert.equal(publicBaseUrl(req, 'https://relay.example.com', false), 'https://relay.example.com');
});

test('publicBaseUrl derives from Host, and X-Forwarded-* only behind a trusted proxy', () => {
  const req = fakeReq({ host: 'Relay.local:8787', 'x-forwarded-proto': 'https', 'x-forwarded-host': 'relay.example.com' });
  assert.equal(publicBaseUrl(req, null, false), 'http://relay.local:8787');
  assert.equal(publicBaseUrl(req, null, true), 'https://relay.example.com');
  assert.equal(publicBaseUrl(fakeReq({ host: 'a.example', 'x-forwarded-proto': 'wss, http' }), null, true), 'https://a.example');
  assert.equal(publicBaseUrl(fakeReq({ host: '[::1]:8787' }), null, false), 'http://[::1]:8787');
});

test('publicBaseUrl falls back to the local address for missing or bogus Host headers', () => {
  assert.equal(publicBaseUrl(fakeReq({}), null, false), 'http://127.0.0.1:8787');
  assert.equal(publicBaseUrl(fakeReq({ host: 'evil.example/path?x' }), null, false), 'http://127.0.0.1:8787');
  assert.equal(
    publicBaseUrl(fakeReq({}, { localAddress: '::1', localPort: 9 }), null, false),
    'http://[::1]:9',
  );
});
