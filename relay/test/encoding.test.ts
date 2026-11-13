import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  base32Encode,
  base64UrlDecodeStrict,
  constantTimeEqual,
  decodeDeviceSecret,
  deriveDeviceId,
  verifyDeviceIdentity,
} from '../src/encoding.js';
import { bearerCredential, parseToken } from '../src/token.js';
import { identityFrom, newIdentity } from './helpers.js';

const VECTOR_SECRET = Buffer.from(Array.from({ length: 32 }, (_, i) => i));

test('PROTOCOL.md §1 device test vector', () => {
  assert.equal(VECTOR_SECRET.toString('base64url'), 'AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8');
  assert.equal(deriveDeviceId(VECTOR_SECRET), 'mmg42klgyqzwneiskrelxms3j4');
  assert.ok(verifyDeviceIdentity('mmg42klgyqzwneiskrelxms3j4', 'AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8'));
  // The test helper's independent implementation agrees.
  assert.equal(identityFrom(VECTOR_SECRET).deviceId, 'mmg42klgyqzwneiskrelxms3j4');
});

test('PROTOCOL.md §1 token secret vector encodes as documented', () => {
  const secret = Buffer.from(Array.from({ length: 32 }, (_, i) => 100 + i));
  assert.equal(secret.toString('base64url'), 'ZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXp7fH1-f4CBgoM');
});

test('base32 matches RFC 4648 vectors (lowercase, unpadded)', () => {
  const vectors: Array<[string, string]> = [
    ['', ''],
    ['f', 'my'],
    ['fo', 'mzxq'],
    ['foo', 'mzxw6'],
    ['foob', 'mzxw6yq'],
    ['fooba', 'mzxw6ytb'],
    ['foobar', 'mzxw6ytboi'],
  ];
  for (const [input, expected] of vectors) assert.equal(base32Encode(Buffer.from(input)), expected, input);
  assert.equal(base32Encode(Buffer.alloc(16, 0xff)), '77777777777777777777777774');
});

test('derived ids agree with an independent implementation', () => {
  for (let i = 0; i < 50; i++) {
    const identity = newIdentity();
    assert.equal(deriveDeviceId(Buffer.from(identity.secret, 'base64url')), identity.deviceId);
    assert.match(identity.deviceId, /^[a-z2-7]{26}$/);
  }
});

test('base64url decoding is strict and canonical', () => {
  assert.deepEqual(base64UrlDecodeStrict('AQID'), Buffer.from([1, 2, 3]));
  assert.equal(base64UrlDecodeStrict('AQ=='), null, 'padding');
  assert.equal(base64UrlDecodeStrict('AQ+/'), null, 'standard alphabet');
  assert.equal(base64UrlDecodeStrict('A'), null, 'impossible length');
  assert.equal(base64UrlDecodeStrict('AR'), null, 'non-zero trailing bits');
});

test('device secrets must be 43 base64url chars decoding to 32 bytes', () => {
  const good = 'AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8';
  assert.equal(decodeDeviceSecret(good)?.length, 32);
  assert.equal(decodeDeviceSecret(`${good}=`), null);
  assert.equal(decodeDeviceSecret(good.slice(0, 42)), null);
  assert.equal(decodeDeviceSecret(`${good.slice(0, 42)}9`), null, 'non-canonical last char');
  assert.equal(decodeDeviceSecret(`${good.slice(0, 42)}+`), null);
});

test('verifyDeviceIdentity rejects mismatches and malformed input', () => {
  const a = newIdentity();
  const b = newIdentity();
  assert.ok(verifyDeviceIdentity(a.deviceId, a.secret));
  assert.equal(verifyDeviceIdentity(b.deviceId, a.secret), false);
  assert.equal(verifyDeviceIdentity(a.deviceId.toUpperCase(), a.secret), false);
  assert.equal(verifyDeviceIdentity(a.deviceId.slice(0, 25), a.secret), false);
  assert.equal(verifyDeviceIdentity(a.deviceId, 'short'), false);
});

test('constantTimeEqual', () => {
  assert.ok(constantTimeEqual('abc', 'abc'));
  assert.equal(constantTimeEqual('abc', 'abd'), false);
  assert.equal(constantTimeEqual('abc', 'abcd'), false);
  assert.equal(constantTimeEqual('', 'a'), false);
  assert.ok(constantTimeEqual('', ''));
});

test('parseToken validates the shape and extracts the deviceId', () => {
  const id = 'mmg42klgyqzwneiskrelxms3j4';
  const token = `pb1.${id}.abcdefghij23.ZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXp7fH1-f4CBgoM`;
  assert.deepEqual(parseToken(token), { token, deviceId: id });
  assert.equal(parseToken(`pb2.${token.slice(4)}`), null);
  assert.equal(parseToken(`${token}x`), null);
  assert.equal(parseToken(token.replace('abcdefghij23', 'abcdefghij2')), null);
  assert.equal(parseToken(token.replace(id, id.toUpperCase())), null);
  assert.equal(parseToken(`${token}\n`), null);
  assert.equal(parseToken(''), null);
});

test('bearerCredential: scheme is case-insensitive, other schemes rejected', () => {
  assert.equal(bearerCredential('Bearer abc'), 'abc');
  assert.equal(bearerCredential('bearer abc'), 'abc');
  assert.equal(bearerCredential('BEARER   abc  '), 'abc');
  assert.equal(bearerCredential('Basic abc'), null);
  assert.equal(bearerCredential('Bearer'), null);
  assert.equal(bearerCredential('Bearer a b'), null);
  assert.equal(bearerCredential(undefined), null);
});
