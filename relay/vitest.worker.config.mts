// Unit/integration tests of the Cloudflare Worker relay, run inside workerd by
// @cloudflare/vitest-plugin (the successor of @cloudflare/vitest-pool-workers). `npm run test:worker`.
import { cloudflareTest } from '@cloudflare/vitest-plugin';
import { defineConfig } from 'vitest/config';

export default defineConfig({
  plugins: [
    cloudflareTest({
      wrangler: { configPath: './wrangler.toml' },
      miniflare: {
        // Short limits so timeouts, expirations and caps are quick to reach.
        bindings: {
          RELAY_SECRET: 'vitest-relay-secret-0123456789abcdefghijklmnop',
          HELLO_TIMEOUT_MS: '300',
          REQUEST_TIMEOUT_MS: '1000',
          PAIRING_TTL_MS: '1500',
          AUTH_CODE_TTL_MS: '1500',
          MAX_INFLIGHT_PER_DEVICE: '2',
          MAX_PENDING_HANDSHAKES_PER_IP: '2',
          HEARTBEAT_TIMEOUT_MS: '400',
          RATE_LIMIT_REQUESTS_PER_MIN: '100000',
          RATE_LIMIT_AUTH_FAILURES_PER_MIN: '100000',
          LOG_LEVEL: 'silent',
          // wrangler.toml's PUBLIC_URL is production's; the tests address the relay as BASE.
          PUBLIC_URL: '',
        },
      },
    }),
  ],
  test: {
    include: ['test-worker/**/*.test.ts'],
    testTimeout: 15_000,
  },
});
