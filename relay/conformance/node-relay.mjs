// Starts the built Node relay (dist/) from the environment, like `npm start`, plus the pairing TTLs
// that only the conformance runner needs short (PAIRING_TTL_MS, AUTH_CODE_TTL_MS; not part of the
// Node relay's documented configuration).
import { createRelay, loadConfigFromEnv } from '../dist/relay.js';

const config = loadConfigFromEnv(process.env);
const int = (name) => (process.env[name] ? Number(process.env[name]) : undefined);
const relay = await createRelay({
  ...config,
  pairingTtlMs: int('PAIRING_TTL_MS') ?? config.pairingTtlMs,
  authCodeTtlMs: int('AUTH_CODE_TTL_MS') ?? config.authCodeTtlMs,
});
console.log(`listening on ${relay.url}`);
const stop = () => relay.close().then(() => process.exit(0));
process.on('SIGTERM', stop);
process.on('SIGINT', stop);
