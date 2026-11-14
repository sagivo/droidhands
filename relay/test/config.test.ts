import assert from 'node:assert/strict';
import { test } from 'node:test';
import { ConfigError, DEFAULT_CONFIG, isOriginAllowed, loadConfigFromEnv, resolveConfig } from '../src/config.js';

test('defaults match the documented values', () => {
  const config = loadConfigFromEnv({});
  assert.deepEqual(config, resolveConfig());
  assert.equal(config.port, 8787);
  assert.equal(config.host, '0.0.0.0');
  assert.equal(config.publicUrl, null);
  assert.equal(config.trustProxy, false);
  assert.equal(config.accessKey, null);
  assert.equal(config.allowTokenInUrl, false);
  assert.deepEqual(config.allowedOrigins, []);
  assert.equal(config.allowImplicitDevice, false, 'authorize requires `resource` by default');
  assert.equal(config.requestTimeoutMs, 150_000);
  assert.equal(config.maxBodyBytes, 1_048_576);
  assert.equal(config.maxInflightPerDevice, 32);
  assert.equal(config.helloTimeoutMs, 10_000);
  assert.equal(config.maxPendingHandshakes, 512);
  assert.equal(config.maxPendingHandshakesPerIp, 16);
  assert.equal(config.logLevel, 'info');
  assert.equal(config.rateLimitRequestsPerMin, 600);
  assert.equal(config.rateLimitAuthFailuresPerMin, 30);
  assert.equal(config.relaySecret, null);
  assert.equal(config.dataDir, './data');
  assert.match(config.publicDir, /public$/);
  assert.equal(config.setupBanner, true);
  assert.equal(config.pairingTtlMs, 180_000);
  assert.equal(config.authCodeTtlMs, 120_000);
  assert.equal(DEFAULT_CONFIG.heartbeatIntervalMs, 25_000);
});

test('reads every variable', () => {
  const config = loadConfigFromEnv({
    PORT: '9000',
    HOST: '127.0.0.1',
    PUBLIC_URL: 'https://Relay.Example.com/base/',
    TRUST_PROXY: 'true',
    RELAY_ACCESS_KEY: ' s3cret ',
    ALLOW_TOKEN_IN_URL: '1',
    ALLOWED_ORIGINS: 'https://A.example, https://b.example/ ,',
    ALLOW_IMPLICIT_DEVICE: 'true',
    REQUEST_TIMEOUT_MS: '1000',
    MAX_BODY_BYTES: '2048',
    MAX_INFLIGHT_PER_DEVICE: '4',
    HELLO_TIMEOUT_MS: '500',
    MAX_PENDING_HANDSHAKES: '64',
    MAX_PENDING_HANDSHAKES_PER_IP: '4',
    LOG_LEVEL: 'DEBUG',
    RATE_LIMIT_REQUESTS_PER_MIN: '100',
    RATE_LIMIT_AUTH_FAILURES_PER_MIN: '5',
    RELAY_SECRET: ' a-relay-secret-that-is-long-enough-1234 ',
    DATA_DIR: '/var/lib/phonebridge',
    PUBLIC_DIR: '/srv/public',
    SETUP_BANNER: 'off',
  });
  assert.equal(config.port, 9000);
  assert.equal(config.host, '127.0.0.1');
  assert.equal(config.publicUrl, 'https://relay.example.com/base');
  assert.equal(config.trustProxy, true);
  assert.equal(config.accessKey, ' s3cret ', 'access key is used verbatim');
  assert.equal(config.allowTokenInUrl, true);
  assert.deepEqual(config.allowedOrigins, ['https://a.example', 'https://b.example']);
  assert.equal(config.allowImplicitDevice, true);
  assert.equal(config.requestTimeoutMs, 1000);
  assert.equal(config.maxBodyBytes, 2048);
  assert.equal(config.maxInflightPerDevice, 4);
  assert.equal(config.helloTimeoutMs, 500);
  assert.equal(config.maxPendingHandshakes, 64);
  assert.equal(config.maxPendingHandshakesPerIp, 4);
  assert.equal(config.logLevel, 'debug');
  assert.equal(config.rateLimitRequestsPerMin, 100);
  assert.equal(config.rateLimitAuthFailuresPerMin, 5);
  assert.equal(config.relaySecret, ' a-relay-secret-that-is-long-enough-1234 ', 'relay secret is used verbatim');
  assert.equal(config.dataDir, '/var/lib/phonebridge');
  assert.equal(config.publicDir, '/srv/public');
  assert.equal(config.setupBanner, false);
});

test('PUBLIC_URL defaults to the address announced by Render or Fly.io', () => {
  assert.equal(loadConfigFromEnv({ RENDER_EXTERNAL_URL: 'https://pb.onrender.com' }).publicUrl, 'https://pb.onrender.com');
  assert.equal(loadConfigFromEnv({ FLY_APP_NAME: 'my-relay' }).publicUrl, 'https://my-relay.fly.dev');
  assert.equal(
    loadConfigFromEnv({ PUBLIC_URL: 'https://relay.example.com', FLY_APP_NAME: 'my-relay' }).publicUrl,
    'https://relay.example.com',
    'an explicit PUBLIC_URL wins',
  );
  assert.throws(() => loadConfigFromEnv({ RENDER_EXTERNAL_URL: 'nope' }), /RENDER_EXTERNAL_URL/);
});

test('empty values fall back to defaults', () => {
  const config = loadConfigFromEnv({ PORT: '', PUBLIC_URL: '  ', RELAY_ACCESS_KEY: '' });
  assert.equal(config.port, 8787);
  assert.equal(config.publicUrl, null);
  assert.equal(config.accessKey, null);
});

test('invalid values are rejected with the variable name', () => {
  const cases: Array<[Record<string, string>, RegExp]> = [
    [{ PORT: 'abc' }, /PORT/],
    [{ PORT: '70000' }, /PORT/],
    [{ MAX_BODY_BYTES: '0' }, /MAX_BODY_BYTES/],
    [{ MAX_BODY_BYTES: '99999999' }, /MAX_BODY_BYTES/],
    [{ MAX_INFLIGHT_PER_DEVICE: '0' }, /MAX_INFLIGHT_PER_DEVICE/],
    [{ REQUEST_TIMEOUT_MS: '-5' }, /REQUEST_TIMEOUT_MS/],
    [{ TRUST_PROXY: 'maybe' }, /TRUST_PROXY/],
    [{ LOG_LEVEL: 'loud' }, /LOG_LEVEL/],
    [{ PUBLIC_URL: 'relay.example.com' }, /PUBLIC_URL/],
    [{ PUBLIC_URL: 'ftp://relay.example.com' }, /PUBLIC_URL/],
    [{ PUBLIC_URL: 'https://relay.example.com/?x=1' }, /PUBLIC_URL/],
    [{ RELAY_SECRET: 'too-short' }, /RELAY_SECRET/],
    [{ SETUP_BANNER: 'sometimes' }, /SETUP_BANNER/],
    [{ ALLOW_IMPLICIT_DEVICE: 'maybe' }, /ALLOW_IMPLICIT_DEVICE/],
    [{ MAX_PENDING_HANDSHAKES: '0' }, /MAX_PENDING_HANDSHAKES/],
    [{ MAX_PENDING_HANDSHAKES_PER_IP: '0' }, /MAX_PENDING_HANDSHAKES_PER_IP/],
  ];
  for (const [env, pattern] of cases) {
    assert.throws(() => loadConfigFromEnv(env), (err: unknown) => err instanceof ConfigError && pattern.test(err.message));
  }
});

test('origin allowlist matching', () => {
  const allowed = resolveConfig({ allowedOrigins: ['https://App.example/'] }).allowedOrigins;
  assert.ok(isOriginAllowed('https://app.example', allowed));
  assert.ok(isOriginAllowed('https://APP.example', allowed));
  assert.equal(isOriginAllowed('https://evil.example', allowed), false);
  assert.equal(isOriginAllowed('null', allowed), false);
  assert.equal(isOriginAllowed('https://app.example', []), false);
  assert.ok(isOriginAllowed('https://anything.example', ['*']));
});
