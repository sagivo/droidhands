import { AccountVerifier, httpJwksFetcher, JWKS_PATH } from '../account.js';
import type { Env, WorkerConfig } from './config.js';

/**
 * One [AccountVerifier] per isolate and API server, so the API server's keys are fetched
 * once rather than for every phone connection. With the `ACCOUNTS` service binding (wrangler.toml)
 * the keys come straight from the API Worker; otherwise over the internet from ACCOUNTS_URL.
 */
const verifiers = new Map<string, AccountVerifier>();

export function accountVerifier(env: Env, cfg: WorkerConfig): AccountVerifier | null {
  const issuer = cfg.relay.accountsUrl;
  if (issuer === null) return null;
  let verifier = verifiers.get(issuer);
  if (verifier === undefined) {
    const binding = env.ACCOUNTS;
    const fetcher =
      binding === undefined
        ? httpJwksFetcher(issuer)
        : httpJwksFetcher(issuer, (_input, init) => binding.fetch(`${issuer}${JWKS_PATH}`, init));
    verifier = new AccountVerifier(issuer, fetcher);
    verifiers.set(issuer, verifier);
  }
  return verifier;
}
