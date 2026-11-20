// The Cloudflare Worker relay with accounts required (ACCOUNTS_URL), run inside workerd like
// vitest.worker.config.mts. The API Worker is stood in for by a service binding that serves the
// public half of a key made here; the tests sign relay tokens with the private half.
import { generateKeyPairSync } from 'node:crypto';
import { cloudflareTest } from '@cloudflare/vitest-plugin';
import { defineConfig } from 'vitest/config';

const { privateKey, publicKey } = generateKeyPairSync('ed25519');
const kid = 'vitest-key';
const publicJwk = { ...publicKey.export({ format: 'jwk' }), kid, alg: 'EdDSA' };
const privateJwk = { ...privateKey.export({ format: 'jwk' }), kid, alg: 'EdDSA' };

export default defineConfig({
  plugins: [
    cloudflareTest({
      wrangler: { configPath: './wrangler.toml' },
      miniflare: {
        bindings: {
          RELAY_SECRET: 'vitest-relay-secret-0123456789abcdefghijklmnop',
          HELLO_TIMEOUT_MS: '2000',
          RATE_LIMIT_REQUESTS_PER_MIN: '100000',
          RATE_LIMIT_AUTH_FAILURES_PER_MIN: '100000',
          LOG_LEVEL: 'silent',
          PUBLIC_URL: '',
          ACCOUNTS_URL: 'https://api.test',
          TEST_SIGNING_JWK: JSON.stringify(privateJwk),
        },
        serviceBindings: {
          ACCOUNTS: (request: Request) =>
            new URL(request.url).pathname === '/api/auth/jwks'
              ? Response.json({ keys: [publicJwk] })
              : new Response('not found', { status: 404 }),
        },
      },
    }),
  ],
  test: {
    include: ['test-worker-accounts/**/*.test.ts'],
    testTimeout: 15_000,
  },
});
